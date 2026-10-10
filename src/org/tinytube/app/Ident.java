package org.tinytube.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The visitor identity every player request needs.
 *
 * <p>This is the whole of what used to be a BotGuard/WebView PO-token implementation. It turned
 * out not to be needed: the {@code VISIONOS} client -- the one yt-dlp settles on -- returns
 * stream URLs that serve the <em>entire</em> file, but only when the request carries a
 * {@code visitorData}. Without it the same request is LOGIN_REQUIRED; with it, byte ranges at 5%,
 * 50% and 95% all return 206.
 *
 * <p>So the fix for "playback dies after ~90 seconds" is this one string, not an attestation
 * token. Getting it costs a single page fetch, and it is cached for a day so pressing play stays
 * instant.
 */
final class Ident {
    private Ident() {}

    private static final String HOME = "https://www.youtube.com/";
    private static final long TTL_MS = 24 * 3600 * 1000;

    private static final Object LOCK = new Object();
    private static volatile String cached;
    private static volatile long fetchedAt;
    /** When the last few refreshes after a refusal happened; see {@link #refreshAfter403}. */
    private static final long[] refreshedAt = new long[4];
    private static final long REFRESH_WINDOW_MS = 3600 * 1000;

    /** Kicks off a fetch so the first play does not wait for one. */
    static void warmUp(final Context context) {
        if (fresh()) return;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() { get(app); }
        }, "ident").start();
    }

    private static boolean fresh() {
        return cached != null && System.currentTimeMillis() - fetchedAt < TTL_MS;
    }

    /** The visitor identity, fetching it if needed. Returns null only if YouTube is unreachable. */
    static String get(Context context) {
        if (fresh()) return cached;
        SharedPreferences prefs =
                context.getApplicationContext().getSharedPreferences("tt", Context.MODE_PRIVATE);
        synchronized (LOCK) {
            if (fresh()) return cached;
            long storedAt = prefs.getLong("visitor_at", 0);
            String stored = prefs.getString("visitor", null);
            if (stored != null && System.currentTimeMillis() - storedAt < TTL_MS) {
                cached = stored;
                fetchedAt = storedAt;
                return cached;
            }
            try {
                String html = new String(Net.get(HOME, Net.UA_WEB,
                        "Cookie", "PREF=hl=en&gl=US",
                        "Accept-Language", "en-US,en"), "UTF-8");
                String value = extract(html, "\"VISITOR_DATA\"");
                if (value == null) value = extract(html, "\"visitorData\"");
                if (value == null) throw new java.io.IOException("no visitorData on the home page");
                // The page percent-encodes the trailing '='; anything bound to the raw form is
                // rejected, so normalise it exactly as PipePipe does.
                cached = value.replace("%3D", "=").replace("%3d", "=");
                fetchedAt = System.currentTimeMillis();
                prefs.edit().putString("visitor", cached).putLong("visitor_at", fetchedAt).apply();
                Dbg.i("ident: visitorData " + cached.length() + " chars");
            } catch (Exception e) {
                Dbg.i("ident: " + e);
                if (stored != null) { cached = stored; fetchedAt = storedAt; }   // stale is better
            }
            return cached;
        }
    }

    /**
     * A media-server 403 can be bound to this visitor even while the URL is fresh: since
     * 2026-09-29 a visitor identity can turn into one whose URLs serve the first minute of a
     * video and nothing after it, and a new anonymous identity is what fixes it. Replace it,
     * sharing the new one with everything, then drop every stream resolved under the old one
     * and let downloads deferred for the refusal start again now rather than in a day. At most
     * four times an hour, so a refusal with another cause cannot churn identities. Never log
     * either identity.
     */
    static boolean refreshAfter403(Context context) {
        boolean changed;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            int oldest = 0;
            for (int i = 1; i < refreshedAt.length; i++) {
                if (refreshedAt[i] < refreshedAt[oldest]) oldest = i;
            }
            if (now - refreshedAt[oldest] < REFRESH_WINDOW_MS) return false;
            refreshedAt[oldest] = now;
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences("tt", Context.MODE_PRIVATE);
            String before = cached != null ? cached : prefs.getString("visitor", null);
            cached = null;
            fetchedAt = 0;
            prefs.edit().putLong("visitor_at", 0).apply();
            String after = get(context);
            changed = after != null && !after.equals(before);
            Dbg.i("ident: refreshed after media 403, changed=" + changed);
        }
        if (changed) {
            Yt.forgetAll();
            if (Db.get(context).releaseDeferred() > 0) Cacher.resumeInterrupted(context);
        }
        return changed;
    }

    /** Pulls {@code "key":"value"} out of the page without parsing the surrounding script. */
    private static String extract(String html, String key) {
        int at = html.indexOf(key);
        while (at >= 0) {
            int colon = html.indexOf(':', at + key.length());
            int quote = colon < 0 ? -1 : html.indexOf('"', colon);
            if (quote > 0 && html.substring(colon + 1, quote).trim().isEmpty()) {
                int end = html.indexOf('"', quote + 1);
                if (end > quote + 1) return html.substring(quote + 1, end);
            }
            at = html.indexOf(key, at + key.length());
        }
        return null;
    }
}
