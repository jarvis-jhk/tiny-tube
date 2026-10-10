package org.tinytube.app;

import android.media.AudioAttributes;
import android.media.AudioTimestamp;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.PlaybackParams;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The player. Two {@link MediaExtractor}s feeding two {@link MediaCodec} decoders -- video to a
 * {@link Surface}, audio to an {@link AudioTrack} -- driven by a single clock.
 *
 * <p>This exists because 1080p YouTube is only ever offered as separate video-only and audio-only
 * streams, which {@code MediaPlayer} cannot combine, and because ExoPlayer would cost more APK
 * than the rest of the app put together. Everything here is framework API.
 *
 * <p><b>The clock is the audio track.</b> It is denominated in frames played, so it advances in
 * real time, survives underruns, and -- because a frame is a fixed slice of media time regardless
 * of how fast it is consumed -- stays correct when the playback speed changes. The frame count
 * comes from {@link AudioTrack#getTimestamp}, which reports what actually reached the DAC, capped
 * by {@link AudioTrack#getPlaybackHeadPosition()}, which counts what was handed to the mixer and
 * runs ~130 ms ahead of what is audible; see {@link #positionUs()}. Video frames are rendered
 * when their presentation timestamp catches up with that clock, and dropped if they fall too far
 * behind.
 */
final class Engine {

    interface Listener {
        /** Both tracks are decoding; duration is the container's, in ms. */
        void onReady(int durationMs);
        void onEnded();
        void onError(String message);
    }

    /** A frame more than this far behind the clock is dropped rather than shown late. */
    private static final long DROP_BEHIND_US = 80_000;
    /**
     * Once video is this far behind, dropping late frames cannot rescue it: the decoder still has
     * to work through every frame in between, so a stall of a few seconds leaves the picture
     * frozen for as long as it takes to chew the backlog while the audio clock runs on. Past this
     * point the extractor is moved to the current position instead.
     */
    private static final long RESYNC_BEHIND_US = 1_500_000;
    /** Minimum gap between resyncs, so a genuinely slow decoder cannot spin on them. */
    private static final long RESYNC_COOLDOWN_MS = 3000;
    /**
     * How far ahead of the clock a frame may be handed to the surface.
     *
     * <p>Frames are released with the wall-clock time they should appear at and SurfaceFlinger
     * schedules them, which is smooth and costs no blocking -- but the queue only absorbs a few
     * frames of lead. A timestamp far in the future is not honoured that long: the buffer comes
     * straight back, the decoder never blocks, and it runs flat out. Nothing else pulls a video
     * that is ahead back into line, because the resync and the drop below only catch it being
     * behind. Normal lead is ~170 ms.
     */
    private static final long MAX_AHEAD_US = 1_000_000;
    /**
     * How far short of the container duration a track may end and still count as a clean finish.
     *
     * <p>Generous on purpose. MediaExtractor stops yielding samples before the end of these
     * fragmented streams -- measured on a 945.075 s audio track (confirmed against the stream's
     * own {@code dur=945.075} and {@code clen}, with every byte fetched) the last sample came at
     * 918.8 s, 2.8% short. Treating that as a read failure put an error on screen at the end of
     * every video. Anything within this of the end is the end.
     */
    private static final long END_TOLERANCE_US = 3_000_000;
    /** ...or this fraction of the duration, whichever is larger. */
    private static final double END_TOLERANCE_FRACTION = 0.05;
    /** Longest single sleep while waiting for a frame's turn, so pause/seek stay responsive. */
    private static final long MAX_SLEEP_MS = 40;

    private final Listener listener;

    private MediaExtractor videoEx, audioEx;
    /** The network sources behind the extractors, if any; closed first on release. */
    private volatile Source videoSource, audioSource;
    private MediaCodec videoDec, audioDec;
    private AudioTrack track;
    private Surface surface;

    private Thread videoThread, audioThread;

    private final Object lock = new Object();
    private volatile boolean playing;
    private volatile boolean released;
    private volatile float speed = 1f;

    /** Media time of the audio track's frame 0, i.e. where the last seek landed. */
    private volatile long seekBaseUs;
    private volatile long seekTargetUs = -1;
    private volatile int seekGeneration;

    // --- temporary instrumentation ---
    volatile long dbgAudioBytes, dbgFramesRendered, dbgFramesDropped, dbgVideoReadStalls;
    volatile long dbgAudioWaits;
    /** Sum and count of how early/late each frame was against the clock, in microseconds. */
    volatile long dbgAheadSum, dbgAheadCount;
    volatile int dbgResyncs;
    private long lastResyncAt;

    private int sampleRate = 44100;
    private int channelCount = 2;
    /**
     * Presentation timestamp of the first audio frame written since the last flush. The clock is
     * anchored to this, not to the requested seek target: the extractor lands on the nearest sync
     * sample, so the two differ, and using the target puts the whole clock out by that difference.
     */
    private volatile long audioBasePtsUs = -1;
    /**
     * A seek has been issued and the audio loop has not re-anchored yet.
     *
     * <p>Until it has, {@link #positionUs()} is the seek target and nothing else. The old anchor
     * plus the old frame count reports where playback <em>used to be</em>, and the video loop
     * reaches the new position within milliseconds while audio still has its source to restart --
     * so every frame it decoded looked minutes ahead of the clock.
     */
    private volatile boolean anchorPending;
    /**
     * Latest HAL audio timestamp: how many frames had actually reached the DAC, and when.
     * {@link AudioTrack#getPlaybackHeadPosition()} counts frames handed to the mixer instead, so
     * it runs ahead of what is audible by the output latency -- which is exactly the offset that
     * shows up as video leading the sound.
     */
    private volatile long firstVideoPtsUs = -1, firstAudioPtsUs = -1;
    /**
     * Decoded audio before this timestamp is thrown away rather than played.
     *
     * <p>A seek can only land on a sync sample, and in fragmented MP4 that is a fragment
     * boundary -- measured up to 2.3 s away from the requested time. Playing from there means a
     * seek to the end of a SponsorBlock segment lands back <em>inside</em> it. Discarding the
     * decoded frames up to the real target makes the seek exact, which is what a seek is
     * supposed to be.
     */
    private volatile long trimBeforeUs;
    private volatile long tsFrames = -1;
    private volatile long tsNanos;
    private final AudioTimestamp audioTimestamp = new AudioTimestamp();
    private long durationUs;
    private volatile boolean videoDone, audioDone;
    private volatile int videoWidth, videoHeight;

    Engine(Listener listener) { this.listener = listener; }

    // ---- lifecycle ----------------------------------------------------------

    /**
     * One input stream: a local file, a remote googlevideo URL, or an extractor that was opened
     * and seeked ahead of time (see {@link #prepareAhead}).
     */
    static final class Track {
        final String source;
        final long bytes;
        final int bitrate;
        final MediaExtractor extractor;
        final Source net;
        /** The stream's first bytes from the disk head cache, or null. */
        final byte[] head;
        /** The fragment at the resume position from the disk head cache, or null. */
        final byte[] island;
        final long islandAt;

        private Track(String source, long bytes, int bitrate, MediaExtractor ex, Source net,
                      byte[] head, long islandAt, byte[] island) {
            this.source = source;
            this.bytes = bytes;
            this.bitrate = bitrate;
            this.extractor = ex;
            this.net = net;
            this.head = head;
            this.islandAt = islandAt;
            this.island = island;
        }

        static Track file(String path) { return new Track(path, 0, 0, null, null, null, -1, null); }

        static Track remote(Yt.Stream s, byte[] head, long islandAt, byte[] island) {
            return new Track(s.url, s.bytes, s.bitrate, null, null, head, islandAt, island);
        }

        static Track prepared(MediaExtractor ex, Source net) {
            return new Track(null, 0, 0, ex, net, null, -1, null);
        }
    }

    /**
     * Both extractors of a video, opened and seeked to its resume position before the tap.
     *
     * <p>The extractor's start-up is the network-bound half of click-to-playback: it parses the
     * container header, reads the first fragment's moof and glances at the second, and on a
     * resume jumps to the target fragment and glances again -- three to five round trips in
     * sequence, ~0.6-1.5 s on the phone. Doing it here while the feed is on screen leaves the
     * tap with only the codecs to bring up, ~100 ms. The sources are capped to a small
     * read-ahead meanwhile, so a prepared row costs ~2 MB of traffic and memory, not a
     * playing source's worth.
     */
    static final class Prepared {
        final MediaExtractor videoEx, audioEx;
        final Source video, audio;
        final long startAtUs;
        private final String videoUrl, audioUrl;

        private Prepared(Yt.Media m, MediaExtractor videoEx, Source video,
                         MediaExtractor audioEx, Source audio, long startAtUs) {
            this.videoEx = videoEx;
            this.video = video;
            this.audioEx = audioEx;
            this.audio = audio;
            this.startAtUs = startAtUs;
            this.videoUrl = m.video.url;
            this.audioUrl = m.audio.url;
        }

        /**
         * Hands the sources their full read-ahead and warms the spare sockets playback will
         * want; called when the player takes them.
         */
        void activate() {
            video.limitReadAhead(Integer.MAX_VALUE);
            audio.limitReadAhead(Integer.MAX_VALUE);
            Source.warmSpares(videoUrl, 2);
            Source.warmSpares(audioUrl, 1);
        }

        void release() {
            video.close();
            audio.close();
            try { videoEx.release(); } catch (RuntimeException ignored) { }
            try { audioEx.release(); } catch (RuntimeException ignored) { }
        }
    }

    /** Read-ahead kept by a prepared source until it is played. */
    private static final int PREPARED_AHEAD = 512 << 10;

    /** Opens and seeks both extractors in the background. Throws if either cannot be. */
    static Prepared prepareAhead(Yt.Media m, long startAtUs, Heads.Loaded disk)
            throws IOException {
        Source vs = new Source(m.video.url, m.video.bytes, m.video.bitrate, disk.videoHead,
                disk.videoIslandAt, disk.videoIsland);
        Source as = new Source(m.audio.url, m.audio.bytes, m.audio.bitrate, disk.audioHead,
                disk.audioIslandAt, disk.audioIsland);
        vs.limitReadAhead(PREPARED_AHEAD);
        as.limitReadAhead(PREPARED_AHEAD);
        MediaExtractor vex = null, aex = null;
        try {
            vex = openAndSeek(vs, "video/", startAtUs);
            aex = openAndSeek(as, "audio/", startAtUs);
            return new Prepared(m, vex, vs, aex, as, startAtUs);
        } catch (IOException | RuntimeException e) {
            vs.close();
            as.close();
            if (vex != null) vex.release();
            if (aex != null) aex.release();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static MediaExtractor openAndSeek(Source source, String mimePrefix, long startAtUs)
            throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(source);
        if (selectTrack(ex, mimePrefix) < 0) throw new IOException("no " + mimePrefix + " track");
        if (startAtUs > 0) {
            source.expectSeek();
            ex.seekTo(startAtUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
        return ex;
    }

    void open(final Track video, final Track audio,
              final Surface outputSurface, final long startAtUs) {
        this.surface = outputSurface;
        // Anchor the clock before anything is opened. positionUs() must report the resume
        // position until the first sample actually plays; when it reported 0 instead, backing
        // out of a video that had not started yet saved 0 over the real position.
        if (startAtUs > 0) {
            seekBaseUs = startAtUs;
            trimBeforeUs = startAtUs;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    prepare(video, audio, startAtUs);
                } catch (Exception e) {
                    fail("Could not start playback: " + e.getMessage());
                }
            }
        }, "engine-open").start();
    }

    private long videoDurationUs, audioDurationUs;
    private volatile Exception videoCodecError, audioCodecError;
    /** The tracks' formats, kept so a lost codec can be recreated (see {@link #recover}). */
    private volatile MediaFormat videoFormat, audioFormat;

    /**
     * Opens both tracks <em>in parallel</em>.
     *
     * <p>Each track needs an extractor (network round-trips for the header), a codec, and then a
     * seek to the resume position (more round-trips). Done one after the other that was 3.4 s
     * before the first frame; the two are independent, so they run on their own threads and the
     * cost becomes the slower of the two rather than the sum.
     */
    private void prepare(final Track video, final Track audio, final long startAtUs)
            throws IOException {
        Dbg.i("prepare video=" + (video.bytes >> 20) + "MB audio=" + (audio.bytes >> 20)
                + "MB startAt=" + (startAtUs / 1000) + "ms");
        final IOException[] failure = new IOException[2];
        Thread videoSetup = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    prepareVideo(video, startAtUs);
                } catch (Exception e) {
                    failure[0] = e instanceof IOException ? (IOException) e : new IOException(e);
                }
            }
        }, "prepare-video");
        Thread audioSetup = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    prepareAudio(audio, startAtUs);
                } catch (Exception e) {
                    failure[1] = e instanceof IOException ? (IOException) e : new IOException(e);
                }
            }
        }, "prepare-audio");
        videoSetup.start();
        audioSetup.start();
        try {
            videoSetup.join();
            audioSetup.join();
        } catch (InterruptedException e) {
            throw new IOException("interrupted while opening", e);
        }
        if (failure[0] != null) throw failure[0];
        if (failure[1] != null) throw failure[1];
        if (released) { closeQuietly(); return; }     // backed out while this was opening

        durationUs = Math.max(videoDurationUs, audioDurationUs);

        playing = true;
        track.play();

        videoThread = new Thread(new Runnable() {
            @Override public void run() { videoLoop(); }
        }, "engine-video");
        audioThread = new Thread(new Runnable() {
            @Override public void run() { audioLoop(); }
        }, "engine-audio");
        videoThread.start();
        audioThread.start();

        Dbg.i("playing, duration=" + (durationUs / 1000) + "ms");
        listener.onReady((int) (durationUs / 1000));
    }

    private void prepareVideo(Track video, long startAtUs) throws IOException {
        boolean prepared = video.extractor != null;
        if (prepared) {
            videoEx = video.extractor;
            videoSource = video.net;
        } else {
            videoEx = extractor(video, true);
        }
        int trackIndex = prepared ? findTrack(videoEx, "video/") : selectTrack(videoEx, "video/");
        if (trackIndex < 0) throw new IOException("no video track");
        MediaFormat format = videoEx.getTrackFormat(trackIndex);
        videoDurationUs = durationOf(format);
        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
        // Bringing up the codec and seeking the extractor need nothing from each other, and both
        // are slow -- one on the CPU, one on the network. Overlap them.
        videoFormat = format;
        Thread codec = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    videoDec = MediaCodec.createDecoderByType(
                            videoFormat.getString(MediaFormat.KEY_MIME));
                    videoDec.configure(videoFormat, surface, null, 0);
                    videoDec.start();
                    Dbg.i("video codec=" + videoDec.getName() + " "
                            + videoFormat.getString(MediaFormat.KEY_MIME)
                            + " " + videoWidth + "x" + videoHeight);
                } catch (Exception e) {
                    videoCodecError = e;
                }
            }
        }, "video-codec");
        codec.start();
        if (startAtUs > 0 && !prepared) {           // a prepared extractor sits there already
            expectSeek(videoSource);
            videoEx.seekTo(startAtUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
        try { codec.join(); } catch (InterruptedException e) { throw new IOException(e); }
        if (videoCodecError != null) throw new IOException("video codec: " + videoCodecError);
        Dbg.v("video ready");
    }

    private void prepareAudio(Track audio, long startAtUs) throws IOException {
        boolean prepared = audio.extractor != null;
        if (prepared) {
            audioEx = audio.extractor;
            audioSource = audio.net;
        } else {
            audioEx = extractor(audio, false);
        }
        int trackIndex = prepared ? findTrack(audioEx, "audio/") : selectTrack(audioEx, "audio/");
        if (trackIndex < 0) throw new IOException("no audio track");
        MediaFormat format = audioEx.getTrackFormat(trackIndex);
        audioDurationUs = durationOf(format);
        audioFormat = format;
        Thread codec = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    audioDec = MediaCodec.createDecoderByType(
                            audioFormat.getString(MediaFormat.KEY_MIME));
                    audioDec.configure(audioFormat, null, null, 0);
                    audioDec.start();
                    openAudioTrack(audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    Dbg.i("audio container says " + sampleRate + " Hz x" + channelCount);
                } catch (Exception e) {
                    audioCodecError = e;
                }
            }
        }, "audio-codec");
        codec.start();
        if (startAtUs > 0 && !prepared) {
            expectSeek(audioSource);
            audioEx.seekTo(startAtUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
        try { codec.join(); } catch (InterruptedException e) { throw new IOException(e); }
        if (audioCodecError != null) throw new IOException("audio codec: " + audioCodecError);
        Dbg.v("audio ready");
    }

    /**
     * Remote sources go through {@link Source}, never through a bare URL: the platform's HTTP
     * layer cannot satisfy googlevideo's bounded-Range requirement. Local files are opened
     * directly.
     */
    private MediaExtractor extractor(Track track, boolean video) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        if (track.source.startsWith("http")) {
            Source source = new Source(track.source, track.bytes, track.bitrate, track.head,
                    track.islandAt, track.island);
            if (video) videoSource = source; else audioSource = source;
            // Video peeks at moofs and restarts on seeks; audio mostly just seeks. Each wants
            // a socket of its own beyond the producer's.
            Source.warmSpares(track.source, video ? 2 : 1);
            ex.setDataSource(source);
        } else {
            ex.setDataSource(track.source);
        }
        return ex;
    }

    /** Tells a network source that the next far read is a seek, not a glance at a moof. */
    private static void expectSeek(Source s) {
        if (s != null) s.expectSeek();
    }

    private static int selectTrack(MediaExtractor ex, String mimePrefix) {
        int i = findTrack(ex, mimePrefix);
        if (i >= 0) ex.selectTrack(i);
        return i;
    }

    private static int findTrack(MediaExtractor ex, String mimePrefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private static long durationOf(MediaFormat f) {
        return f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
    }

    private void openAudioTrack(int rate, int channels) {
        sampleRate = rate;
        channelCount = channels;
        int channelMask = channels >= 2
                ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int minBuffer = AudioTrack.getMinBufferSize(
                rate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuffer * 4, 64 * 1024))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        applySpeed();
    }

    // ---- clock --------------------------------------------------------------

    /** A HAL snapshot older than this is not extrapolated from; it has gone stale. */
    private static final long SNAPSHOT_MAX_AGE_NS = 500_000_000L;

    /** Current media position in microseconds: where the sound coming out of the speaker is. */
    long positionUs() {
        AudioTrack t = track;
        long base = audioBasePtsUs >= 0 ? audioBasePtsUs : seekBaseUs;
        // Mid-seek the frame counter still belongs to the old anchor; the target is the only
        // honest answer until the first sample of the new one has been decoded.
        if (anchorPending) return seekBaseUs;
        if (t == null) return base;

        // Frames handed to the mixer. Always an upper bound: the DAC cannot have played more
        // than it was given.
        long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long frames = head;

        long snapshotFrames = tsFrames;
        if (playing && snapshotFrames >= 0) {
            long ageNs = System.nanoTime() - tsNanos;
            // getTimestamp() is not dependable moment to moment -- the platform itself logs
            // "retrograde timestamp" and "device stall time corrected" against it, and at startup
            // it can be plain wrong. Extrapolating from a bad or stale snapshot runs the clock
            // fast, and the video chases it. So only use a fresh snapshot, and never let it claim
            // more frames than were actually handed over.
            if (ageNs >= 0 && ageNs < SNAPSHOT_MAX_AGE_NS) {
                long extrapolated =
                        snapshotFrames + (long) (ageNs * 1e-9 * sampleRate * speed);
                if (extrapolated < frames) frames = extrapolated;
            }
        }
        if (frames < 0) frames = 0;
        return base + frames * 1_000_000L / sampleRate;
    }

    private long lastClockLogAt;

    /** Refreshes the HAL snapshot. Called from the audio thread only. */
    private void sampleAudioClock() {
        AudioTrack t = track;
        if (t == null) return;
        try {
            if (t.getTimestamp(audioTimestamp)) {
                tsFrames = audioTimestamp.framePosition;
                tsNanos = audioTimestamp.nanoTime;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastClockLogAt > 30_000) {     // was every 3 s: logcat spam while playing
                    lastClockLogAt = now;
                    long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    long headMs = head * 1000L / sampleRate;
                    long halMs = tsFrames * 1000L / sampleRate;
                    Dbg.v("audio clock: head=" + headMs + "ms hal=" + halMs
                            + "ms latency=" + (headMs - halMs) + "ms");
                }
            }
        } catch (RuntimeException ignored) {
            // Not supported on every route; the head-position fallback covers it.
        }
    }

    long durationUs() { return durationUs; }

    int videoWidth() { return videoWidth; }

    int videoHeight() { return videoHeight; }

    boolean isPlaying() { return playing; }

    // ---- transport ----------------------------------------------------------

    void setPlaying(boolean play) {
        if (released || playing == play) return;
        playing = play;
        AudioTrack t = track;
        if (t == null) return;
        if (play) t.play(); else t.pause();
        synchronized (lock) { lock.notifyAll(); }
    }

    /**
     * The activity's surface was recreated (a SurfaceView loses its surface while the app is in
     * the background). The video codec keeps decoding but draws into the new one from here on;
     * if the codec is gone too, the loop's recovery recreates it against the new surface.
     */
    void setSurface(Surface newSurface) {
        surface = newSurface;
        MediaCodec dec = videoDec;
        if (dec == null || newSurface == null) return;
        try {
            dec.setOutputSurface(newSurface);
        } catch (IllegalStateException | IllegalArgumentException e) {
            Dbg.i("surface handover failed (" + e.getMessage() + "), leaving it to recovery");
        }
    }

    void setSpeed(float value) {
        speed = Math.max(0.25f, Math.min(4f, value));
        applySpeed();
    }

    float speed() { return speed; }

    /** Swaps in a track matching the decoder's real output format, keeping playback going. */
    private void rebuildAudioTrack(int rate, int channels) {
        AudioTrack old = track;
        try {
            if (old != null) { old.pause(); old.flush(); old.release(); }
        } catch (RuntimeException ignored) { }
        track = null;
        openAudioTrack(rate, channels);
        audioBasePtsUs = -1;              // frame counter restarts, so the anchor must too
        anchorPending = true;
        tsFrames = -1;
        if (playing) track.play();
    }

    private void applySpeed() {
        AudioTrack t = track;
        if (t == null) return;
        try {
            // AudioTrack resamples and pitch-corrects on its own; nothing else has to change,
            // because the head-position clock is denominated in frames, not wall-clock seconds.
            t.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f));
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Some devices refuse extreme rates; keep playing at whatever was accepted.
        }
    }

    void seekTo(long positionUs) {
        if (released) return;
        long clamped = Math.max(0, Math.min(positionUs, durationUs));
        synchronized (lock) {
            seekTargetUs = clamped;
            // The clock moves now, not whenever the audio loop gets round to re-anchoring.
            seekBaseUs = clamped;
            audioBasePtsUs = -1;
            anchorPending = true;
            seekGeneration++;
            videoDone = false;
            audioDone = false;
            lock.notifyAll();
        }
    }

    void release() {
        if (released) return;
        long t0 = android.os.SystemClock.elapsedRealtime();
        released = true;
        playing = false;
        synchronized (lock) { lock.notifyAll(); }
        // Unblock the network first. A decode thread stalled inside a Source read would
        // otherwise sit out the whole join timeout -- three seconds of frozen UI on Back
        // during a stall -- and then have its extractor released underneath it.
        Source vs = videoSource, as = audioSource;
        if (vs != null) vs.close();
        if (as != null) as.close();
        join(videoThread);
        join(audioThread);
        closeQuietly();
        Dbg.i("engine released in " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
    }

    private static void join(Thread t) {
        if (t == null) return;
        try { t.join(1500); } catch (InterruptedException ignored) { }
    }

    private void closeQuietly() {
        try { if (videoDec != null) { videoDec.stop(); videoDec.release(); } } catch (Exception ignored) { }
        try { if (audioDec != null) { audioDec.stop(); audioDec.release(); } } catch (Exception ignored) { }
        try { if (track != null) { track.pause(); track.flush(); track.release(); } } catch (Exception ignored) { }
        try { if (videoEx != null) videoEx.release(); } catch (Exception ignored) { }
        try { if (audioEx != null) audioEx.release(); } catch (Exception ignored) { }
        videoDec = null; audioDec = null; track = null; videoEx = null; audioEx = null;
    }

    /** Whether a network source was refused with HTTP 403, whatever the failure said. */
    boolean forbidden() {
        Source v = videoSource, a = audioSource;
        return (v != null && v.forbidden) || (a != null && a.forbidden);
    }

    private void fail(final String message) {
        Dbg.i("engine failed: " + message);
        if (released) return;
        listener.onError(message);
    }

    // ---- decode loops -------------------------------------------------------

    /** Codec recreations allowed per track before giving up. */
    private static final int MAX_RECOVERIES = 3;

    /**
     * Android reclaims hardware codecs from apps in the background when another app needs one;
     * the codec is then in the Released state and every call on it throws. Seen on the phone
     * after a pause, a trip to the launcher and a return: "queueInputBuffer() is valid only at
     * Executing states; currently at Released state" put an error on screen. A lost codec is
     * recreated against the current surface and playback re-seeks to where it was.
     */
    private boolean recover(boolean video, Exception e) {
        if (released) return false;
        Dbg.i((video ? "video" : "audio") + " codec lost (" + e.getMessage() + "); recreating");
        try {
            MediaFormat format = video ? videoFormat : audioFormat;
            MediaCodec old = video ? videoDec : audioDec;
            try { if (old != null) old.release(); } catch (RuntimeException ignored) { }
            MediaCodec dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            dec.configure(format, video ? surface : null, null, 0);
            dec.start();
            if (video) videoDec = dec; else audioDec = dec;
            seekTo(positionUs());        // both loops re-seek their extractors and flush
            return true;
        } catch (Exception again) {
            Dbg.i("codec recreation failed: " + again);
            return false;
        }
    }

    private void videoLoop() {
        for (int recoveries = 0; !released; ) {
            try {
                videoLoopBody();
                return;
            } catch (Exception e) {
                if (released) return;
                if (recoveries++ >= MAX_RECOVERIES || !recover(true, e)) {
                    fail("Video decoding stopped: " + e.getMessage());
                    return;
                }
            }
        }
    }

    private void audioLoop() {
        for (int recoveries = 0; !released; ) {
            try {
                audioLoopBody();
                return;
            } catch (Exception e) {
                if (released) return;
                if (recoveries++ >= MAX_RECOVERIES || !recover(false, e)) {
                    fail("Audio decoding stopped: " + e.getMessage());
                    return;
                }
            }
        }
    }

    private void videoLoopBody() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int seenGeneration = seekGeneration;
        boolean inputDone = false;
        // A seek lands on the previous keyframe, so the frames from there to the target arrive
        // "behind" the clock by up to a GOP. That backlog is the seek, not a stall: resyncing on
        // it seeks to the same keyframe and starts the run-up over. Hold off for one cooldown
        // after every seek, including the initial one.
        lastResyncAt = android.os.SystemClock.elapsedRealtime();
        try {
            while (!released) {
                int generation = seekGeneration;
                if (generation != seenGeneration) {
                    expectSeek(videoSource);
                    videoEx.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    videoDec.flush();
                    videoEofQueued = false;
                    lastVideoSampleUs = seekTargetUs;   // so a read failure here is not "the end"
                    lastResyncAt = android.os.SystemClock.elapsedRealtime();
                    seenGeneration = generation;
                    inputDone = false;
                    videoDone = false;
                }

                // Fill every input buffer the codec will give us. Feeding only one buffer per
                // rendered frame leaves the decoder with no cushion, so any hiccup -- a window
                // reopen, a scheduling delay -- immediately turns into dropped frames.
                while (!inputDone && feed(videoDec, videoEx)) {
                    if (seekGeneration != seenGeneration) break;
                }
                if (!inputDone) inputDone = videoEofQueued;

                int index = videoDec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = videoDec.getOutputFormat();
                    videoWidth = f.getInteger(MediaFormat.KEY_WIDTH);
                    videoHeight = f.getInteger(MediaFormat.KEY_HEIGHT);
                    continue;
                }
                if (index < 0) continue;

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    videoDec.releaseOutputBuffer(index, false);
                    videoDone = true;
                    maybeEnded();
                    // Stay alive: a seek after the end restarts decoding from the new position.
                    // Leaving the loop here made the player dead once a video had finished.
                    while (!released && seekGeneration == seenGeneration) await(100);
                    continue;
                }
                if (firstVideoPtsUs < 0) {
                    firstVideoPtsUs = info.presentationTimeUs;
                    Dbg.i("first video frame at " + (firstVideoPtsUs / 1000) + "ms");
                }

                // While paused, hold the frame rather than dropping it, so the picture stays up.
                while (!playing && !released && seekGeneration == seenGeneration) await(50);
                if (released || seekGeneration != seenGeneration) {
                    videoDec.releaseOutputBuffer(index, false);
                    continue;
                }

                long aheadUs = info.presentationTimeUs - positionUs();
                // Too far ahead to hand over: wait for the sound to catch up rather than queue a
                // frame the surface will not hold, which is what lets the decoder run away.
                while (aheadUs > MAX_AHEAD_US && !released && seekGeneration == seenGeneration) {
                    await(Math.min(50, Math.max(1, (aheadUs - MAX_AHEAD_US) / 1000)));
                    aheadUs = info.presentationTimeUs - positionUs();
                }
                if (released || seekGeneration != seenGeneration) {
                    videoDec.releaseOutputBuffer(index, false);
                    continue;
                }
                if (aheadUs < -RESYNC_BEHIND_US
                        && android.os.SystemClock.elapsedRealtime() - lastResyncAt
                                > RESYNC_COOLDOWN_MS) {
                    // Hopelessly behind -- jump the extractor to where the sound is. Seeking to
                    // the previous sync sample keeps every frame from there on, so nothing is
                    // skipped; it just stops us decoding the backlog we would only throw away.
                    lastResyncAt = android.os.SystemClock.elapsedRealtime();
                    Dbg.i("video resync: was " + (-aheadUs / 1000) + "ms behind");
                    videoDec.releaseOutputBuffer(index, false);
                    expectSeek(videoSource);
                    videoEx.seekTo(positionUs(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    videoDec.flush();
                    videoEofQueued = false;
                    inputDone = false;
                    dbgResyncs++;
                    continue;
                }
                if (aheadUs < -DROP_BEHIND_US) {
                    dbgFramesDropped++;
                    videoDec.releaseOutputBuffer(index, false);
                    continue;
                }
                // Hand the frame to the surface with the wall-clock time it should appear at and
                // let SurfaceFlinger schedule it against vsync. Blocking here instead would stall
                // the decoder; the surface's own buffer queue provides the backpressure.
                long delayNs = (long) (Math.max(0, aheadUs) * 1000 / speed);
                dbgAheadSum += aheadUs;
                dbgAheadCount++;
                dbgFramesRendered++;
                videoDec.releaseOutputBuffer(index, System.nanoTime() + delayNs);
            }
        } finally {
            // nothing: the wrapper decides between recovery and failure
        }
    }

    private void audioLoopBody() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int seenGeneration = seekGeneration;
        boolean inputDone = false;
        try {
            while (!released) {
                int generation = seekGeneration;
                if (generation != seenGeneration) {
                    // PREVIOUS_SYNC, then trim: landing before the target and discarding the
                    // difference is exact, where CLOSEST_SYNC can land either side of it.
                    expectSeek(audioSource);
                    audioEx.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    audioDec.flush();
                    audioEofQueued = false;
                    lastAudioSampleUs = seekTargetUs;
                    trimBeforeUs = seekTargetUs;
                    track.pause();
                    track.flush();                 // resets the head counter to 0
                    seekBaseUs = seekTargetUs;     // provisional until the first sample arrives
                    audioBasePtsUs = -1;           // re-anchor from that sample's real timestamp
                    tsFrames = -1;                 // HAL snapshot refers to the old frame counter
                    if (playing) track.play();
                    seenGeneration = generation;
                    inputDone = false;
                    audioDone = false;
                }

                if (!playing) { await(40); continue; }
                while (!inputDone && feed(audioDec, audioEx)) {
                    if (seekGeneration != seenGeneration) break;
                }
                if (!inputDone) inputDone = audioEofQueued;

                int index = audioDec.dequeueOutputBuffer(info, 5000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // What the decoder actually emits wins over what the container claimed.
                    // HE-AAC in particular declares half the real rate, and playing 44.1 kHz PCM
                    // through a 22.05 kHz track runs the audio at half speed -- desync that grows
                    // without bound.
                    MediaFormat out = audioDec.getOutputFormat();
                    int rate = out.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? out.getInteger(MediaFormat.KEY_SAMPLE_RATE) : sampleRate;
                    int channels = out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? out.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channelCount;
                    if (rate != sampleRate || channels != channelCount) {
                        Dbg.i("audio decoder emits " + rate + " Hz x" + channels
                                + " (container said " + sampleRate + " Hz x" + channelCount
                                + ") - rebuilding the track");
                        rebuildAudioTrack(rate, channels);
                    }
                    continue;
                }
                if (index < 0) continue;

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    audioDec.releaseOutputBuffer(index, false);
                    audioDone = true;
                    maybeEnded();
                    while (!released && seekGeneration == seenGeneration) await(100);
                    continue;
                }

                // Everything before the seek target is decoded but not played.
                if (info.size > 0 && info.presentationTimeUs + 1000 < trimBeforeUs) {
                    audioDec.releaseOutputBuffer(index, false);
                    continue;
                }
                trimBeforeUs = 0;

                if (info.size > 0) {
                    if (audioBasePtsUs < 0) {
                        audioBasePtsUs = info.presentationTimeUs;
                        anchorPending = false;
                        if (firstAudioPtsUs < 0) {
                            firstAudioPtsUs = info.presentationTimeUs;
                            Dbg.i("first audio frame at " + (firstAudioPtsUs / 1000) + "ms");
                        }
                        Dbg.v("audio clock anchored at " + (audioBasePtsUs / 1000) + "ms");
                    }
                    ByteBuffer out = audioDec.getOutputBuffer(index);
                    if (out != null) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        track.write(out, info.size, AudioTrack.WRITE_BLOCKING);
                        dbgAudioWaits += android.os.SystemClock.elapsedRealtime() - t0;
                        dbgAudioBytes += info.size;
                        sampleAudioClock();
                    }
                }
                audioDec.releaseOutputBuffer(index, false);
            }
        } finally {
            // nothing: the wrapper decides between recovery and failure
        }
    }

    private volatile boolean videoEofQueued, audioEofQueued;
    /**
     * Timestamp of the newest sample queued on each track, used to tell a real end of stream from
     * an I/O failure. Kept per track: video is fed as hard as the codec will take it, so it
     * reaches the end well before audio does, and judging the video's EOF by the audio's position
     * reported the normal end of every video as a read failure.
     */
    private volatile long lastVideoSampleUs, lastAudioSampleUs;

    /**
     * Pumps one input buffer. Returns true when the codec might take another straight away, so
     * callers can keep feeding until it says no -- the timeout is 0 for exactly that reason.
     */
    private boolean feed(MediaCodec dec, MediaExtractor ex) {
        int index = dec.dequeueInputBuffer(0);
        if (index < 0) return false;
        ByteBuffer in = dec.getInputBuffer(index);
        if (in == null) return false;
        int size = ex.readSampleData(in, 0);
        if (size < 0) {
            // readSampleData returns -1 both at the real end and on an I/O failure. Ending the
            // stream on an I/O failure silently kills the track, so only trust it near the end.
            long lastUs = dec == videoDec ? lastVideoSampleUs : lastAudioSampleUs;
            long tolerance = Math.max(END_TOLERANCE_US,
                    (long) (durationUs * END_TOLERANCE_FRACTION));
            boolean atEnd = durationUs <= 0 || lastUs >= durationUs - tolerance;
            if (!atEnd) {
                dec.queueInputBuffer(index, 0, 0, 0, 0);
                throw new IllegalStateException("stream read failed at "
                        + (lastUs / 1000) + " ms of " + (durationUs / 1000) + " ms");
            }
            Dbg.i((dec == videoDec ? "video" : "audio") + " track ended at " + (lastUs / 1000)
                    + "ms of " + (durationUs / 1000) + "ms");
            dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            if (dec == videoDec) videoEofQueued = true; else audioEofQueued = true;
            return false;
        }
        if (dec == videoDec) lastVideoSampleUs = ex.getSampleTime();
        else lastAudioSampleUs = ex.getSampleTime();
        dec.queueInputBuffer(index, 0, size, ex.getSampleTime(), 0);
        ex.advance();
        return true;
    }

    private void maybeEnded() {
        if (videoDone && audioDone && !released) listener.onEnded();
    }

    private void await(long millis) {
        synchronized (lock) {
            try { lock.wait(millis); } catch (InterruptedException ignored) { }
        }
    }
}
