package org.tinytube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.ForegroundServiceStartNotAllowedException;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Downloads a video for offline playback: the 1080p video track, the audio track and the
 * SponsorBlock segments, so a cached video behaves exactly like a streamed one with no network.
 *
 * <p>Files land in {@code filesDir/media/&lt;id&gt;.v} and {@code .a}. Nothing describes them on
 * the side -- MediaExtractor reads the container itself, so the player uses one code path for
 * files and for googlevideo URLs.
 */
public final class Cacher extends Service {

    private static final String ACTION_CACHE = "cache";
    private static final String ACTION_CANCEL = "cancel";
    private static final String CHANNEL = "downloads";
    private static final int NOTE_ID = 1;

    /** Quick retries before parking the download for {@link #DEFER_MS}. */
    private static final int MAX_ATTEMPTS = 3;
    /**
     * Waits between attempts. Long on purpose: a download that failed is usually waiting on the
     * network coming back or YouTube calming down, and hammering it helps neither.
     */
    private static final long[] BACKOFF_MS = {10_000, 30_000};
    /** How long a failed round parks a download: every 12 h, never longer (the maintainer, 2026-10-12). */
    private static final long DEFER_MS = 12 * 3_600_000L;
    /** Failed rounds after which a download has been failing for three days, and is reported. */
    private static final int REPORT_ROUNDS = 7;
    /**
     * A download YouTube is refusing waits only this long: what fixes a refusal is a new visitor
     * identity, of which {@link Ident#refreshAfter403} allows four an hour, and not days.
     */
    private static final long FORBIDDEN_DEFER_MS = 3_600_000L;

    /**
     * How long a download may move no bytes at all before it is stopped and retried. A stall is
     * not a failure: the socket is alive, nothing throws, and the download simply sits there --
     * holding the CPU and Wi-Fi locks, since {@link #transferring} thinks bytes are moving.
     * Nothing shorter than this can tell a stall from ordinary slowness: a socket read times out
     * only after 25 s, and {@link Source}'s own 403 back-off can legitimately spend a minute
     * opening a range without delivering a byte.
     */
    private static final long STALL_MS = 180_000;
    /** How often {@link #watch} looks. */
    private static final long WATCH_MS = 30_000;
    /**
     * Bytes an attempt has to have written for it not to count against {@link #MAX_ATTEMPTS}.
     * A download that keeps stalling but keeps advancing is an interrupted download, not a
     * failing one; without this a long video on a flaky link would be given up on and deleted
     * after ten stalls with almost all of it already on disk.
     */
    private static final long PROGRESS_MIN = 1L << 20;

    /**
     * A stream being written right now, so {@link #watch} can see whether it is still moving.
     * The download loop is blocked inside {@code Source.readAt} for the whole of a stall, which
     * is why this has to be looked at from another thread.
     */
    private static final class Job {
        final String id;
        final Source source;
        /** Bytes written for the whole video so far, kept up to date by {@link #fetch}. */
        volatile long bytes;
        /** Set when the watchdog cut this one short, so the failure is logged for what it is. */
        volatile boolean stalled;
        /** The watchdog's own last sample, and when it took it. Touched only by the watchdog. */
        long sampled;
        long sampledAt;

        Job(String id, Source source, long bytes, long now) {
            this.id = id;
            this.source = source;
            this.bytes = bytes;
            this.sampled = bytes;
            this.sampledAt = now;
        }
    }

    /** Downloads with a stream open, guarded by itself. */
    private final List<Job> running = new ArrayList<>();
    private Thread watchdog;

    private static final Set<String> CANCELLED =
            Collections.synchronizedSet(new HashSet<String>());
    /**
     * Jobs owned by this service instance. This must not be static: {@link Screen} keeps the
     * process alive when Android stops and later recreates this service at the dataSync time
     * limit. A queued job removed by shutdownNow never runs its finally block, so a static set
     * retained a phantom "active" id forever and every recreated service refused to resume it.
     */
    private final Set<String> active =
            Collections.synchronizedSet(new HashSet<String>());

    /** The service instance running now, if any: what tells {@link #resumeInterrupted} to stay out. */
    private static volatile Cacher live;

    static boolean syncBusy() { return live != null; }

    private ExecutorService pool;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    /** Downloads transferring right now, as opposed to sleeping out a back-off. */
    private int transferring;
    /** Most recent start, so an old worker cannot stop a service that has since been started again. */
    private volatile int latestStartId;
    /** Set before shutdown interrupts workers. */
    private volatile boolean stopping;

    /**
     * Bumped on every change to a video's download state, with a hook the feed holds while it is
     * on screen.
     *
     * <p>The feed's cache button only ever followed the state because the tap handler set it on
     * the row it had just been pressed on. A download nobody tapped -- an auto-cached one --
     * therefore stayed the idle grey for its whole transfer, and finishing never flipped the
     * icon either.
     */
    static volatile int cacheVersion;
    /** Run whenever {@link #cacheVersion} moves; from whatever thread made the change. */
    static volatile Runnable onCacheChange;

    /** Records a video's download state and tells whoever is watching. */
    private static void mark(Context ctx, String videoId, int state) {
        if (state != Db.CACHE_BUSY) Db.get(ctx).clearCacheRetry(videoId);
        Db.get(ctx).setCache(videoId, state);
        cacheVersion++;
        Runnable r = onCacheChange;
        if (r != null) r.run();
    }

    // ---- public entry points -----------------------------------------------

    static void cache(Context ctx, String videoId) { cache(ctx, videoId, false); }

    /** @param auto started by auto-caching: a download refused for space says nothing */
    private static void cache(Context ctx, String videoId, boolean auto) {
        if (DataSync.isReceiver()) return;
        Db.get(ctx).clearCacheRetry(videoId);
        mark(ctx, videoId, Db.CACHE_BUSY);
        try {
            ctx.startForegroundService(new Intent(ctx, Cacher.class)
                    .setAction(ACTION_CACHE).putExtra("id", videoId).putExtra("auto", auto));
        } catch (RuntimeException e) {
            if (e instanceof ForegroundServiceStartNotAllowedException) {
                // Android's background/time quota is temporary. Keep BUSY and the partials
                // for resumeInterrupted; refusing a start is not a failed download round.
                Dbg.i("cache: foreground start deferred by Android");
                return;
            }
            // Nothing will pick this up, so it must not stay BUSY: the reconcile only ever
            // starts a video that is CACHE_NONE, so a stuck row would never be retried.
            mark(ctx, videoId, Db.CACHE_NONE);
            throw e;
        }
    }

    /**
     * Restarts anything left mid-download. START_STICKY covers the system killing the service,
     * but not a force-stop, which blocks the restart -- so the app also asks on launch and every
     * screen-on pass does ({@link Sync#background}). The check is a database query, so it runs
     * off the caller's thread. Downloads the live service already has in hand are left alone:
     * restarting for those every pass would log a "resuming" line every five minutes. What is
     * left over after taking them out is a row marked BUSY with
     * nothing working on it -- a download stopped by something that never got as far as putting
     * the row back -- and that is exactly what has to be started again.
     */
    static void resumeInterrupted(Context ctx) {
        if (DataSync.isReceiver()) return;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                List<String> busy = Db.get(app).dueDownloads();
                Cacher service = live;
                if (service != null) busy.removeAll(service.active);
                if (busy.isEmpty()) return;
                try {
                    app.startForegroundService(new Intent(app, Cacher.class));
                } catch (IllegalStateException e) {
                    Dbg.i("cache: cannot resume now: " + e);   // app went to the background
                }
            }
        }, "resume-check").start();
    }

    /**
     * Deletes media files no database row claims. A download interrupted by the process dying
     * leaves its {@code .part} behind, and when that row later goes back to CACHE_NONE -- the
     * service gave up, or the user marked the video watched while nothing was running -- nothing
     * ever removes the file. 74 MB of one was on the phone. Runs off the caller's thread.
     */
    static void sweepOrphans(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                File[] files = dir(app).listFiles();
                if (files == null || files.length == 0) return;
                Set<String> keep = new HashSet<>(Db.get(app).cachedIds());
                for (File f : files) {
                    String n = f.getName();
                    int dot = n.indexOf('.');
                    if (dot < 0 || keep.contains(n.substring(0, dot))) continue;
                    Dbg.i("cache: dropping orphan " + n + " (" + (f.length() >> 20) + " MB)");
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }, "cache-sweep").start();
    }

    static void cancel(Context ctx, String videoId) {
        CANCELLED.add(videoId);
        ctx.startService(new Intent(ctx, Cacher.class)
                .setAction(ACTION_CANCEL).putExtra("id", videoId));
        mark(ctx, videoId, restState(ctx, videoId));
    }

    static void uncache(Context ctx, String videoId) {
        remove(ctx, videoId, restState(ctx, videoId));
    }

    /**
     * Drops a download because its video was marked watched. The state goes to NONE, not SKIP:
     * a video that is un-watched again is fair game for auto-caching.
     */
    static void discard(Context ctx, String videoId) {
        remove(ctx, videoId, Db.CACHE_NONE);
    }

    /** Stops a download in flight, deletes whatever it wrote, and records {@code state}. */
    private static void remove(Context ctx, String videoId, int state) {
        CANCELLED.add(videoId);
        delete(ctx, videoId);
        mark(ctx, videoId, state);
    }

    /** What a download the user just cancelled or removed becomes: skipped, if auto-caching would otherwise redo it. */
    private static int restState(Context ctx, String videoId) {
        Vid v = Db.get(ctx).video(videoId);
        return v != null && Db.get(ctx).isAuto(v.chanId) ? Db.CACHE_SKIP : Db.CACHE_NONE;
    }

    // ---- auto-caching ---------------------------------------------------------

    /** How many of a channel's oldest unwatched videos auto-caching keeps downloaded. */
    private static final int AUTO_KEEP = 2;

    /** Flips a channel's auto-caching; if it is now on, starts the downloads that implies. */
    static boolean toggleAuto(Context ctx, String chanId) {
        if (DataSync.isReceiver()) return false;
        Db db = Db.get(ctx);
        boolean on = !db.isAuto(chanId);
        db.setAuto(chanId, on);
        if (on) reconcileAuto(ctx);
        return on;
    }

    /**
     * Auto-caching: for every channel switched on with a long press on a cache button, the
     * {@link #AUTO_KEEP} oldest unwatched videos in the feed are kept downloaded. Runs whenever
     * the feed is (re)loaded and when a video is marked watched, so the next oldest follows.
     * Downloads only on Wi-Fi. Marking a video watched in the feed removes its
     * download, so the next oldest follows at once; a video finished in the player keeps its
     * download until removed there. A download the user cancelled or removed by hand is marked
     * {@link Db#CACHE_SKIP} and left alone, or it would simply come back.
     */
    static void reconcileAuto(Context ctx) {
        if (DataSync.isReceiver()) return;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                if (!Sync.unmetered(app)) return;
                Db db = Db.get(app);
                List<String> channels = db.autoChannels();
                for (String chan : channels) {
                    try {
                        reconcileChannel(app, db, chan, channels.size() == 1);
                    } catch (IOException e) {
                        Dbg.i("cache: auto for " + db.chanName(chan) + " stopped: " + e);
                    } catch (IllegalStateException e) {
                        Dbg.i("cache: cannot start now: " + e);   // app in the background
                        return;
                    }
                }
            }
        }, "auto-cache").start();
    }

    /**
     * One channel's share, oldest first: a video over {@link #BIG} is the channel's only
     * download unless it is the only auto channel with eligible videos left. None starts
     * without {@link #room} for it. Sizes come from resolving the
     * streams, which is cached and for the top of the feed already done by priming.
     */
    private static void reconcileChannel(Context app, Db db, String chan, boolean onlyChannel)
            throws IOException {
        List<Vid> oldest = db.autoOldest(chan, AUTO_KEEP);
        for (int i = 0; i < oldest.size(); i++) {
            Vid v = oldest.get(i);
            // A download under way or done, with nothing after it to decide on: no size needed.
            if (v.cache != Db.CACHE_NONE && i == oldest.size() - 1) return;
            long size;
            try {
                size = v.cache == Db.CACHE_DONE
                        ? videoFile(app, v.id).length() + audioFile(app, v.id).length()
                        : streamBytes(app, v.id);
            } catch (IOException e) {
                // Let Cacher own extraction failures too, so its persistent retry policy
                // applies even when the preliminary size lookup failed.
                if (v.cache == Db.CACHE_NONE) cache(app, v.id, true);
                return;
            }
            if (v.cache == Db.CACHE_NONE) {
                if (!room(app, size)) {
                    Dbg.i("cache: no room to auto-cache " + v.id + " (" + (size >> 20) + " MB, "
                            + (dir(app).getUsableSpace() >> 20) + " MB free)");
                    return;
                }
                Dbg.i("cache: auto " + v.id + " (" + (size >> 20) + " MB) for "
                        + db.chanName(chan));
                cache(app, v.id, true);
            }
            if (size > BIG && !onlyChannel) return;
        }
    }

    private static long streamBytes(Context app, String id) throws IOException {
        Yt.Media m = Yt.resolve(id, Ident.get(app));
        return m.video.bytes + m.audio.bytes;
    }

    // ---- free space -----------------------------------------------------------

    /** No download starts with less free space than this, counting what running ones still owe. */
    private static final long MIN_FREE = 1536L << 20;
    /** Nor if finishing it would leave less than this: a big video must not fill the disk. */
    private static final long MIN_LEFT = 512L << 20;
    /** A video over this size is its channel's only auto download while other channels remain. */
    private static final long BIG = 1L << 30;
    /**
     * Bytes each running download still has to write. Free space alone does not see them: two
     * downloads checking at the same moment would each find room for itself and fill the disk
     * between them.
     */
    private static final Map<String, long[]> OWED = new HashMap<>();

    /** Whether {@code need} more bytes fit, leaving the disk its margin. */
    private static boolean room(Context ctx, long need) {
        synchronized (OWED) {
            long free = dir(ctx).getUsableSpace();
            for (long[] owed : OWED.values()) free -= owed[0];
            return free >= MIN_FREE && free - need >= MIN_LEFT;
        }
    }

    static File videoFile(Context ctx, String id) { return new File(dir(ctx), id + ".v"); }

    static File audioFile(Context ctx, String id) { return new File(dir(ctx), id + ".a"); }

    static boolean isCached(Context ctx, String id) {
        return videoFile(ctx, id).exists() && audioFile(ctx, id).exists();
    }

    /** A finalized file that MediaExtractor cannot read is no longer an offline copy. */
    static boolean invalidateCached(Context ctx, String id) {
        delete(ctx, id);
        if (isCached(ctx, id)) return false;
        mark(ctx, id, Db.CACHE_NONE);
        return true;
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "media");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * A partial download, named for the format it holds: {@code <id>.v.<itag>.part}. The
     * client fallback chain can hand back a different itag on a retry, and appending that
     * stream's bytes to another format's partial file would produce a file that completes and
     * does not play.
     */
    private static File partFile(Context ctx, String id, String suffix, int itag) {
        return new File(dir(ctx), id + suffix + "." + itag + ".part");
    }

    /** Bytes of a video already on disk, partial files included: how far an attempt got. */
    private static long bytesOnDisk(Context ctx, String id) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return 0;
        long n = 0;
        for (File f : files) if (f.getName().startsWith(id + ".")) n += f.length();
        return n;
    }

    /** Removes everything belonging to a video: final files and any partial ones. */
    private static void delete(Context ctx, String id) {
        deleteMatching(ctx, id + ".", null);
    }

    /** Deletes files whose name starts with {@code prefix}, sparing {@code keep}. */
    private static void deleteMatching(Context ctx, String prefix, File keep) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(prefix) && !f.equals(keep)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // ---- service ------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (DataSync.GATE) { live = this; }
        // One video at a time (the maintainer, 2026-09-30): the rest wait their turn in this queue, and a
        // download sleeping out a short back-off holds it for at most 30 s.
        pool = Executors.newSingleThreadExecutor();
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW));
        // A foreground service is not enough on its own: with the screen off the CPU is allowed
        // to sleep between network callbacks, which stalls a long download indefinitely.
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tinytube:cache");
        wakeLock.setReferenceCounted(false);
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tinytube:cache");
            wifiLock.setReferenceCounted(false);
        }
        watchdog = new Thread(new Runnable() {
            @Override public void run() { watch(); }
        }, "cache-watch");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void holdWakeLocks(boolean hold) {
        try {
            if (hold) {
                if (!wakeLock.isHeld()) wakeLock.acquire();
                if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            } else {
                if (wakeLock.isHeld()) wakeLock.release();
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            }
        } catch (RuntimeException ignored) {
            // Never let lock bookkeeping take the service down.
        }
    }

    /**
     * The locks are held only while bytes are actually moving. A download sleeping out its
     * back-off -- up to half an hour at a time, an hour and a half over the whole schedule --
     * used to keep the CPU and the Wi-Fi radio awake the entire time for nothing. Without the
     * lock the sleep just stretches over any deep sleep, and the retry happens when the phone
     * is next awake anyway, which is exactly when a network is likely to be back.
     */
    private synchronized void transferring(boolean on) {
        transferring += on ? 1 : -1;
        holdWakeLocks(transferring > 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? null : intent.getAction();
        final String id = intent == null ? null : intent.getStringExtra("id");
        if (!DataSync.isReceiver() && id != null && ACTION_CANCEL.equals(action)) {
            CANCELLED.add(id);           // the caller has already set the row's state
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (!enterForeground()) return START_NOT_STICKY;
        if (DataSync.isReceiver()) {
            // Satisfy startForegroundService even for an old sticky/service restart.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (id == null) {
            // Restarted by the system after being killed: pick up whatever was mid-download.
            int resumed = 0;
            for (String pending : Db.get(this).dueDownloads()) {
                enqueue(pending, true);
                resumed++;
            }
            if (resumed == 0) stopIfIdle(); else Dbg.i("cache: resuming " + resumed + " download(s)");
            return START_STICKY;
        }

        enqueue(id, intent.getBooleanExtra("auto", false));
        return START_STICKY;
    }

    private boolean enterForeground() {
        if (stopping) { stopSelf(); return false; }
        try {
            startForeground(NOTE_ID, note("Downloading", "Starting…", 0));
            return true;
        } catch (ForegroundServiceStartNotAllowedException e) {
            // Android 15 can accept startForegroundService but refuse this promotion after
            // the dataSync allowance is exhausted. Stop without a sticky restart or touching
            // BUSY/retry rows: the next permitted launch/pass resumes the same partial files.
            Dbg.i("cache: foreground promotion deferred by Android");
            onTimeout(latestStartId, 0);
            return false;
        }
    }

    /** Runs one video's download, retrying with a long back-off before giving up. */
    private void enqueue(final String id, final boolean auto) {
        if (!active.add(id)) return;                   // already running in this service
        CANCELLED.remove(id);
        try {
            pool.execute(new Runnable() {
                @Override public void run() {
                    try {
                        attemptWithRetries(id, auto);
                    } finally {
                        active.remove(id);
                        stopIfIdle();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // onTimeout may race a new start. Leave the database BUSY for the next service
            // instance, but never leave a phantom active id in this one.
            active.remove(id);
            Dbg.i("cache: executor stopped before " + id + " could start");
            stopIfIdle();
        }
    }

    /**
     * Stops a download that has gone quiet. Closing its {@link Source} unblocks the read the
     * fetch loop is sitting in, which then fails the attempt -- and the ordinary retry path
     * picks it up again from the bytes already in the part file, so a stall costs a back-off
     * and not the download.
     */
    private void watch() {
        while (!stopping) {
            try { Thread.sleep(WATCH_MS); } catch (InterruptedException e) { return; }
            long now = android.os.SystemClock.elapsedRealtime();
            List<Job> jobs;
            synchronized (running) { jobs = new ArrayList<>(running); }
            for (Job j : jobs) {
                long bytes = j.bytes;
                if (bytes != j.sampled) { j.sampled = bytes; j.sampledAt = now; continue; }
                if (j.stalled || now - j.sampledAt < STALL_MS) continue;
                j.stalled = true;
                Dbg.i("cache: " + j.id + " stalled at " + (bytes >> 20) + " MB for "
                        + ((now - j.sampledAt) / 1000) + "s, stopping it");
                j.source.close();
            }
        }
    }

    private void attemptWithRetries(String id, boolean auto) {
        long onDiskBefore = bytesOnDisk(this, id);
        boolean refreshedVisitor = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (stopping) return;
            if (CANCELLED.contains(id)) { delete(this, id); return; }
            try {
                transferring(true);
                try {
                    download(id, auto);
                } finally {
                    transferring(false);
                }
                return;
            } catch (Exception e) {
                if (stopping) return;
                if (CANCELLED.contains(id)) { delete(this, id); return; }
                long onDisk = bytesOnDisk(this, id);
                boolean moved = onDisk - onDiskBefore >= PROGRESS_MIN;
                onDiskBefore = onDisk;
                Dbg.i("cache: attempt " + attempt + "/" + MAX_ATTEMPTS + " for " + id
                        + " failed at " + (onDisk >> 20) + " MB: " + e);
                // A fresh URL with a visitor-specific media refusal stays forbidden at every
                // range size. On the next attempt, mint it under a new visitor identity.
                if (!refreshedVisitor && e.getMessage() != null
                        && e.getMessage().startsWith("HTTP 403 reading ")) {
                    refreshedVisitor = true;
                    Ident.refreshAfter403(this);
                }
                // An attempt that wrote real bytes starts the allowance over: the download is
                // being interrupted, not failing, and it is getting there.
                if (moved) attempt = 0;
                else if (attempt == MAX_ATTEMPTS) {
                    if (permanentFailure(e)) {
                        Dbg.i("cache: permanent failure for " + id + ": " + e);
                        Crash.reportDownload(this, "Permanent download failure", e);
                        mark(this, id, Db.CACHE_SKIP);
                        delete(this, id);
                        return;
                    }
                    Db db = Db.get(this);
                    boolean forbidden = e.getMessage() != null
                            && e.getMessage().contains("HTTP 403");
                    long delay = forbidden ? FORBIDDEN_DEFER_MS : DEFER_MS;
                    int rounds = db.deferCache(id, System.currentTimeMillis() + delay);
                    Dbg.i("cache: deferring " + id + " for " + (delay / 60_000)
                            + " min after " + e);
                    if (rounds == 1 && forbidden)
                        Crash.reportDownload(this, "Download still forbidden after retry", e);
                    if (rounds == REPORT_ROUNDS && !forbidden) Crash.reportDownload(this,
                            "Download still failing after three days", e);
                    return;
                }
                long wait = BACKOFF_MS[Math.min(Math.max(attempt - 1, 0), BACKOFF_MS.length - 1)];
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /** Local file finalisation and unsupported formats cannot be fixed by waiting for YouTube. */
    private static boolean permanentFailure(Exception e) {
        String message = e.getMessage();
        return message != null && (message.equals("could not finalise download")
                || message.equals("no usable video/audio pair"));
    }

    private void download(String id, boolean auto) throws IOException {
        Yt.Media m = Yt.serving(this, id, true);
        File videoPart = partFile(this, id, ".v", m.video.itag);
        File audioPart = partFile(this, id, ".a", m.audio.itag);
        // A part left by a different format (the client fallback chain can change the pick)
        // is useless to this attempt.
        deleteMatching(this, id + ".v.", videoPart);
        deleteMatching(this, id + ".a.", audioPart);

        long[] owed = {Math.max(0, m.video.bytes - videoPart.length())
                + Math.max(0, m.audio.bytes - audioPart.length())};
        synchronized (OWED) {
            if (!room(this, owed[0])) {
                Dbg.i("cache: no room for " + id + " (" + (owed[0] >> 20) + " MB, "
                        + (dir(this).getUsableSpace() >> 20) + " MB free)");
                delete(this, id);
                mark(this, id, Db.CACHE_NONE);
                return;
            }
            OWED.put(id, owed);
        }
        try {
            // SponsorBlock first: it is tiny and must be present for offline playback.
            Sponsor.fetchAndStore(this, id);

            long[] written = {0};
            fetch(m.video, videoPart, id, written, owed);
            fetch(m.audio, audioPart, id, written, owed);
        } finally {
            synchronized (OWED) { OWED.remove(id); }
        }

        if (CANCELLED.contains(id)) { delete(this, id); return; }
        if (!videoPart.renameTo(videoFile(this, id)) || !audioPart.renameTo(audioFile(this, id))) {
            throw new IOException("could not finalise download");
        }
        mark(this, id, Db.CACHE_DONE);
        // No "Downloaded" notification: the row's icon says so, and the progress notification
        // (which a foreground service must have) goes away with the service.
    }

    /**
     * Downloads one stream by reading a {@link Source} sequentially, which is also what playback
     * uses -- so the bounded-range rules and the 403 back-off live in exactly one place.
     */
    private void fetch(Yt.Stream stream, File dest, String id, long[] written,
                       long[] owed) throws IOException {
        // Carry on from whatever a previous attempt managed. The part file is named for its itag,
        // so bytes are only ever appended to the same format; a size mismatch on the same itag
        // means the partial file is stale and gets thrown away.
        long pos = dest.exists() ? dest.length() : 0;
        if (pos > stream.bytes) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            pos = 0;
        }
        if (pos > 0) Dbg.i("cache: resuming " + dest.getName() + " at " + (pos >> 20) + "MB");
        if (pos >= stream.bytes) { written[0] += pos; return; }
        written[0] += pos;
        Source source = new Source(stream.url, stream.bytes, stream.bitrate);
        Job job = new Job(id, source, written[0], android.os.SystemClock.elapsedRealtime());
        synchronized (running) { running.add(job); }
        FileOutputStream out = new FileOutputStream(dest, true);
        try {
            byte[] buf = new byte[1 << 16];
            while (pos < stream.bytes) {
                if (CANCELLED.contains(id)) throw new IOException("cancelled");
                int n = source.readAt(pos, buf, 0, buf.length);
                if (n <= 0) break;
                out.write(buf, 0, n);
                synchronized (OWED) { owed[0] -= n; }
                pos += n;
                written[0] += n;
                job.bytes = written[0];       // what the watchdog watches
            }
            if (pos < stream.bytes) {
                throw new IOException(job.stalled ? "stalled" : "download truncated");
            }
        } finally {
            synchronized (running) { running.remove(job); }
            out.close();
            source.close();
        }
    }

    private Notification note(String title, String text, int percent) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true);
        if (percent >= 0) b.setProgress(100, percent, false);
        else if (percent == -2) b.setProgress(0, 0, true);
        else b.setSmallIcon(android.R.drawable.stat_sys_download_done);
        return b.build();
    }

    private void stopIfIdle() {
        if (active.isEmpty()) {
            holdWakeLocks(false);
            // stopSelfResult protects a new start that arrived after the worker observed idle.
            if (stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /**
     * Android 15 allows a dataSync foreground service six hours a day, then calls this and
     * expects the service to stop within seconds -- or it throws. The downloads keep their BUSY
     * state, so they pick up again the next time the app is opened.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Dbg.i("cache: foreground time limit reached, pausing " + active.size() + " download(s)");
        stopping = true;
        watchdog.interrupt();
        pool.shutdownNow();
        active.clear();
        holdWakeLocks(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        watchdog.interrupt();
        live = null;
        holdWakeLocks(false);
        pool.shutdownNow();
        active.clear();
        super.onDestroy();
    }
}
