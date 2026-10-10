package org.tinytube.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The seconds of every unwatched video that playback will need first, kept on disk from the
 * moment the video is known.
 *
 * <p>The extractor's start-up needs the container header, the first fragment and a glance at
 * the second fragment's moof -- 1-3 MB of video, a few hundred KB of audio -- whatever position
 * it then seeks to; and for a video with a resume position it needs the fragment there plus a
 * glance at the one after. Every byte of that used to be fetched at tap time. With them on
 * disk, {@link Source} is seeded: the extractor parses, seeks and decodes the first frames out
 * of memory while the producer fetches what follows, and playback streams on from where the
 * stored bytes end. Together with the resolved streams in {@link Yt}'s cache, a tap on any
 * unwatched row costs the codecs and the activity, not the network.
 *
 * <p>Files, under {@code filesDir/heads}: the prefix {@code <id>.<itag>.<bytes>.v} / {@code .a},
 * and for a video with a resume position the island {@code <id>.<itag>.<bytes>.<posMs>.vi} /
 * {@code .ai}, found by walking the {@code sidx} box in the prefix. The names pin the exact
 * stream and position, so nothing stale is ever used: a different format or a moved position
 * is simply a miss, and the old files are replaced. Fetched one video at a time in the
 * background on Wi-Fi -- ~2.3 MB per video, plus a fragment for one in
 * progress -- and deleted when the video is marked watched.
 */
final class Heads {
    private Heads() {}

    private static final int VIDEO_SECONDS = 6;
    private static final int VIDEO_MIN = 1 << 20, VIDEO_MAX = 2 << 20;
    private static final int AUDIO_BYTES = 256 << 10;
    /** How much of the fragment after the resume fragment to include: its moof, for the glance. */
    private static final int GLANCE = 64 << 10;
    /** Pause between fetches during a backfill, so forty videos do not hammer InnerTube. */
    private static final long PACE_MS = 300;
    /** A media 403 across videos means further head requests are unlikely to succeed now. */
    private static final long FORBIDDEN_PAUSE_MS = 15 * 60 * 1000;
    /** Accessed only by Yt.BACKGROUND, which runs head jobs one at a time. */
    private static long forbiddenUntil;

    /** What was on disk for a video, ready to seed its two sources. */
    static final class Loaded {
        byte[] videoHead, audioHead, videoIsland, audioIsland;
        long videoIslandAt, audioIslandAt;
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "heads");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static String stem(String id, Yt.Stream s) { return id + "." + s.itag + "." + s.bytes; }

    private static File prefixFile(Context ctx, String id, Yt.Stream s, boolean video) {
        return new File(dir(ctx), stem(id, s) + (video ? ".v" : ".a"));
    }

    private static File islandFile(Context ctx, String id, Yt.Stream s, int posMs, boolean video) {
        return new File(dir(ctx), stem(id, s) + "." + posMs + (video ? ".vi" : ".ai"));
    }

    /** True when everything this video needs at this position is on disk. */
    private static boolean has(Context ctx, String id, int posMs) {
        boolean v = false, a = false, vi = posMs <= 0, ai = posMs <= 0;
        File[] files = dir(ctx).listFiles();
        if (files == null) return false;
        String islandTail = "." + posMs;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith(id + ".")) continue;
            if (n.endsWith(".v")) v = true;
            else if (n.endsWith(".a")) a = true;
            else if (n.endsWith(islandTail + ".vi")) vi = true;
            else if (n.endsWith(islandTail + ".ai")) ai = true;
        }
        return v && a && vi && ai;
    }

    /** Everything stored for exactly these streams at this position; fields stay null if absent. */
    static Loaded load(Context ctx, String id, Yt.Media m, int posMs) {
        Loaded l = new Loaded();
        l.videoHead = read(prefixFile(ctx, id, m.video, true));
        l.audioHead = read(prefixFile(ctx, id, m.audio, false));
        if (posMs > 0 && l.videoHead != null && l.audioHead != null) {
            long[] vf = fragmentAt(l.videoHead, posMs), af = fragmentAt(l.audioHead, posMs);
            if (vf != null && af != null) {
                l.videoIsland = read(islandFile(ctx, id, m.video, posMs, true));
                l.audioIsland = read(islandFile(ctx, id, m.audio, posMs, false));
                l.videoIslandAt = vf[0];
                l.audioIslandAt = af[0];
            }
        }
        return l;
    }

    private static byte[] read(File f) {
        if (!f.exists()) return null;
        try {
            byte[] data = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    /** Resolves and fetches whatever this video lacks on disk for this position. Blocking. */
    private static void fetch(Context ctx, String id, int posMs) {
        try {
            Yt.Media m = Yt.serving(ctx, id, false);
            File v = prefixFile(ctx, id, m.video, true), a = prefixFile(ctx, id, m.audio, false);
            if (!v.exists() || !a.exists()) {
                delete(ctx, id);                                   // heads of another format
                long videoBytes = Math.min(m.video.bytes, Math.max(VIDEO_MIN,
                        Math.min(VIDEO_MAX, (m.video.bitrate / 8L) * VIDEO_SECONDS)));
                long audioBytes = Math.min(m.audio.bytes, AUDIO_BYTES);
                write(v, Net.get(m.video.url, Net.UA_WEB, "Range", "bytes=0-" + (videoBytes - 1)));
                write(a, Net.get(m.audio.url, Net.UA_WEB, "Range", "bytes=0-" + (audioBytes - 1)));
                Dbg.v("heads: " + id + " " + (videoBytes >> 10) + "KB + " + (audioBytes >> 10)
                        + "KB");
            }
            if (posMs <= 0) return;
            File vi = islandFile(ctx, id, m.video, posMs, true);
            File ai = islandFile(ctx, id, m.audio, posMs, false);
            if (vi.exists() && ai.exists()) return;
            deleteIslands(ctx, id);                                // the position moved
            long[] vf = fragmentAt(read(v), posMs), af = fragmentAt(read(a), posMs);
            if (vf == null || af == null) {
                Dbg.v("heads: no sidx for " + id);
                return;
            }
            write(vi, fetchRange(m.video, vf));
            write(ai, fetchRange(m.audio, af));
            Dbg.v("heads: " + id + " island at " + (posMs / 1000) + "s: "
                    + ((vf[1] + Math.min(GLANCE, vf[2])) >> 10) + "KB + "
                    + ((af[1] + Math.min(GLANCE, af[2])) >> 10) + "KB");
        } catch (Exception e) {
            Dbg.v("heads " + id + ": " + e);
            if (e.getMessage() != null && e.getMessage().contains("HTTP 403")) {
                forbiddenUntil = System.currentTimeMillis() + FORBIDDEN_PAUSE_MS;
                Dbg.i("heads: pausing after media HTTP 403");
            }
        }
    }

    private static byte[] fetchRange(Yt.Stream s, long[] fragment) throws IOException {
        long end = Math.min(s.bytes, fragment[0] + fragment[1] + Math.min(GLANCE, fragment[2])) - 1;
        return Net.get(s.url, Net.UA_WEB, "Range", "bytes=" + fragment[0] + "-" + end);
    }

    /**
     * Walks the top-level boxes of a fragmented MP4 prefix to its {@code sidx} and finds the
     * fragment containing {@code timeMs}: returns {offset, size, size of the next fragment}, or
     * null if the prefix holds no usable index. The same fragment SEEK_TO_PREVIOUS_SYNC lands on.
     */
    static long[] fragmentAt(byte[] h, long timeMs) {
        if (h == null) return null;
        long p = 0;
        while (p + 8 <= h.length) {
            long size = u32(h, (int) p);
            int header = 8;
            if (size == 1) { size = u64(h, (int) p + 8); header = 16; }
            if (size == 0) size = h.length - p;
            if (size < header) return null;
            boolean sidx = h[(int) p + 4] == 's' && h[(int) p + 5] == 'i'
                    && h[(int) p + 6] == 'd' && h[(int) p + 7] == 'x';
            if (sidx) {
                int q = (int) p + header;
                if (q + 12 > h.length) return null;
                int version = h[q] & 0xff;
                q += 8;                                     // version, flags, reference_ID
                long timescale = u32(h, q); q += 4;
                long time, first;
                if (version == 0) { time = u32(h, q); first = u32(h, q + 4); q += 8; }
                else { time = u64(h, q); first = u64(h, q + 8); q += 16; }
                q += 2;                                     // reserved
                if (q + 2 > h.length) return null;
                int count = u16(h, q); q += 2;
                long offset = p + size + first;
                long target = timeMs * timescale / 1000;
                for (int i = 0; i < count && q + 12 <= h.length; i++, q += 12) {
                    long refSize = u32(h, q) & 0x7fffffffL;
                    long duration = u32(h, q + 4);
                    boolean last = i == count - 1 || q + 24 > h.length;
                    if (target < time + duration || last) {
                        long next = last ? 0 : u32(h, q + 12) & 0x7fffffffL;
                        return new long[]{offset, refSize, next};
                    }
                    offset += refSize;
                    time += duration;
                }
                return null;
            }
            p += size;
        }
        return null;
    }

    private static int u16(byte[] b, int i) { return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff); }

    private static long u32(byte[] b, int i) {
        return ((long) (b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16)
                | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    private static long u64(byte[] b, int i) { return (u32(b, i) << 32) | u32(b, i + 4); }

    private static void write(File f, byte[] data) throws IOException {
        File part = new File(f.getPath() + ".part");
        FileOutputStream out = new FileOutputStream(part);
        try { out.write(data); } finally { out.close(); }
        if (!part.renameTo(f)) throw new IOException("could not finalise " + f.getName());
    }

    /** Drops everything stored for a video: it was watched, or its format changed. */
    static void delete(Context ctx, String id) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (f.getName().startsWith(id + ".")) f.delete();
        }
    }

    private static void deleteIslands(Context ctx, String id) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            //noinspection ResultOfMethodCallIgnored
            if (n.startsWith(id + ".") && (n.endsWith(".vi") || n.endsWith(".ai"))) f.delete();
        }
    }

    /** Fetches heads for newly known videos, in the background, on Wi-Fi. */
    static void enqueue(Context ctx, List<String> ids) {
        if (ids.isEmpty() || !Sync.unmetered(ctx)) return;
        List<Vid> vids = new java.util.ArrayList<>(ids.size());
        for (String id : ids) vids.add(new Vid(id));
        run(ctx.getApplicationContext(), vids, false);
    }

    /**
     * At launch and whenever the feed comes back: every unwatched video gets what its current
     * position needs, newest first, and heads of videos no longer unwatched go.
     */
    static void backfill(Context ctx) {
        run(ctx.getApplicationContext(), null, true);
    }

    private static void run(final Context app, final List<Vid> only, final boolean sweep) {
        Yt.BACKGROUND.execute(new Runnable() {
            @Override public void run() {
                List<Vid> vids = only != null ? only : Db.get(app).unwatchedWithPos();
                if (sweep) {
                    Set<String> keep = new HashSet<>();
                    for (Vid v : vids) keep.add(v.id);
                    File[] files = dir(app).listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String n = f.getName();
                            int dot = n.indexOf('.');
                            //noinspection ResultOfMethodCallIgnored
                            if (dot < 0 || !keep.contains(n.substring(0, dot))) f.delete();
                        }
                    }
                    if (!Sync.unmetered(app)) return;
                }
                for (Vid v : vids) {
                    if (System.currentTimeMillis() < forbiddenUntil) break;
                    if (has(app, v.id, v.pos)) continue;
                    Yt.yieldToPlayback();
                    fetch(app, v.id, v.pos);
                    try { Thread.sleep(PACE_MS); } catch (InterruptedException e) { return; }
                }
            }
        });
    }
}
