package org.tinytube.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * SponsorBlock. Every category is skipped silently -- no buttons, no toasts -- matching the
 * same categories as PipePipe, where all eight skippable categories are enabled and set
 * to "automatic".
 *
 * <p>Segments are fetched through the privacy-preserving hash prefix endpoint (the server only
 * ever sees the first four characters of the video id's SHA-256) and cached in the database, so a
 * downloaded video still skips correctly with no network.
 */
final class Sponsor {
    private Sponsor() {}

    /**
     * The categories set to "automatic" in PipePipe. {@code poi_highlight} is deliberately absent:
     * it marks a jump target, not a segment to remove.
     */
    private static final String CATEGORIES =
            "[\"sponsor\",\"intro\",\"outro\",\"selfpromo\",\"interaction\","
                    + "\"preview\",\"music_offtopic\",\"filler\"]";

    private static final String API = "https://sponsor.ajay.app/api/skipSegments/";

    /** A segment in seconds. */
    static final class Seg {
        final float start, end;
        Seg(float start, float end) { this.start = start; this.end = end; }
    }

    /** Cached segments, or null when this video has never been looked up. */
    static List<Seg> cached(Context ctx, String videoId) {
        return parse(Db.get(ctx).segs(videoId));
    }

    /**
     * Returns cached segments if present, otherwise fetches and caches them. Safe to call from a
     * background thread only.
     */
    static List<Seg> fetchAndStore(Context ctx, String videoId) {
        String stored = Db.get(ctx).segs(videoId);
        if (stored != null) return parse(stored);
        String serialised;
        try {
            serialised = fetch(videoId);
        } catch (Exception e) {
            return new ArrayList<>();       // not cached, so a later attempt can retry
        }
        Db.get(ctx).putSegs(videoId, serialised);
        return parse(serialised);
    }

    /**
     * Re-fetches segments in the background and overwrites the cache, then reports the fresh list
     * on the given callback if -- and only if -- it actually differs from what was cached.
     *
     * <p>Called when playback starts and the segments are due ({@link Db#segsDue}), including for
     * a downloaded video: SponsorBlock submissions keep arriving after a video is cached, and a
     * month-old download would otherwise skip using month-old data forever. Failure is silent; the
     * cached segments stay in use.
     */
    static void refreshAsync(final Context ctx, final String videoId, final Updated cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String fresh;
                try {
                    fresh = fetch(videoId);
                } catch (Exception e) {
                    return;                      // offline or API down: keep what we have
                }
                String stored = Db.get(ctx).segs(videoId);
                if (fresh.equals(stored)) return;
                Db.get(ctx).putSegs(videoId, fresh);
                if (cb != null) cb.onSegments(parse(fresh));
            }
        }, "sb-refresh").start();
    }

    /** Reports updated segments; called on the fetching thread. */
    interface Updated { void onSegments(List<Seg> segs); }

    /**
     * How fresh segments are kept (the maintainer, 2026-09-13): submissions pour in while a video is new, so in
     * its first {@link #FIRST_DAY_MS} they are refreshed at most every {@link #YOUNG_EVERY_MS}, and
     * after that at most every {@link #OLD_EVERY_MS}. That caps every refresh, the one at play too.
     */
    static final long FIRST_DAY_MS = 24 * 3600 * 1000L, YOUNG_EVERY_MS = 3600 * 1000L,
            OLD_EVERY_MS = 24 * 3600 * 1000L;

    /**
     * SQL condition over {@code v} (vid, may be absent) and {@code sb}: the segments are due for a
     * (re)fetch. Numbers are inlined, not bound -- a bound argument is TEXT (see the SQLite affinity rule).
     */
    static String dueSql(long now) {
        long young = now - FIRST_DAY_MS;
        return "(sb.at IS NULL"
                + " OR (IFNULL(v.pub,0) > " + young + " AND sb.at < " + (now - YOUNG_EVERY_MS) + ")"
                + " OR (IFNULL(v.pub,0) <= " + young + " AND sb.at < " + (now - OLD_EVERY_MS) + "))";
    }
    /** Videos refreshed per sync tick. */
    private static final int REFRESH_PER_TICK = 5;

    /** Opens a connection to the SponsorBlock host early, so the first refresh does not pay for it. */
    static void warmUp() {
        Yt.BACKGROUND.execute(new Runnable() {
            @Override public void run() {
                try { Net.get(API + "0000", "TinyTube/1.0"); } catch (IOException ignored) { }
            }
        });
    }

    /**
     * Keeps every unwatched video's segments fresh: the stalest few are re-fetched on each sync
     * tick, and a video not looked up yet counts as stalest of all. Blocking; the sync thread.
     * Returns how many were refreshed.
     */
    static int refreshStale(Context ctx) {
        List<String> ids = Db.get(ctx).staleSegmentIds(REFRESH_PER_TICK, System.currentTimeMillis());
        int done = 0;
        for (String id : ids) {
            try {
                Db.get(ctx).putSegs(id, fetch(id));
                done++;
            } catch (Exception e) {
                Dbg.v("sponsorblock refresh " + id + ": " + e);
                break;                        // offline or the API is down: try next tick
            }
        }
        if (done > 0) Dbg.v("sponsorblock: refreshed " + done + " video(s)");
        return done;
    }

    private static String fetch(String videoId) throws IOException {
        String prefix = sha256Prefix(videoId);
        String url = API + prefix + "?categories="
                + java.net.URLEncoder.encode(CATEGORIES, "UTF-8");
        String body = Net.getText(url, "TinyTube/1.0");
        StringBuilder out = new StringBuilder();
        try {
            JSONArray videos = new JSONArray(body);
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.optJSONObject(i);
                if (v == null || !videoId.equals(v.optString("videoID"))) continue;
                JSONArray segments = v.optJSONArray("segments");
                if (segments == null) continue;
                for (int j = 0; j < segments.length(); j++) {
                    JSONObject s = segments.optJSONObject(j);
                    JSONArray pair = s == null ? null : s.optJSONArray("segment");
                    if (pair == null || pair.length() < 2) continue;
                    if ("full".equals(s.optString("actionType"))) continue;  // whole-video label
                    if (out.length() > 0) out.append(',');
                    out.append((float) pair.optDouble(0)).append(':')
                            .append((float) pair.optDouble(1));
                }
            }
        } catch (Exception e) {
            throw new IOException("bad SponsorBlock response", e);
        }
        return out.toString();
    }

    private static List<Seg> parse(String serialised) {
        if (serialised == null) return null;
        List<Seg> segs = new ArrayList<>();
        if (serialised.isEmpty()) return segs;
        for (String part : serialised.split(",")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            try {
                segs.add(new Seg(Float.parseFloat(part.substring(0, colon)),
                        Float.parseFloat(part.substring(colon + 1))));
            } catch (NumberFormatException ignored) { }
        }
        java.util.Collections.sort(segs, new java.util.Comparator<Seg>() {
            @Override public int compare(Seg a, Seg b) { return Float.compare(a.start, b.start); }
        });
        return segs;
    }

    private static String sha256Prefix(String videoId) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(videoId.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 2; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("no SHA-256", e);
        }
    }
}
