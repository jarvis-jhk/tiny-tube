package org.tinytube.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

/**
 * Fullscreen landscape player.
 *
 * <p>Gestures follow the mapping chosen for this app: vertical drag on the left third is
 * brightness, on the right third volume, in the middle playback speed. Taps beyond the first in
 * a burst seek 10 s each -- back on the left third, forward on the right -- and a double tap in
 * the middle toggles pause. There is deliberately no press-and-hold; the progress bar scrubs.
 *
 * <p>The activity is {@code singleTask}, so a second video shares one instance: a link shared
 * while a player exists arrives through {@link #onNewIntent} and relaunches with the new id.
 */
public final class Play extends Activity implements Engine.Listener, SurfaceHolder.Callback {

    private static final int CONTROLS_TIMEOUT_MS = 3000;
    /**
     * How far short of the duration still counts as having played the video out.
     *
     * <p>The last decoded frame's timestamp sits a little below the container's duration anyway
     * (measured 339 ms of 123.3 s), so exact equality never holds. Tighter than
     * {@link Db#WATCHED_TAIL_US}, so it can only be reached by a video already counted watched.
     */
    private static final long END_SLACK_US = 2_000_000L;
    private static final int TICK_MS = 200;
    private static final long SAVE_EVERY_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private Engine engine;
    private SurfaceView surfaceView;
    private View controls, hudView, uncacheButton;
    private ProgressBar loading;
    private TextView titleView, posView, lenView, errorView, hud;
    private SeekBar seek;

    private String videoId;
    private Vid vid;
    private boolean cachedPlayback;
    private List<Sponsor.Seg> segments;
    /** Per-segment skip attempts, so a seek that lands short cannot loop forever. */
    private int[] skipAttempts;
    private long lastSkipAtMs;

    private boolean surfaceReady;
    private boolean started;
    /** The engine has reported a duration; before that its position is only the resume point. */
    private boolean ready;
    private boolean playbackFailed;
    /** Playback ran off the end; the next seek or play restarts the decoders. */
    private boolean ended;
    private boolean scrubbing;
    /**
     * A seek was issued and the seek bar is pinned to its target until the clock lands there.
     * Without this the ticker repaints the thumb from positionUs(), which still reads the old
     * position for the few hundred ms until the seek re-anchors -- so the thumb flicks back to
     * where playback was and then jumps to the target.
     */
    private int pendingSeekMs = -1;
    private long pendingSeekUntil;
    /** Clock within this of the target counts as the seek having landed. */
    private static final long SEEK_SETTLE_MS = 500;
    private boolean markedWatched;
    private long lastSaveAt;
    private long controlsShownAt;

    // gesture state
    private static final int ZONE_NONE = 0, ZONE_BRIGHT = 1, ZONE_SPEED = 2, ZONE_VOLUME = 3;
    private int zone;
    private float gestureStartY;
    private float gestureStartValue;

    /** Multi-tap: how long after a tap another one still counts as part of the same gesture. */
    private static final long MULTI_TAP_MS = 350;
    /** Seconds jumped per extra tap: double tap 10 s, triple 20 s, and so on. */
    private static final int TAP_SEEK_SECONDS = 10;
    private long tapDownAtMs;
    private float tapDownX, tapDownY;
    private long lastTapAtMs;
    private int tapCount;
    private int tapZone;
    /** Position the accumulated tap-seek is measured from; -1 when no seek is pending. */
    private long tapSeekBaseUs = -1;
    private long tapSeekOffsetUs;
    private AudioManager audio;
    private int maxVolume;
    private static final String PLAYER_PREFS = "player";
    private float seenBrightness;
    private float seenVolume;

    // =========================================================================

    private long createdAt;

    @Override
    protected void onCreate(Bundle saved) {
        createdAt = android.os.SystemClock.uptimeMillis();
        super.onCreate(saved);
        videoId = idFromIntent(getIntent());
        if (videoId == null) { finish(); return; }

        setContentView(R.layout.play);
        surfaceView = findViewById(R.id.surface);
        controls = findViewById(R.id.controls);
        loading = findViewById(R.id.loading);
        titleView = findViewById(R.id.title);
        posView = findViewById(R.id.pos);
        lenView = findViewById(R.id.len);
        errorView = findViewById(R.id.error);
        hud = findViewById(R.id.hud);
        seek = findViewById(R.id.seek);
        uncacheButton = findViewById(R.id.btn_uncache);

        audio = getSystemService(AudioManager.class);
        maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        vid = Db.get(this).video(videoId);
        titleView.setText(vid != null ? vid.title : "");

        goFullscreen();
        applyBarInsets();
        setUpGestures();
        setUpSeekBar();
        uncacheButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Watched to the end and no longer wanted offline: drop the files and go back
                // to the feed, where the row leaves the list with them.
                Cacher.uncache(Play.this, videoId);
                finish();
            }
        });

        surfaceView.getHolder().addCallback(this);
        engine = new Engine(this);
        restorePrefs();
        showControls();
    }

    // ---- player settings ----------------------------------------------------

    /** Restore the last player settings regardless of which video was opened. */
    private void restorePrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences(PLAYER_PREFS, MODE_PRIVATE);
        if (prefs.contains("bright")) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = prefs.getFloat("bright", -1f);
            getWindow().setAttributes(lp);
        }
        if (prefs.contains("vol")) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.round(prefs.getFloat("vol", 0f) * maxVolume), 0);
        }
        seenBrightness = getWindow().getAttributes().screenBrightness;
        seenVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maxVolume;
        Dbg.i("play: settings bright=" + seenBrightness + " vol=" + seenVolume);
    }

    /**
     * Save changes on the way out, including volume-key changes. apply() updates the in-memory
     * preferences immediately, so a new player can restore them even if this one is recreating.
     */
    private void savePrefs() {
        float bright = getWindow().getAttributes().screenBrightness;
        float vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maxVolume;
        android.content.SharedPreferences.Editor edit = getSharedPreferences(PLAYER_PREFS, MODE_PRIVATE).edit();
        boolean changed = false;
        if (bright != seenBrightness) {
            edit.putFloat("bright", bright);
            seenBrightness = bright;
            changed = true;
        }
        if (vol != seenVolume) {
            edit.putFloat("vol", vol);
            seenVolume = vol;
            changed = true;
        }
        if (changed) edit.apply();
    }

    /**
     * Accepts both our own extra and a youtube.com / youtu.be link from another app. The
     * activity is exported, so even the extra is filtered down to id characters before it can
     * become a file name.
     */
    private static String idFromIntent(Intent intent) {
        String direct = intent.getStringExtra("id");
        if (direct != null && !direct.isEmpty()) return nullIfEmpty(cut(direct));
        CharSequence text = intent.getData() != null
                ? intent.getData().toString() : intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return null;
        String s = text.toString();
        int v = s.indexOf("v=");
        if (v >= 0) return nullIfEmpty(cut(s.substring(v + 2)));
        int shortLink = s.indexOf("youtu.be/");
        if (shortLink >= 0) return nullIfEmpty(cut(s.substring(shortLink + 9)));
        return null;
    }

    private static String nullIfEmpty(String s) { return s.isEmpty() ? null : s; }

    /** The leading run of characters a video id can contain: ASCII letters, digits, '-', '_'. */
    private static String cut(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return s.substring(0, i);
        }
        return s;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String id = idFromIntent(intent);
        if (id == null) return;
        if (id.equals(videoId) && (!external(intent) || Db.get(this).isKnown(id))) return;
        // singleTask routes a second link to this instance. Relaunch with the new intent
        // rather than just resuming the old video, which is what ignoring this did.
        Dbg.i("play: new intent for " + id + ", replacing " + videoId);
        setIntent(intent);
        recreate();
    }

    private static boolean external(Intent intent) {
        return Intent.ACTION_VIEW.equals(intent.getAction())
                || Intent.ACTION_SEND.equals(intent.getAction());
    }

    private void goFullscreen() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    // ---- surface / start ----------------------------------------------------

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        // Coming back from the background: the old surface is gone, hand over the new one.
        if (started && engine != null) engine.setSurface(holder.getSurface());
        startIfPossible();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; }

    private void startIfPossible() {
        if (started || !surfaceReady) return;
        started = true;
        // "--ez from0 true" on an am start ignores the stored position: benchmarking aid.
        final long resumeUs = vid != null && !getIntent().getBooleanExtra("from0", false)
                ? vid.pos * 1000L : 0;
        cachedPlayback = Cacher.isCached(this, videoId);
        Dbg.i("play: resolving " + (android.os.SystemClock.uptimeMillis() - createdAt)
                + "ms after onCreate (cached=" + cachedPlayback + ")");

        // Cached segments first so skipping works with no network at all; then a refresh if they are
        // due, so a video downloaded weeks ago still gets recent SponsorBlock submissions.
        // Segments and their attempt counters are touched on the main thread only, so the ticker
        // never sees one without the other.
        final Sponsor.Updated onMain = new Sponsor.Updated() {
            @Override public void onSegments(final List<Sponsor.Seg> fresh) {
                main.post(new Runnable() {
                    @Override public void run() { segments = fresh; armSkips(); }
                });
            }
        };
        new Thread(new Runnable() {
            @Override public void run() {
                List<Sponsor.Seg> stored = Sponsor.fetchAndStore(Play.this, videoId);
                Dbg.i("sponsorblock: " + (stored == null ? 0 : stored.size())
                        + " segment(s) for " + videoId);
                onMain.onSegments(stored);
                if (Db.get(Play.this).segsDue(videoId, System.currentTimeMillis())) {
                    Sponsor.refreshAsync(Play.this, videoId, onMain);
                }
            }
        }, "sb").start();

        if (cachedPlayback) {
            engine.open(Engine.Track.file(Cacher.videoFile(this, videoId).getPath()),
                    Engine.Track.file(Cacher.audioFile(this, videoId).getPath()),
                    surfaceView.getHolder().getSurface(), resumeUs);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    boolean hit = Yt.cached(videoId) != null;
                    final Yt.Media m = Yt.resolve(videoId, Ident.get(Play.this));
                    final Engine.Prepared p = Yt.takePrepared(m, resumeUs);
                    final Heads.Loaded disk = p != null ? null
                            : Heads.load(Play.this, videoId, m, (int) (resumeUs / 1000));
                    Dbg.i("play: streams " + (hit ? "from cache" : "resolved")
                            + (p != null ? ", extractors prepared"
                            : disk.videoHead != null ? ", heads on disk"
                                    + (disk.videoIsland != null ? " with resume fragment" : "")
                            : ""));
                    if (vid == null) {
                        vid = new Vid(videoId);
                        vid.title = m.title;
                        vid.chan = m.chan;
                        vid.chanId = m.chanId;
                        vid.dur = m.durationSec;
                        // An external open is also an add, dated now like the search plus.
                        // Reuse the player response: no extra request delays playback.
                        if (external(getIntent())) {
                            vid.pub = System.currentTimeMillis();
                            try {
                                Db.get(Play.this).putVideos(java.util.Collections.singletonList(vid));
                                Sync.feedVersion++;
                            } catch (Exception e) {
                                Crash.reportDownload(Play.this, "External video feed insert failed", e);
                                // A storage failure must not prevent the requested playback.
                            }
                        }
                    }
                    main.post(new Runnable() {
                        @Override public void run() {
                            titleView.setText(vid.title);
                            if (p != null) {
                                p.activate();
                                engine.open(Engine.Track.prepared(p.videoEx, p.video),
                                        Engine.Track.prepared(p.audioEx, p.audio),
                                        surfaceView.getHolder().getSurface(), resumeUs);
                            } else {
                                engine.open(Engine.Track.remote(m.video, disk.videoHead,
                                                disk.videoIslandAt, disk.videoIsland),
                                        Engine.Track.remote(m.audio, disk.audioHead,
                                                disk.audioIslandAt, disk.audioIsland),
                                        surfaceView.getHolder().getSurface(), resumeUs);
                            }
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() { onError(String.valueOf(e.getMessage())); }
                    });
                }
            }
        }, "resolve").start();
    }

    // ---- Engine.Listener ----------------------------------------------------

    @Override
    public void onReady(final int durationMs) {
        main.post(new Runnable() {
            @Override public void run() {
                Dbg.i("play: ready " + (android.os.SystemClock.uptimeMillis() - createdAt)
                        + "ms after onCreate");
                if (isFinishing() || isDestroyed() || engine == null) return;
                ready = true;
                if (!resumed) engine.setPlaying(false);
                updateScreenOn();
                loading.setVisibility(View.GONE);
                seek.setMax(Math.max(1, durationMs));
                lenView.setText(Feed.clock(durationMs / 1000));
                sizeSurface();
                if (resumed) main.post(ticker);   // else onResume starts it
                hideControlsLater();
            }
        });
    }

    @Override
    public void onEnded() {
        main.post(new Runnable() {
            @Override public void run() {
                // Only a genuine end counts. A stream that fails mid-way also reports EOF on both
                // tracks, and marking that watched silently loses the video from the feed.
                long durUs = engine.durationUs();
                long posUs = engine.positionUs();
                boolean reallyFinished = durUs > 0 && posUs >= durUs - Db.WATCHED_TAIL_US;
                // Offering to delete the download is a stricter question still: it has to have
                // played out, not merely reached the last seconds.
                boolean playedOut = durUs > 0 && posUs >= durUs - END_SLACK_US;
                Dbg.i("ended at " + (posUs / 1000) + "ms of " + (durUs / 1000)
                        + "ms -> " + (reallyFinished ? "watched" : "stopped early, not watched")
                        + (playedOut ? ", played out" : ""));
                if (reallyFinished) markWatched();
                if (playedOut) offerUncache();
                ended = true;
                engine.setPlaying(false);
                updateScreenOn();
                showControls();
                controlsShownAt = Long.MAX_VALUE;   // keep them up at the end
            }
        });
    }

    /** After the end, a seek is a request to watch again, so it also un-pauses. */
    private void resumeIfEnded() {
        if (!ended) return;
        ended = false;
        engine.setPlaying(true);
        updateScreenOn();
        hideControlsLater();
    }

    @Override
    public void onError(final String message) {
        main.post(new Runnable() {
            @Override public void run() {
                playbackFailed = true;
                updateScreenOn();
            }
        });
        Yt.forget(videoId);      // if the cached URLs were the problem, the next try re-resolves
        if (cachedPlayback && message != null && message.contains("stream read failed")) {
            recoverCachedFile();
            return;
        }
        Engine e = engine;
        boolean forbidden = (message != null && message.contains("HTTP 403"))
                || (e != null && e.forbidden());
        if (forbidden && !cachedPlayback
                && !videoId.equals(recoveredFrom403)) {
            main.post(new Runnable() {
                @Override public void run() { recoverFrom403(); }
            });
            return;
        }
        main.post(new Runnable() {
            @Override public void run() {
                loading.setVisibility(View.GONE);
                errorView.setVisibility(View.VISIBLE);
                errorView.setText(message);
            }
        });
    }

    private volatile boolean recoveringCachedFile;

    /** A complete-sized download can still contain unreadable samples. Resume online. */
    private void recoverCachedFile() {
        if (recoveringCachedFile) return;
        recoveringCachedFile = true;
        final Context app = getApplicationContext();
        final String id = videoId;
        final int posMs = ready && engine != null ? (int) (engine.positionUs() / 1000) : 0;
        Dbg.i("play: cached video unreadable at " + posMs + "ms, streaming instead");
        Crash.reportDownload(app, "Cached playback failure", new IOException("stream read failed"));
        new Thread(new Runnable() {
            @Override public void run() {
                if (posMs > 0 && !markedWatched) Db.get(app).setPos(id, posMs);
                final boolean removed = Cacher.invalidateCached(app, id);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (removed) recreate();
                        else {
                            loading.setVisibility(View.GONE);
                            errorView.setVisibility(View.VISIBLE);
                            errorView.setText("Could not remove unreadable download");
                        }
                    }
                });
            }
        }, "cached-recovery").start();
    }

    /** The video whose media 403 was last answered with a new identity; once per video. */
    private static volatile String recoveredFrom403;

    /**
     * A visitor identity refused past the first minute (see {@link Yt#serving}) stops a video
     * about a minute in. Keep the place, get streams that pass the probe -- under a new identity
     * if need be -- and start again from there.
     */
    private void recoverFrom403() {
        recoveredFrom403 = videoId;
        final int posMs = ready && engine != null ? (int) (engine.positionUs() / 1000) : -1;
        final Context app = getApplicationContext();
        final String id = videoId;
        Dbg.i("play: media 403 at " + posMs + "ms, resolving again");
        new Thread(new Runnable() {
            @Override public void run() {
                if (posMs > 0 && !markedWatched) Db.get(app).setPos(id, posMs);
                try {
                    Yt.serving(app, id, false);
                } catch (Exception e) {
                    Dbg.i("play: still refused: " + e);
                }
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing() && !isDestroyed()) recreate();
                    }
                });
            }
        }, "recover").start();
    }

    /** A position, unlike a duration, is "0:00" at zero rather than blank. */
    private static String clock(int seconds) {
        return seconds <= 0 ? "0:00" : Feed.clock(seconds);
    }

    /** Letterboxes the surface to the video's aspect ratio. */
    private void sizeSurface() {
        int vw = engine.videoWidth(), vh = engine.videoHeight();
        if (vw <= 0 || vh <= 0) return;
        View root = findViewById(R.id.root);
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw <= 0 || rh <= 0) return;
        float scale = Math.min(rw / (float) vw, rh / (float) vh);
        ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
        lp.width = Math.round(vw * scale);
        lp.height = Math.round(vh * scale);
        surfaceView.setLayoutParams(lp);
    }

    /** Loading, pause, end, errors and leaving the player all release the display. */
    private void updateScreenOn() {
        if (surfaceView == null) return;
        surfaceView.setKeepScreenOn(resumed && ready && !ended && !playbackFailed
                && engine != null && engine.isPlaying());
    }

    // ---- periodic work ------------------------------------------------------

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (isFinishing() || engine == null) return;
            long posUs = engine.positionUs();
            int posMs = (int) (posUs / 1000);

            if (!scrubbing) {
                if (pendingSeekMs >= 0 && Math.abs(posMs - pendingSeekMs) >= SEEK_SETTLE_MS
                        && android.os.SystemClock.elapsedRealtime() < pendingSeekUntil) {
                    seek.setProgress(pendingSeekMs);
                    posView.setText(clock(pendingSeekMs / 1000));
                } else {
                    pendingSeekMs = -1;
                    seek.setProgress(posMs);
                    posView.setText(clock(posMs / 1000));
                }
            }
            debugHeartbeat(posUs);
            Sync.tick(Play.this);        // catch up on the feed while a video is playing
            skipSponsor(posUs);
            savePosition(posMs, false);

            long durUs = engine.durationUs();
            if (durUs > 0 && posUs >= durUs - Db.WATCHED_TAIL_US) markWatched();

            main.postDelayed(this, TICK_MS);
            autoHideControls();
        }
    };

    /** Whether the activity is on screen: the ticker only runs between onResume and onPause. */
    private boolean resumed;

    private long dbgLastWall, dbgLastPos, dbgLastRendered, dbgLastDropped;

    private void debugHeartbeat(long posUs) {
        // Paused is not worth a line every two seconds, and neither is the first tick after a
        // resume, whose window would span however long the app was away.
        if (!engine.isPlaying()) { dbgLastWall = 0; return; }
        long wall = android.os.SystemClock.elapsedRealtime();
        if (dbgLastWall == 0) { dbgLastWall = wall; dbgLastPos = posUs; return; }
        // Every 10 s: every 2 s it was two thirds of all logcat lines while a video played.
        if (wall - dbgLastWall < 10_000) return;
        long dWall = wall - dbgLastWall, dPos = (posUs - dbgLastPos) / 1000;
        long dRendered = engine.dbgFramesRendered - dbgLastRendered;
        long dDropped = engine.dbgFramesDropped - dbgLastDropped;
        Dbg.v("hb ratio=" + (dPos * 100 / Math.max(1, dWall)) + "%"
                + " fps=" + (dRendered * 1000 / Math.max(1, dWall))
                + " rendered=" + dRendered + " dropped=" + dDropped
                + " ahead=" + (engine.dbgAheadCount == 0 ? 0
                        : engine.dbgAheadSum / engine.dbgAheadCount / 1000) + "ms"
                + " (" + (dDropped * 100 / Math.max(1, dRendered + dDropped)) + "% lost)"
                + (engine.dbgResyncs > 0 ? " resyncs=" + engine.dbgResyncs : ""));
        dbgLastWall = wall; dbgLastPos = posUs;
        dbgLastRendered = engine.dbgFramesRendered; dbgLastDropped = engine.dbgFramesDropped;
        engine.dbgAheadSum = 0; engine.dbgAheadCount = 0;
    }

    /** Attempts allowed per segment before letting it play rather than seeking again. */
    private static final int MAX_SKIP_ATTEMPTS = 3;
    /** A seek needs time to land; without this the ticker burns every attempt in one go. */
    private static final long SKIP_COOLDOWN_MS = 1500;

    /**
     * Silent auto-skip: no button, no toast, matching the PipePipe setup.
     *
     * <p>Seeking to a segment's end does not necessarily leave the segment. Audio in fragmented
     * MP4 seeks to a fragment boundary, which can fall <em>short</em> of the requested time -- so
     * a naive implementation lands back inside the same segment and skips again, forever,
     * flushing the audio track dozens of times a second. That is a hang with garbled sound, not a
     * subtle bug. So each segment gets a few attempts with a growing margin, then is left alone.
     */
    private void skipSponsor(long posUs) {
        List<Sponsor.Seg> segs = segments;
        int[] attempts = skipAttempts;
        if (segs == null || segs.isEmpty() || attempts == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSkipAtMs < SKIP_COOLDOWN_MS) return;

        float seconds = posUs / 1_000_000f;
        for (int i = 0; i < segs.size() && i < attempts.length; i++) {
            Sponsor.Seg s = segs.get(i);
            // The 0.15 s guard stops a segment whose end we just landed on from re-triggering.
            if (seconds < s.start || seconds >= s.end - 0.15f) continue;
            if (attempts[i] >= MAX_SKIP_ATTEMPTS) return;   // tried enough; let it play through
            attempts[i]++;
            lastSkipAtMs = now;
            long targetUs = (long) ((s.end + 0.5f * attempts[i]) * 1_000_000L);
            Dbg.i("sponsorblock: skip " + (int) s.start + "s -> " + (targetUs / 1000) + "ms"
                    + (attempts[i] > 1 ? " (attempt " + attempts[i] + ")" : ""));
            engine.seekTo(targetUs);
            return;
        }
    }

    /** Segments become skippable again when the position is moved by hand. */
    private void armSkips() {
        List<Sponsor.Seg> segs = segments;
        skipAttempts = new int[segs == null ? 0 : segs.size()];
        lastSkipAtMs = 0;
    }

    private void savePosition(final int posMs, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSaveAt < SAVE_EVERY_MS) return;
        lastSaveAt = now;
        // Nothing has played before ready, so there is nothing truer than what is stored; and
        // before the engine is even opened the position reads 0, which must not overwrite it.
        if (!ready || markedWatched) return;
        // Off the main thread: a SQLite commit does real I/O, and this runs during playback.
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() { Db.get(ctx).setPos(videoId, posMs); }
        }, "save-pos").start();
    }

    private void markWatched() {
        if (markedWatched) return;
        markedWatched = true;
        Dbg.i("marked watched: " + videoId + " at " + (engine.positionUs() / 1000)
                + "ms of " + (engine.durationUs() / 1000) + "ms");
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Db db = Db.get(ctx);
                db.setWatched(videoId, true);
                db.setPos(videoId, 0);          // finished: no stale resume bar in the feed
                Heads.delete(ctx, videoId);
            }
        }, "mark-watched").start();
    }

    /**
     * Offers to drop the download, once the video has actually run out.
     *
     * <p>Not when it is <em>marked</em> watched: that happens {@link Db#WATCHED_TAIL_US} before
     * the end, with a second still to play. The button is a big target in the middle of the
     * screen -- which is where a tap to pause goes -- so while anything is still playing, an
     * attempt to pause a downloaded video would delete it and close the player instead.
     */
    private void offerUncache() {
        if (Cacher.isCached(this, videoId)) uncacheButton.setVisibility(View.VISIBLE);
    }

    // ---- controls -----------------------------------------------------------

    /**
     * Issues a seek and pins the seek bar to the target until the clock catches up, so the thumb
     * does not flick back to the current position for a frame first. Every seek path goes through
     * here.
     */
    private void issueSeek(long targetUs) {
        long dur = engine.durationUs();
        long clamped = Math.max(0, dur > 0 ? Math.min(targetUs, dur) : targetUs);
        pendingSeekMs = (int) (clamped / 1000);
        pendingSeekUntil = android.os.SystemClock.elapsedRealtime() + 4000;
        seek.setProgress(pendingSeekMs);
        posView.setText(clock(pendingSeekMs / 1000));
        engine.seekTo(clamped);
    }

    private void setUpSeekBar() {
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) posView.setText(clock(value / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
                controlsShownAt = Long.MAX_VALUE;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                armSkips();          // a deliberate seek re-arms every segment
                issueSeek(bar.getProgress() * 1000L);
                showControls();
                resumeIfEnded();
            }
        });
    }

    private void showControls() {
        controls.animate().alpha(1f).setDuration(140).start();
        controls.setVisibility(View.VISIBLE);
        controlsShownAt = System.currentTimeMillis();
        setSystemBars(true);
    }

    private void hideControls() {
        controlsShownAt = 0;
        controls.animate().alpha(0f).setDuration(200).start();
        setSystemBars(false);
    }

    /**
     * Keeps the controls clear of the system bars whenever those are on screen. A fixed dp margin
     * is not enough: the phone shows a slim gesture pill, the tablet a full taskbar ~165 px tall
     * that would sit right on top of the seek bar.
     *
     * <p>The padding goes on the title and the bottom bar rather than on the container, so the
     * scrim behind them still reaches the very bottom of the window instead of stopping short
     * wherever the navigation bar begins.
     */
    private void applyBarInsets() {
        final int extra = Math.round(16 * getResources().getDisplayMetrics().density);
        final View bottomBar = findViewById(R.id.bottom_bar);
        final int titleTop = titleView.getPaddingTop();
        final int titleStart = titleView.getPaddingLeft();
        final int titleEnd = titleView.getPaddingRight();
        final int barBottom = bottomBar.getPaddingBottom();
        final int barStart = bottomBar.getPaddingLeft();
        final int barEnd = bottomBar.getPaddingRight();
        controls.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(
                    View v, android.view.WindowInsets insets) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                titleView.setPadding(titleStart + bars.left, titleTop + bars.top,
                        titleEnd + bars.right, titleView.getPaddingBottom());
                bottomBar.setPadding(barStart + bars.left, bottomBar.getPaddingTop(),
                        barEnd + bars.right, barBottom + bars.bottom + extra);
                return insets;
            }
        });
    }

    /**
     * The status and navigation bars come back with the controls. Fullscreen alone leaves no way
     * to reach the system gestures, so they follow the controls rather than staying hidden.
     */
    private void setSystemBars(boolean visible) {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        if (visible) c.show(WindowInsets.Type.systemBars());
        else c.hide(WindowInsets.Type.systemBars());
    }

    private void hideControlsLater() { controlsShownAt = System.currentTimeMillis(); }

    /** Hides the controls after a while, but only while the video is actually playing. */
    private void autoHideControls() {
        if (controlsShownAt == 0 || controlsShownAt == Long.MAX_VALUE) return;
        if (engine == null || !engine.isPlaying()) return;   // paused: they stay up
        if (System.currentTimeMillis() - controlsShownAt > CONTROLS_TIMEOUT_MS) hideControls();
    }

    // ---- gestures -----------------------------------------------------------

    private void setUpGestures() {
        findViewById(R.id.root).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                handleDrag(v, event);
                return true;
            }
        });
    }

    private int zoneOf(View root, float x) {
        float third = root.getWidth() / 3f;
        return x < third ? ZONE_BRIGHT : x > 2 * third ? ZONE_VOLUME : ZONE_SPEED;
    }

    /**
     * A tap landed. Taps in quick succession in the same third accumulate: the first is a plain
     * tap (show or hide the controls), and each one after that seeks
     * {@value #TAP_SEEK_SECONDS} s -- back on the left third, forward on the right -- so a double
     * tap is 10 s, a triple 20 s. In the middle third the second tap toggles pause.
     */
    private void onTap(View root, float x) {
        long now = System.currentTimeMillis();
        int hitZone = zoneOf(root, x);
        if (now - lastTapAtMs < MULTI_TAP_MS && hitZone == tapZone) {
            tapCount++;
        } else {
            tapCount = 1;
            tapZone = hitZone;
        }
        lastTapAtMs = now;

        if (tapCount == 1) {
            // Wait out the window before treating it as a lone tap, or every seek would flash
            // the controls on its way past.
            main.removeCallbacks(singleTap);
            main.postDelayed(singleTap, MULTI_TAP_MS);
            return;
        }
        main.removeCallbacks(singleTap);

        if (hitZone == ZONE_SPEED) {
            if (tapCount == 2) {
                if (ended) {
                    // "Play" at the end means from the top.
                    armSkips();
                    issueSeek(0);
                    resumeIfEnded();
                    flash("▶");
                    return;
                }
                boolean nowPlaying = !engine.isPlaying();
                engine.setPlaying(nowPlaying);
                updateScreenOn();
                flash(nowPlaying ? "▶" : "❚❚");
                if (nowPlaying) hideControlsLater(); else showControls();
            }
            return;
        }

        boolean back = hitZone == ZONE_BRIGHT;
        if (tapSeekBaseUs < 0) {
            tapSeekBaseUs = engine.positionUs();
            tapSeekOffsetUs = 0;
        }
        tapSeekOffsetUs += (back ? -1L : 1L) * TAP_SEEK_SECONDS * 1_000_000L;
        long seconds = Math.abs(tapSeekOffsetUs) / 1_000_000L;
        flash((back ? "«  " : "»  ") + seconds + " s");
        // Coalesce a burst of taps into one seek rather than flushing the decoders per tap.
        main.removeCallbacks(applyTapSeek);
        main.postDelayed(applyTapSeek, 220);
    }

    private final Runnable singleTap = new Runnable() {
        @Override public void run() {
            if (controlsShownAt == 0) showControls(); else hideControls();
        }
    };

    private final Runnable applyTapSeek = new Runnable() {
        @Override public void run() {
            if (tapSeekBaseUs < 0 || engine == null) return;
            long target = tapSeekBaseUs + tapSeekOffsetUs;
            long duration = engine.durationUs();
            if (target < 0) target = 0;
            if (duration > 0 && target > duration - 1_000_000L) target = duration - 1_000_000L;
            Dbg.i("tap seek to " + (target / 1000) + "ms");
            // Deliberately no armSkips() here: rewinding into a segment you just skipped should
            // not bounce you straight back out again.
            issueSeek(target);
            resumeIfEnded();
            tapSeekBaseUs = -1;
            tapSeekOffsetUs = 0;
        }
    };

    private void handleDrag(View root, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartY = event.getY();
                zone = ZONE_NONE;
                tapDownAtMs = System.currentTimeMillis();
                tapDownX = event.getX();
                tapDownY = event.getY();
                break;

            case MotionEvent.ACTION_MOVE: {
                float dy = gestureStartY - event.getY();
                if (zone == ZONE_NONE) {
                    if (Math.abs(dy) < 24) return;
                    zone = zoneOf(root, event.getX());
                    gestureStartY = event.getY();
                    gestureStartValue = currentValue();
                    dy = 0;
                }
                // A full swipe over ~70% of the screen height covers the whole range.
                float fraction = dy / (root.getHeight() * 0.7f);
                apply(gestureStartValue + fraction);
                break;
            }

            case MotionEvent.ACTION_UP: {
                // A tap is a touch that never became a drag and did not linger.
                boolean moved = Math.abs(event.getX() - tapDownX) > 30
                        || Math.abs(event.getY() - tapDownY) > 30;
                if (zone == ZONE_NONE && !moved
                        && System.currentTimeMillis() - tapDownAtMs < 350) {
                    onTap(root, event.getX());
                }
                zone = ZONE_NONE;
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                zone = ZONE_NONE;
                break;
            default:
                break;
        }
    }

    private float currentValue() {
        switch (zone) {
            case ZONE_BRIGHT: {
                float b = getWindow().getAttributes().screenBrightness;
                return b < 0 ? 0.5f : b;
            }
            case ZONE_VOLUME:
                return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maxVolume;
            case ZONE_SPEED:
                // 0..1 maps onto 0.25x..3x, so 1x sits at a comfortable 0.27.
                return (engine.speed() - 0.25f) / 2.75f;
            default:
                return 0;
        }
    }

    private void apply(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        switch (zone) {
            case ZONE_BRIGHT: {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = Math.max(0.01f, clamped);
                getWindow().setAttributes(lp);
                flash("☀  " + Math.round(clamped * 100) + "%");
                break;
            }
            case ZONE_VOLUME: {
                int step = Math.round(clamped * maxVolume);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0);
                flash("🔊  " + Math.round(step * 100f / maxVolume) + "%");
                break;
            }
            case ZONE_SPEED: {
                float speed = Math.round((0.25f + clamped * 2.75f) * 10) / 10f;   // 0.1 steps
                engine.setSpeed(speed);
                flash(trim(engine.speed()) + "×");
                break;
            }
            default:
                break;
        }
    }

    private static String trim(float f) {
        return f == Math.round(f) ? String.valueOf(Math.round(f))
                : String.format(java.util.Locale.US, "%.2f", f).replaceAll("0$", "");
    }

    private final Runnable hideHud = new Runnable() {
        @Override public void run() { hud.setVisibility(View.GONE); }
    };

    private void flash(String text) {
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideHud);
        main.postDelayed(hideHud, 750);
    }

    // ---- lifecycle ----------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        Yt.playing = true;           // background transfers wait while a player is on screen
        // Coming back from the background leaves playback paused, which is what you want -- but
        // with the controls hidden it just looks frozen, so show them.
        if (started && engine != null && !engine.isPlaying()) showControls();
        resumed = true;
        updateScreenOn();
        if (ready) { main.removeCallbacks(ticker); main.post(ticker); }
        // Changes made elsewhere while this activity was away are not player adjustments.
        seenVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maxVolume;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Off screen there is nothing for the ticker to do -- and left running it kept logging,
        // saving positions and poking Sync minutes after the app was gone, which is exactly what
        // stops the process going idle.
        resumed = false;
        updateScreenOn();
        main.removeCallbacks(ticker);
        dbgLastWall = 0;
        Yt.playing = false;
        savePrefs();
        if (engine != null) {
            engine.setPlaying(false);
            savePosition((int) (engine.positionUs() / 1000), true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (engine != null) {
            savePosition((int) (engine.positionUs() / 1000), true);
            engine.release();
            engine = null;
        }
    }

    static void open(Context ctx, String videoId) {
        ctx.startActivity(new Intent(ctx, Play.class).putExtra("id", videoId));
    }
}
