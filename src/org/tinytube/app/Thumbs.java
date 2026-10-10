package org.tinytube.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.PowerManager;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thumbnail cache: an in-memory {@link LruCache} in front of a permanent on-disk copy under
 * {@code filesDir/thumbs}. Every feed video's thumbnail is fetched and kept, so the list renders
 * instantly and completely offline.
 *
 * <p>Files live in {@code filesDir} rather than {@code cacheDir} on purpose -- the system is free
 * to wipe the latter, and a cached-for-offline video with no thumbnail looks broken.
 */
final class Thumbs {

    private static final Executor IO = new ThreadPoolExecutor(
            0, 4, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    private static LruCache<String, Bitmap> mem;
    private static File dir;
    private static Context app;
    private static final AtomicBoolean BACKFILL = new AtomicBoolean();
    private static final AtomicBoolean REPORTED_WRITE = new AtomicBoolean();

    static synchronized void init(Context ctx) {
        if (mem != null) return;
        app = ctx.getApplicationContext();
        int budget = (int) (Runtime.getRuntime().maxMemory() / 8 / 1024);   // KB
        mem = new LruCache<String, Bitmap>(budget) {
            @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount() / 1024; }
        };
        dir = new File(ctx.getFilesDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Counts how the first screenful is served, to tell a slow disk from a slow network. */
    static volatile int dbgFromMemory, dbgFromDisk, dbgFromNetwork;

    /** Binds a thumbnail to a recycled view; the tag guards against a late arrival. */
    static void into(final ImageView view, final String videoId) {
        into(view, videoId, false);
    }

    static void into(final ImageView view, final String videoId, final boolean large) {
        final String key = videoId + (large ? ".large" : "");
        Bitmap hit = mem.get(key);
        view.setTag(key);
        if (hit != null) {
            dbgFromMemory++;
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        IO.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = load(videoId, large);
                final Bitmap shown = b != null ? b : large ? load(videoId, false) : null;
                if (shown == null) return;
                view.post(new Runnable() {
                    @Override public void run() {
                        if (key.equals(view.getTag())) view.setImageBitmap(shown);
                    }
                });
            }
        });
    }

    /** Fetches into the disk cache without touching any view. Used to pre-warm a refresh. */
    static void prefetch(String videoId) {
        if (new File(dir, videoId + ".jpg").exists()) return;
        load(videoId, false);
    }

    /** Large tiles must be ready offline before they have ever been shown. */
    static void backfill(Context ctx) {
        final Context context = ctx.getApplicationContext();
        init(context);
        if (!canPrefetch(context) || !BACKFILL.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    long until = System.currentTimeMillis() + 60000;
                    // Downloads first, including watched ones retained for offline viewing.
                    java.util.List<Vid> videos = Db.get(context).feed(Db.VIEW_OFFLINE);
                    videos.addAll(Db.get(context).feed(Db.VIEW_UNWATCHED));
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    int fetched = 0;
                    for (Vid v : videos) {
                        if (!canPrefetch(context) || System.currentTimeMillis() >= until) break;
                        if (!seen.add(v.id)) continue;
                        prefetch(v.id);
                        if (new File(dir, v.id + ".large.jpg").exists()) continue;
                        if (fetched++ == 20) break;
                        load(v.id, true); // an unavailable image must not block later videos
                    }
                } finally {
                    BACKFILL.set(false);
                }
            }
        }, "thumb-backfill").start();
    }

    private static boolean canPrefetch(Context ctx) {
        return !Yt.playing && Sync.online(ctx)
                && ctx.getSystemService(PowerManager.class).isInteractive();
    }

    private static Bitmap load(String videoId, boolean large) {
        String key = videoId + (large ? ".large" : "");
        Bitmap b = mem.get(key);
        if (b != null) return b;
        File f = new File(dir, key + ".jpg");
        if (f.exists()) {
            b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) { dbgFromDisk++; mem.put(key, b); return b; }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        try {
            byte[] bytes = null;
            if (large) {
                // Some videos have no maxres image: YouTube answers with a tiny placeholder.
                // Keep a separate disk copy so older 320x180 row thumbnails do not mask tiles.
                for (String quality : new String[] { "maxresdefault", "sddefault", "hqdefault" }) {
                    try {
                        byte[] candidate = Net.get(Yt.thumbUrl(videoId, quality), Net.UA_WEB);
                        Bitmap test = BitmapFactory.decodeByteArray(candidate, 0, candidate.length);
                        if (test != null && test.getWidth() >= 480) {
                            bytes = candidate;
                            b = test;
                            break;
                        }
                    } catch (IOException ignored) { }
                }
                if (bytes == null) return null;
            } else {
                bytes = Net.get(Yt.thumbUrl(videoId), Net.UA_WEB);
            }
            if (b == null) b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (b == null) return null;
            dbgFromNetwork++;
            mem.put(key, b);
            // A process killed during a write must not leave a partial JPEG as a cache hit.
            // Separate temporary files also keep a tile load and backfill from truncating each other.
            File tmp = null;
            try {
                tmp = File.createTempFile(key, ".tmp", dir);
                FileOutputStream out = new FileOutputStream(tmp);
                try { out.write(bytes); } finally { out.close(); }
                if (!tmp.renameTo(f)) throw new IOException("thumbnail commit failed");
            } catch (IOException e) {
                if (REPORTED_WRITE.compareAndSet(false, true))
                    Crash.reportDownload(app, "Thumbnail cache write failed", e);
            } finally {
                if (tmp != null) tmp.delete();
            }
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    /** Deletes thumbnails for videos that are no longer in the feed. */
    static void sweep(java.util.Set<String> keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".jpg")) continue;
            String id = name.substring(0, name.length() - 4);
            if (id.endsWith(".large")) id = id.substring(0, id.length() - 6);
            if (!keep.contains(id)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
