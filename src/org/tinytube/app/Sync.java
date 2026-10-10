package org.tinytube.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Feed refresh, done opportunistically and invisibly.
 *
 * <p>There is no pull-to-refresh and no progress indicator anywhere. Instead a couple of channels
 * are polled whenever the app happens to be running -- browsing the feed, or watching a video --
 * which is exactly when the phone is already awake and online, so the work costs no extra battery.
 * When the app is not on screen, {@link Screen} runs a silent pass ({@link #background}) every few
 * minutes while the phone's screen is on, and none while it is off. The device is never woken to
 * refresh the feed, and no channel is polled more than once an hour.
 *
 * <p>Two tiers, for cost. Each channel's RSS feed is ~6 KB on the wire and carries an
 * <em>exact</em> publish timestamp -- the thing needed to interleave 55 channels correctly, and
 * the thing InnerTube's "4 days ago" cannot give. A browse call (~25 KB) follows only when that
 * channel turned up a video we have not stored, to fill in duration and view count. A video
 * present in RSS but absent from the Videos tab is a Short, a livestream or a premiere, and is
 * dropped -- that is the Shorts filter.
 */
final class Sync {
    private Sync() {}

    /** Channels polled per tick. Small: this runs while the user is doing something else. */
    private static final int BATCH = 2;
    /**
     * A channel is polled at most this often, and a failed attempt counts too (the maintainer: never more than
     * once per hour).
     */
    private static final long STALE_AFTER_MS = 60 * 60 * 1000;
    /** A background pass starts no new channel after this long. */
    private static final long SWEEP_BUDGET_MS = 60 * 1000;
    /** Shortest gap between ticks, so a busy foreground does not hammer the network. */
    private static final long MIN_TICK_GAP_MS = 15 * 1000;
    /**
     * How long a channel may go without a successful poll before the feed says so. Four days:
     * long enough that a channel simply having nothing new, or a bad afternoon of fetches, never
     * shows a warning, short enough that a channel YouTube has broken is noticed while it still
     * matters.
     */
    static final long STALE_WARN_MS = 4L * 24 * 3600 * 1000;
    /** Nothing older than this ever enters the feed, whichever source it came from. */
    private static final long MAX_AGE_MS = 14L * 24 * 3600 * 1000;

    /** Channels a background sweep polls at once: RSS is ~6 KB, so what limits it is the wake. */
    private static final int SWEEP_THREADS = 4;
    /** A foreground tick or a background sweep holds the sync; never both. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** A sweep's workers start no channel past this; {@link #stopSweep} pulls it in. */
    private static volatile long sweepUntil;
    /**
     * Routine conditions -- a channel that 404s on RSS, browse entries older than the floor -- are
     * logged once per channel per process, not on every poll.
     */
    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<String>());
    /**
     * When each channel was last attempted in this process. `chan.last` only moves on success, so
     * without this a failing channel would be retried on every tick.
     */
    private static final Map<String, Long> ATTEMPTED = new ConcurrentHashMap<>();
    private static volatile long lastTickAt;
    /** The channel table has been reconciled with {@link Chans} in this process. */
    private static boolean seeded;
    /**
     * Bumped whenever a poll stores new rows, so a feed on screen can tell that it is stale and
     * re-query without polling the database on every tick.
     */
    static volatile int feedVersion;

    /**
     * Polls up to {@link #BATCH} stale channels in the background. Cheap to call often -- it
     * returns immediately when it is already running, ran recently, or there is no network.
     */
    static void tick(final Context context) {
        if (RUNNING.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastTickAt < MIN_TICK_GAP_MS) return;
        if (!online(context)) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        lastTickAt = now;
        final Context app = context.getApplicationContext();
        Thumbs.init(app);   // a process started straight into the player has not done this
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    poll(app);
                    Thumbs.backfill(app);
                } catch (Exception e) {
                    Dbg.v("sync: " + e);
                } finally {
                    RUNNING.set(false);
                }
            }
        }, "sync").start();
    }

    /**
     * The screen-on pass ({@link Screen}), off the calling thread and on Wi-Fi only: the channels
     * that are due and the SponsorBlock segments that are due ({@link #sweep}), then the heads of
     * unwatched videos that lack them ({@link Heads#backfill}), then the auto-cache downloads a
     * new video implies ({@link Cacher#reconcileAuto}), plus anything left mid-download
     * ({@link Cacher#resumeInterrupted}) -- which is why an auto-cached channel's downloads no
     * longer wait for the feed to be opened. Silent; returns at once.
     */
    static void background(Context context) {
        final Context app = context.getApplicationContext();
        if (RUNNING.get() || !online(app)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    sweep(app, SWEEP_BUDGET_MS);
                } catch (Exception e) {
                    Dbg.i("sync: background pass failed: " + e);
                }
                Heads.backfill(app);
                Thumbs.backfill(app);
                Cacher.sweepOrphans(app);
                Cacher.resumeInterrupted(app);
                Cacher.reconcileAuto(app);
            }
        }, "background").start();
    }

    /** Up to {@code max} channels due: neither polled successfully nor attempted within the hour. */
    private static List<String> due(Db db, int max) {
        long before = System.currentTimeMillis() - STALE_AFTER_MS;
        List<String> out = new ArrayList<>();
        for (String id : db.stalestChannels(max + ATTEMPTED.size(), before)) {
            Long at = ATTEMPTED.get(id);
            if (at != null && at > before) continue;
            out.add(id);
            if (out.size() == max) break;
        }
        return out;
    }

    /**
     * Every channel that is due, {@link #SWEEP_THREADS} at a time, then the SponsorBlock segments
     * that are due, starting nothing new after {@code budgetMs}. Blocking. Returns false, doing
     * nothing, when a foreground tick holds the sync.
     */
    static boolean sweep(final Context context, long budgetMs) throws InterruptedException {
        if (!RUNNING.compareAndSet(false, true)) return false;
        try {
            final long t0 = System.currentTimeMillis();
            sweepUntil = t0 + budgetMs;
            final Context app = context.getApplicationContext();
            Thumbs.init(app);
            final Db db = Db.get(app);
            if (!seeded) {
                db.seedChannels();
                seeded = true;
            }
            List<String> stale = due(db, 1000);
            final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>(stale);
            final AtomicInteger polled = new AtomicInteger(), added = new AtomicInteger(),
                    failed = new AtomicInteger();
            Thread[] workers = new Thread[Math.min(SWEEP_THREADS, stale.size())];
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(new Runnable() {
                    @Override public void run() {
                        String channelId;
                        while (System.currentTimeMillis() < sweepUntil
                                && (channelId = queue.poll()) != null) {
                            ATTEMPTED.put(channelId, System.currentTimeMillis());
                            try {
                                added.addAndGet(pollOne(app, db, channelId));
                                db.markChannelPolled(channelId);
                                polled.incrementAndGet();
                            } catch (Exception e) {
                                Dbg.v("sync " + channelId + ": " + e);
                                db.markChannelFailed(channelId);
                                failed.incrementAndGet();
                            }
                        }
                    }
                }, "sweep-" + i);
                workers[i].start();
            }
            for (Thread w : workers) w.join();
            int segments = 0;
            while (segments < 50 && System.currentTimeMillis() < sweepUntil) {
                int n = Sponsor.refreshStale(app);
                if (n == 0) break;
                segments += n;
            }
            if (stale.size() + segments > 0) {      // a pass with nothing due says nothing
                Dbg.i("sync: background, " + polled + "/" + stale.size() + " channels"
                        + (failed.get() > 0 ? " (" + failed + " failed)" : "") + ", +" + added
                        + " videos, " + segments + " segment refreshes, "
                        + (System.currentTimeMillis() - t0) + " ms");
            }
            return true;
        } finally {
            RUNNING.set(false);
        }
    }

    static void stopSweep() {
        sweepUntil = 0;
    }

    /**
     * Adds a channel from a search result and polls it straight away, so its videos are in the
     * feed by the time the user is back there.
     */
    static void subscribe(Context ctx, final String chanId, final String name) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Db.get(app).subscribe(chanId, name);
                Dbg.i("subscribed to " + name);
                lastTickAt = 0;
                tick(app);
            }
        }, "subscribe").start();
    }

    /**
     * Drops a channel: its videos leave the feed, their downloads and heads go, and it is not
     * polled again. Off the main thread; the feed re-queries on its next tick.
     */
    static void unsubscribe(Context ctx, final String chanId) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Db db = Db.get(app);
                db.setAuto(chanId, false);
                for (String id : db.videoIdsOf(chanId)) {
                    Cacher.uncache(app, id);
                    Heads.delete(app, id);
                    Yt.forget(id);
                }
                db.unsubscribe(chanId);
                feedVersion++;
                Dbg.i("unsubscribed " + db.chanName(chanId));
            }
        }, "unsubscribe").start();
    }

    /** Wi-Fi is the only network this app treats as being online: never mobile data. */
    static boolean online(Context context) {
        return wifi(context);
    }

    /** Wi-Fi, and not a metered Wi-Fi (a phone hotspot): where speculative megabytes cost nothing. */
    static boolean unmetered(Context context) {
        return wifi(context) && has(context, NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private static boolean wifi(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static boolean has(Context context, int capability) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(capability);
    }

    private static void poll(Context ctx) {
        Db db = Db.get(ctx);
        if (!seeded) {
            db.seedChannels();      // the list is compiled in, so once per process is enough
            seeded = true;
        }
        List<String> channels = due(db, BATCH);
        if (channels.isEmpty()) { Sponsor.refreshStale(ctx); return; }

        for (String channelId : channels) {
            ATTEMPTED.put(channelId, System.currentTimeMillis());
            try {
                pollOne(ctx, db, channelId);
            } catch (Exception e) {
                Dbg.v("sync " + channelId + ": " + e);
                db.markChannelFailed(channelId);
                continue;           // leave it stale so the next tick retries it
            }
            db.markChannelPolled(channelId);
        }
        Sponsor.refreshStale(ctx);
    }

    /**
     * Drops everything that is not news: a video published more than {@link #MAX_AGE_MS} ago,
     * or one whose date we could not read at all.
     *
     * <p>The feed is a to-watch list that nothing leaves except by being marked watched, so a
     * single bad poll is permanent clutter. Browse -- the fallback for the channels that 404 on
     * RSS -- returns the whole Videos tab, 30 entries with the oldest of them years back, and its
     * "5 years ago" dates cannot on their own tell an upload from an archive entry.
     *
     * <p>This floor used to be relative: newer than the channel's newest stored video, less two
     * weeks. That fails on exactly the channels it has to hold, the slow ones. When the newest
     * video stored for a channel is itself a year old, the floor sits a year back and the whole
     * archive walks in -- which is how CGP Grey's 2025 uploads landed in the feed on 2026-09-08
     * with the relative guard in place. The floor is now wall-clock: two weeks, from now.
     *
     * <p>Browse's relative dates round the age <em>down</em> ("1 week ago" for a 13-day-old
     * video), so a coarse date can only ever make a video look newer than it is. Nothing recent
     * is lost to this, and nothing old survives it.
     */
    private static List<Vid> recentOnly(List<Vid> entries) {
        long floor = System.currentTimeMillis() - MAX_AGE_MS;
        List<Vid> recent = new ArrayList<>();
        for (Vid v : entries) {
            if (v.pub > floor) recent.add(v);
        }
        return recent;
    }

    /** Polls one channel; returns how many videos it added to the feed. */
    private static int pollOne(Context ctx, Db db, String channelId) throws Exception {
        // RSS first: it is the only source of an exact publish time, which is what interleaves 55
        // channels correctly. Some channels 404 there even though they are perfectly alive, so a
        // failure falls through to browse rather than dropping the channel from the feed.
        List<Vid> entries;
        boolean exactDates = true;
        try {
            entries = Yt.rss(channelId);
        } catch (Exception e) {
            entries = null;
            exactDates = false;
            if (REPORTED.add("rss " + channelId)) {
                Dbg.v("sync: no RSS for " + db.chanName(channelId) + " (" + e + "), using browse");
            }
        }
        if (entries != null && entries.isEmpty()) {
            exactDates = false;
            if (REPORTED.add("rss " + channelId)) {
                Dbg.v("sync: empty RSS for " + db.chanName(channelId) + ", using browse");
            }
        }

        Map<String, Vid> meta = null;
        if (!exactDates) {
            meta = Yt.browseVideos(channelId);
            entries = new ArrayList<>(meta.values());
        }
        int seen = entries.size();
        entries = recentOnly(entries);
        // RSS always carries the channel's 15 newest, so old entries there are routine; only
        // browse, the path that once backfilled years of videos, is worth a line.
        if (!exactDates && entries.size() < seen && REPORTED.add("old " + channelId)) {
            Dbg.v("sync: dropped " + (seen - entries.size()) + " video(s) older than two weeks from "
                    + db.chanName(channelId) + (exactDates ? "" : " (browse)"));
        }

        List<Vid> fresh = new ArrayList<>();
        for (Vid v : entries) {
            if (v.id != null && !db.isKnown(v.id)) fresh.add(v);
        }
        if (fresh.isEmpty()) return 0;

        if (meta == null) meta = Yt.browseVideos(channelId);
        List<Vid> keep = new ArrayList<>(fresh.size());
        for (Vid v : fresh) {
            Vid m = meta.get(v.id);
            if (m == null) continue;            // Short, live or upcoming: not wanted
            v.dur = m.dur;
            v.views = m.views;
            if (v.pub <= 0) v.pub = m.pub;      // browse-only channels: approximate date
            if (v.chan == null || v.chan.isEmpty()) v.chan = db.chanName(v.chanId);
            keep.add(v);
        }
        if (keep.isEmpty()) return 0;
        db.putVideos(keep);
        feedVersion++;
        Dbg.v("sync: +" + keep.size() + " from " + db.chanName(channelId)
                + (exactDates ? "" : " (approximate dates)"));
        List<String> ids = new ArrayList<>(keep.size());
        for (Vid v : keep) {
            Thumbs.prefetch(v.id);
            ids.add(v.id);
        }
        // Everything a new video needs to start instantly and skip correctly, as soon as it is
        // known: its first seconds on disk, its SponsorBlock segments in the database.
        Heads.enqueue(ctx, ids);
        for (String id : ids) Sponsor.fetchAndStore(ctx, id);
        return keep.size();
    }
}
