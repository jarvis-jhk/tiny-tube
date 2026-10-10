package org.tinytube.app;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * Feeds a googlevideo URL to {@link android.media.MediaExtractor}, with read-ahead.
 *
 * <p><b>Why the HTTP is done here.</b> googlevideo refuses a request unless it carries a
 * <em>bounded</em> byte range, and refuses ranges that ask for too much at once. Measured:
 *
 * <pre>
 *   GET, no Range header            -> 403
 *   Range: bytes=0-                 -> 403   (open-ended)
 *   Range: bytes=0-99999            -> 206
 *   Range covering ~40 s of video   -> 206
 *   Range covering ~55 s of video   -> 403   (same URL, larger window)
 * </pre>
 *
 * <p>The platform's {@code MediaHTTPConnection} only ever issues the first two forms, so
 * {@code MediaExtractor.setDataSource(url)} always fails with "Failed to instantiate extractor".
 * The upper limit is not a clean function of size or duration -- a 251 MB video stream accepted
 * 20 MB but not 28 MB, a 15 MB audio stream accepted 1.02 MB but not 1.25 MB -- so a 403 is
 * answered by <em>waiting</em> and then asking for half as much (see {@link #openRange}), which
 * self-corrects whatever the real rule is.
 *
 * <p><b>Why the buffer.</b> {@code MediaExtractor} pulls bytes only at the moment it needs them.
 * Serving those reads straight from the socket means any dip in throughput starves the decoder
 * immediately, which is what dropped frames look like. So a producer thread runs ahead of the
 * reader, filling a circular buffer worth several seconds of media; the reader only blocks when
 * the buffer is genuinely empty. This is the one thing ExoPlayer does that a naive
 * MediaDataSource does not.
 *
 * <p><b>Why the peeks.</b> Parsing fragmented MP4 the extractor reads a fragment's moof, then
 * glances at the <em>next</em> fragment's moof -- a fragment ahead, 1-3.5 MB at 1080p -- and
 * comes back for the mdat. Restarting the producer for that 16 KB glance abandoned the
 * connection and the ring (traced: five fresh connections on one start); waiting for it meant
 * downloading the whole fragment before the first frame. A peek fetches a few dozen KB there on
 * a second, pooled connection and leaves the ring alone.
 */
final class Source extends MediaDataSource {

    private static final long WINDOW_MIN = 128 << 10;
    /**
     * Largest single request. The old rule of a sixteenth of the file (a 15 MB audio stream took
     * 1.02 MB but not 1.25 MB) was measured on the pre-VISIONOS URLs; the current ones serve
     * 8 MB windows of a 2 MB file or a 250 MB one alike, and yt-dlp fetches them in 10 MB
     * chunks. Fetching a 2 MB audio stream in twelve 166 KB requests cost twelve round trips.
     * Should a fallback client's URL still object, the 403 back-off halves the cap.
     */
    private static final long WINDOW_MAX = 8 << 20;
    /** Successful opens needed before the cap is allowed to grow back after a 403. */
    private static final int GROW_AFTER = 8;
    /** Never shrink below this: tiny requests are latency-bound and cannot sustain playback. */
    private static final long WINDOW_FLOOR = 512 << 10;
    /**
     * Five opens, ~6 s of back-off between them. It was eight, ~100 s -- which on a visitor
     * refused past its first minute (see {@link Yt#serving}) froze playback for a minute and a
     * half before the error that lets a new identity be tried, and VISIONOS URLs have not shown
     * the burst limit the back-off was written for.
     */
    private static final int OPEN_ATTEMPTS = 5;
    private static final long BACKOFF_START_MS = 400;

    /** Seconds of media held in memory ahead of the reader. */
    private static final int BUFFER_SECONDS = 12;
    private static final int BUFFER_MIN = 512 << 10;
    private static final int BUFFER_MAX = 20 << 20;
    /**
     * Bytes guaranteed to be kept behind the reader. MediaExtractor steps backwards by megabytes
     * in fragmented MP4 (it re-reads a moof before its mdat), and a step back past what the ring
     * still holds forces a restart that throws the whole buffer away and refetches -- which was
     * ~14% of frames dropped at 1080p60. Measured on the phone, those steps reach ~4.3 MB behind
     * the furthest byte read, so the floor is set above that. Beyond it, old bytes are kept until
     * the producer actually needs the room.
     */
    private static final int HISTORY = 6 << 20;
    /**
     * How far ahead of the reader to fetch immediately after opening or seeking: a few seconds
     * of media, so the MP4 header and the first fragment arrive in one request rather than
     * three -- each extra round trip is ~150 ms of the time between tapping a video and seeing
     * it. Scaled by bitrate on purpose: a 2 MB audio warm-up next to the 2 MB video warm-up
     * halved the video's share of the link exactly when the first frame was waiting on it.
     */
    private static final int WARMUP_SECONDS = 6;
    private static final int WARMUP_MIN = 512 << 10;
    private static final int WARMUP_MAX = 2 << 20;
    /**
     * What a restart costs: the connection is abandoned (so it cannot go back to the pool) and
     * another is opened, ~150-250 ms on the phone. A forward jump the open connection would
     * reach in less than this waits for it instead -- and keeps the buffered bytes, which the
     * extractor's next backwards step then finds in history rather than forcing yet another
     * request. Jumps further than this are glances (peeks) or real seeks.
     */
    private static final long RESTART_COST_MS = 250;
    /** Throughput assumed until measured: Wi-Fi, but not the 11 MB/s a good day gives. */
    private static final long DEFAULT_BYTES_PER_MS = 6 << 10;
    private static final long WAIT_GAP_MIN = 256 << 10;
    private static final long WAIT_GAP_MAX = 4 << 20;
    /** An announced seek that lands further than this beyond the buffered data restarts. */
    private static final long SEEK_SLACK = 64 << 10;
    /** A glance is a moof header: a few KB. The transfer is on the clock, so keep it small. */
    private static final int PEEK_SIZE = 64 << 10;

    private final String url;
    private final long size;
    /**
     * The ring. Allocated at the warm-up size and grown on demand up to {@link #capacity}, so
     * a source prepared ahead of a tap and then left waiting does not sit on 10 MB of empty
     * array per stream.
     */
    private byte[] ring;
    private final int capacity;

    private final Object lock = new Object();

    /** File offset of the oldest byte held in the ring. */
    private long base;
    /** Number of valid bytes from {@link #base} onward. */
    private int length;
    /** Index in {@code ring} corresponding to {@link #base}. */
    private int head;
    /** Where the reader last asked for data; the producer keeps ahead of this. */
    private long readerAt;
    /** Bumped when the reader jumps, telling the producer to restart elsewhere. */
    private int epoch;
    /** Current read-ahead target; grows with how much the reader has actually consumed. */
    private int target;
    /** Read-ahead right after an open or seek; see {@link #WARMUP_SECONDS}. */
    private final int warmup;
    /** Ceiling on the target while a prepared-ahead source is waiting for its tap. */
    private int targetCap = Integer.MAX_VALUE;
    /** Bytes handed to the reader since the last jump. */
    private long consumed;
    /** Bytes received and nanoseconds spent blocked in socket reads, for the throughput estimate. */
    private long rateBytes, rateNanos;

    /** Per-request ceiling: starts at the maximum, halves on a 403, grows back on success. */
    private long cap = WINDOW_MAX;
    private int openStreak;
    private boolean closed;
    private IOException failure;
    /** googlevideo refused this URL; MediaExtractor reports that only as a failed read. */
    volatile boolean forbidden;
    private boolean eof;
    private Thread producer;
    /** The connection the producer is reading from, so {@link #close()} can cut a blocked read. */
    private volatile HttpURLConnection active;
    /** Same for a peek in progress. */
    private volatile HttpURLConnection activePeek;

    private byte[] peekBuf;
    private long peekBase = -1;
    private int peekLength;
    /**
     * A stretch of the stream stored ahead of time somewhere other than its start: the fragment
     * at a resume position (see {@link Heads}). Served like a peek, but it is never replaced.
     */
    private byte[] island;
    private long islandAt = -1;
    /** Set by the engine before it seeks: the next far jump is real, restart rather than peek. */
    private volatile boolean seekExpected;

    Source(String url, long size, int bitrate) { this(url, size, bitrate, null, -1, null); }

    Source(String url, long size, int bitrate, byte[] head) {
        this(url, size, bitrate, head, -1, null);
    }

    /**
     * @param head the stream's first bytes, if the disk head cache has them. They seed the ring,
     *             so the extractor parses the container and decodes the first fragment out of
     *             memory while the producer fetches what follows: local start, streamed rest,
     *             and the extractor never knows the difference.
     */
    Source(String url, long size, int bitrate, byte[] head, long islandAt, byte[] island) {
        this.url = url;
        if (island != null && island.length > 0 && islandAt >= 0) {
            this.island = island;
            this.islandAt = islandAt;
        }
        this.size = size;
        long bytesPerSecond = Math.max(1, bitrate / 8L);
        this.capacity = (int) Math.max(BUFFER_MIN,
                Math.min(BUFFER_MAX, Math.min(size, bytesPerSecond * BUFFER_SECONDS + HISTORY)));
        this.warmup = (int) Math.max(WARMUP_MIN,
                Math.min(WARMUP_MAX, bytesPerSecond * WARMUP_SECONDS));
        this.target = warmup;
        int seed = head == null ? 0 : head.length;
        this.ring = new byte[Math.min(capacity, Math.max(warmup, seed))];
        if (seed > 0) {
            length = (int) Math.min(Math.min(seed, ring.length), size);
            System.arraycopy(head, 0, ring, 0, length);
        }
    }

    /**
     * Opens {@code n} connections to the stream's host with a 1 KB request each and leaves them
     * in HttpURLConnection's keep-alive pool, in the background. A restart after a seek and a
     * peek at a moof each need a socket of their own, and a fresh TLS handshake to googlevideo
     * measured 150-500 ms on the phone against ~50-100 ms for a pooled one.
     */
    static void warmSpares(final String url, int n) {
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        HttpURLConnection c = Net.open(url, Net.UA_WEB, "Range", "bytes=0-1023");
                        if (c.getResponseCode() / 100 == 2) Net.drain(c.getInputStream());
                        else c.disconnect();
                    } catch (IOException ignored) { }
                }
            }, "spare");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Caps the read-ahead, for a source prepared ahead of a tap: enough to start instantly,
     * not the seconds of media a playing source keeps. {@code Integer.MAX_VALUE} lifts it.
     */
    void limitReadAhead(int bytes) {
        synchronized (lock) {
            targetCap = bytes;
            target = Math.min(targetCap, Math.max(warmup, (int) Math.min(capacity, consumed)));
            lock.notifyAll();
        }
    }

    /** The engine is about to seek: the next far jump restarts the producer instead of peeking. */
    void expectSeek() { seekExpected = true; }

    /** How far ahead of the buffered data a jump may land and still be worth waiting for. */
    private long waitGap() {
        long bytesPerMs = rateNanos > 0 ? rateBytes * 1_000_000L / rateNanos : DEFAULT_BYTES_PER_MS;
        return Math.max(WAIT_GAP_MIN, Math.min(WAIT_GAP_MAX, bytesPerMs * RESTART_COST_MS));
    }

    @Override
    public long getSize() { return size; }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int amount) throws IOException {
        if (position >= size) return -1;
        int wanted = (int) Math.min(amount, size - position);
        if (wanted <= 0) return -1;

        boolean glance = false;
        synchronized (lock) {
            if (closed) return -1;
            if (peekBase >= 0 && position >= peekBase && position < peekBase + peekLength) {
                return fromPeek(position, buffer, offset, wanted);
            }
            if (island != null && position >= islandAt && position < islandAt + island.length) {
                int n = (int) Math.min(wanted, islandAt + island.length - position);
                System.arraycopy(island, (int) (position - islandAt), buffer, offset, n);
                return n;
            }
            long end = base + length;
            boolean far = position < base || position > end + waitGap();
            // An announced seek restarts for any real jump, even one the wait rule would sit
            // out: the audio extractor's jump to the resume point is a megabyte, and waiting
            // fetched that megabyte in sequential windows. Only a jump consumes the
            // announcement -- the extractor re-reads nearby index data first, and clearing on
            // that sent the seek itself down the glance path.
            boolean seek = seekExpected && (position < base || position > end + SEEK_SLACK);
            if (far && !seek && position != peekBase + peekLength) {
                glance = true;         // most likely a look at a moof; the ring stays as it is
            } else {
                readerAt = position;
                if (seek || far) {
                    seekExpected = false;
                    restart(position); // a real seek, or reading on past a peek
                } else if (producer == null) {
                    startProducer();
                }
            }
        }
        if (glance) {
            if (peek(position, wanted)) {
                synchronized (lock) { return fromPeek(position, buffer, offset, wanted); }
            }
            synchronized (lock) { readerAt = position; restart(position); }   // fall back
        }

        synchronized (lock) {
            int retries = 0;
            while (true) {
                if (position < base + length) break;
                if (eof && position >= base + length) {
                    Dbg.v("source: EOF for reader at " + position + " of " + size
                            + " (buffered " + base + ".." + (base + length) + ")");
                    return -1;
                }
                if (closed) return -1;
                if (failure == null && forbidden && producer == null) {
                    throw new IOException("HTTP 403 reading " + Net.shortUrl(url));
                }
                if (failure != null) {
                    // A forbidden URL does not improve when the same Source starts it again.
                    // Let the download retry resolve a fresh media URL instead. Other failures
                    // may be transient, so give playback's extractor a few local restarts.
                    IOException e = failure;
                    failure = null;
                    if (e.getMessage() != null && e.getMessage().startsWith("HTTP 403 reading "))
                        throw e;
                    if (++retries > 3) throw e;
                    restart(position);
                }
                try { lock.wait(200); } catch (InterruptedException e) { return -1; }
            }

            int available = (int) Math.min(wanted, base + length - position);
            int from = (int) ((head + (position - base)) % ring.length);
            int first = Math.min(available, ring.length - from);
            System.arraycopy(ring, from, buffer, offset, first);
            if (first < available) {
                System.arraycopy(ring, 0, buffer, offset + first, available - first);
            }
            // Read-ahead is earned by consumption, not by call count: MediaExtractor issues
            // hundreds of 2 KB reads while parsing the header, and growing per call would balloon
            // the buffer to its maximum before a single frame is decoded.
            consumed += available;
            target = Math.min(targetCap, Math.max(warmup, (int) Math.min(capacity, consumed)));
            // Nothing is discarded here on purpose -- see the producer, which drops old bytes
            // only when it needs the space.
            lock.notifyAll();
            return available;
        }
    }

    private int fromPeek(long position, byte[] buffer, int offset, int wanted) {
        int n = (int) Math.min(wanted, peekBase + peekLength - position);
        System.arraycopy(peekBuf, (int) (position - peekBase), buffer, offset, n);
        return n;
    }

    /** Fetches a small window at {@code position} on a second connection. False on failure. */
    private boolean peek(long position, int wanted) {
        int len = (int) Math.min(size - position, Math.max(wanted, PEEK_SIZE));
        long t0 = android.os.SystemClock.elapsedRealtime();
        HttpURLConnection c = null;
        try {
            c = Net.open(url, Net.UA_WEB, "Range", "bytes=" + position + "-" + (position + len - 1));
            activePeek = c;
            int code = c.getResponseCode();
            if (code != 206 && code != 200) { c.disconnect(); return false; }
            byte[] data = Net.drain(c.getInputStream());      // to EOF, so the socket is pooled
            Dbg.v("peek " + position + "+" + (len >> 10) + "KB in "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
            synchronized (lock) {
                peekBuf = data;
                peekBase = position;
                peekLength = data.length;
            }
            return data.length > 0;
        } catch (IOException e) {
            Dbg.v("peek " + position + " failed: " + e);
            if (c != null) c.disconnect();
            return false;
        } finally {
            activePeek = null;
        }
    }

    /** Drops buffered bytes the reader has moved past, freeing room for read-ahead. */
    private void discardBefore(long offset) {
        if (offset <= base) return;
        int drop = (int) Math.min(length, offset - base);
        base += drop;
        head = (head + drop) % ring.length;
        length -= drop;
    }

    /** Reallocates the ring, keeping its contents. Called with the lock held. */
    private void grow(int newSize) {
        byte[] bigger = new byte[newSize];
        int first = Math.min(length, ring.length - head);
        System.arraycopy(ring, head, bigger, 0, first);
        if (first < length) System.arraycopy(ring, 0, bigger, first, length - first);
        ring = bigger;
        head = 0;
    }

    private void restart(long position) {
        if (length > 0) {
            Dbg.v("source: restart at " + position + ", discarding " + (length >> 10)
                    + "KB (had " + base + ".." + (base + length) + ")");
        }
        epoch++;
        base = position;
        head = 0;
        length = 0;
        target = Math.min(targetCap, warmup);
        consumed = 0;
        eof = false;
        failure = null;
        lock.notifyAll();
        startProducer();
    }

    private void startProducer() {
        // A refused URL stays refused: starting over only asked again, ~85 times in 14 s while
        // the buffered video played out on the tablet.
        if (producer != null || closed || forbidden) return;
        producer = new Thread(new Runnable() {
            @Override public void run() { produce(); }
        }, "source-fill");
        producer.setDaemon(true);
        producer.start();
    }

    /** Fetches forward into the ring, staying ahead of the reader. */
    private void produce() {
        int myEpoch;
        long fetchAt;
        synchronized (lock) { myEpoch = epoch; fetchAt = base + length; }

        HttpURLConnection connection = null;
        InputStream in = null;
        long streamEnd = -1;
        byte[] chunk = new byte[64 << 10];

        try {
            while (true) {
                int space;
                synchronized (lock) {
                    if (closed) return;
                    if (epoch != myEpoch) {            // reader jumped; follow it
                        myEpoch = epoch;
                        fetchAt = base + length;
                        closeQuietly(in, connection, false);
                        in = null; connection = null; streamEnd = -1;
                    }
                    if (fetchAt >= size) { eof = true; lock.notifyAll(); return; }
                    // Make room only now that it is actually wanted: first by growing the ring
                    // up to its capacity, then by dropping bytes the reader is well past. If
                    // keeping HISTORY would leave less than a chunk free, eat into it rather
                    // than stall: the reader would wait on a producer waiting on the reader.
                    if (ring.length - length < chunk.length) {
                        if (ring.length < capacity) {
                            grow(Math.min(capacity, Math.max(ring.length * 2, length + chunk.length)));
                        } else {
                            long needed = base + (chunk.length - (ring.length - length));
                            discardBefore(Math.min(readerAt, Math.max(readerAt - HISTORY, needed)));
                        }
                    }
                    space = ring.length - length;
                    long ahead = base + length - readerAt;
                    // Stop once far enough in front of the reader. This is the pacing that keeps
                    // us inside googlevideo's delivery budget: without it the producer would pull
                    // at line speed and get throttled.
                    if (space < chunk.length || ahead >= target) {
                        try { lock.wait(100); } catch (InterruptedException e) { return; }
                        continue;
                    }
                }

                if (in == null || fetchAt >= streamEnd) {
                    closeQuietly(in, connection, streamEnd >= 0 && fetchAt < streamEnd);
                    long[] range = new long[1];
                    connection = openRange(fetchAt, range);
                    in = connection.getInputStream();
                    streamEnd = range[0];
                }

                int toRead = (int) Math.min(chunk.length, Math.min(space, streamEnd - fetchAt));
                long t0 = System.nanoTime();
                int n = in.read(chunk, 0, toRead);
                long spent = System.nanoTime() - t0;
                if (n < 0) { streamEnd = fetchAt; continue; }   // window ended early; reopen

                synchronized (lock) {
                    if (closed) return;
                    rateBytes += n;                             // time blocked on the socket only
                    rateNanos += spent;
                    if (epoch != myEpoch) continue;             // stale bytes, discard
                    int at = (int) ((head + length) % ring.length);
                    int first = Math.min(n, ring.length - at);
                    System.arraycopy(chunk, 0, ring, at, first);
                    if (first < n) System.arraycopy(chunk, first, ring, 0, n - first);
                    length += n;
                    fetchAt += n;
                    lock.notifyAll();
                }
            }
        } catch (IOException e) {
            // One line: a dropped connection (the screen went off mid-transfer, Wi-Fi gone) is
            // routine, and its 25-line stack trace said nothing the exception does not.
            Dbg.i("producer stopped at " + fetchAt + ": " + e);
            if (e.getMessage() != null && e.getMessage().startsWith("HTTP 403")) forbidden = true;
            synchronized (lock) {
                failure = e;
                lock.notifyAll();
            }
        } finally {
            closeQuietly(in, connection, true);
            synchronized (lock) { producer = null; }
        }
    }

    /**
     * Opens one bounded range starting at {@code from}; {@code endOut[0]} receives its end.
     *
     * <p>googlevideo meters delivery with what behaves like a token bucket: a burst of roughly
     * 24 MB is served at full speed, after which requests are refused until it refills at about
     * playback rate. Measured by fetching 2 MB chunks back to back -- the first twelve returned
     * 206 at 6 MB/s, the thirteenth and fourteenth returned 403 -- and the same pattern appears
     * whether the range is sent as a {@code Range} header or as googlevideo's own {@code &range=}
     * query parameter, so the request form is not what matters.
     *
     * <p>So a 403 here means "too much, too fast", and the answer is to <em>wait</em>. Retrying
     * immediately with a smaller window, which is what this used to do, drains the bucket further
     * and collapses the window to uselessly small requests.
     */
    private HttpURLConnection openRange(long from, long[] endOut) throws IOException {
        IOException last = null;
        long backoff = BACKOFF_START_MS;
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            long currentWindow;
            synchronized (lock) {
                // The read-ahead target: the warm-up right after an open or seek, so it is one
                // request and a window abandoned by a seek wastes little; growing with
                // consumption to the cap, which only a 403 lowers, so a small file comes in one
                // go and a long one in few requests.
                currentWindow = Math.max(WINDOW_MIN, Math.min(cap, target));
            }
            long end = Math.min(size, from + currentWindow) - 1;
            HttpURLConnection c = Net.open(url, Net.UA_WEB, "Range", "bytes=" + from + "-" + end);
            active = c;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            int code = c.getResponseCode();
            Dbg.v("range " + from + "+" + ((end - from + 1) >> 10) + "KB of " + (size >> 20)
                    + "MB -> " + code + " in " + (android.os.SystemClock.elapsedRealtime()
                    - startedAt) + "ms" + (attempt > 0 ? " (retry " + attempt + ")" : ""));
            // A successful status alone does not mean these are the bytes requested. If a
            // server or intermediary ignores Range (200), or returns a different 206 range,
            // the downloader would append those bytes and mark a full-sized corrupt file done.
            // Playback may only discover that much later, at the first damaged fragment.
            String contentRange = c.getHeaderField("Content-Range");
            String expectedRange = "bytes " + from + "-" + end + "/" + size;
            if (code == 206 && expectedRange.equals(contentRange)) {
                synchronized (lock) {
                    // Recover from an earlier back-off so one 403 does not shrink the cap
                    // permanently and strand playback on latency-bound requests.
                    if (cap < WINDOW_MAX && ++openStreak >= GROW_AFTER) {
                        cap = Math.min(WINDOW_MAX, cap * 2);
                        openStreak = 0;
                    }
                }
                endOut[0] = end + 1;
                return c;
            }
            c.disconnect();
            last = new IOException(code == 206 || code == 200
                    ? "invalid media range response" : "HTTP " + code + " reading " + Net.shortUrl(url));
            if (code != 403 || attempt == OPEN_ATTEMPTS - 1) break;   // no sleep after the last

            synchronized (lock) {
                if (closed) break;
                // Ask for a bit less as well, but only down to a size that can still keep up.
                cap = Math.max(WINDOW_FLOOR, cap / 2);
                openStreak = 0;
                try { lock.wait(backoff); } catch (InterruptedException e) { break; }
            }
            backoff *= 2;
        }
        throw last != null ? last : new IOException("could not open " + Net.shortUrl(url));
    }

    /**
     * A socket only goes back to HttpURLConnection's keep-alive pool once its body has been read
     * to EOF; pooling a partially-read response corrupts the next request on it, which showed up
     * as {@code NuMediaExtractor: read failed with error -1004} and a silent audio track.
     */
    private static void closeQuietly(InputStream in, HttpURLConnection c, boolean abandoned) {
        if (in != null) {
            if (abandoned) {
                try { c.disconnect(); } catch (RuntimeException ignored) { }
            }
            try { in.close(); } catch (IOException ignored) { }
        } else if (c != null) {
            try { c.disconnect(); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * Wakes the reader and the producer, and cuts the socket a stalled producer may be blocked
     * on -- with the network down, a read only ends when its timeout does, and the caller
     * (Engine.release, on the main thread) cannot wait 25 s for that.
     */
    @Override
    public void close() {
        Thread t;
        synchronized (lock) {
            closed = true;
            t = producer;
            lock.notifyAll();
        }
        HttpURLConnection c = active;
        if (c != null) {
            try { c.disconnect(); } catch (RuntimeException ignored) { }
        }
        c = activePeek;
        if (c != null) {
            try { c.disconnect(); } catch (RuntimeException ignored) { }
        }
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
    }
}
