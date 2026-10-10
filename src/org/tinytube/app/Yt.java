package org.tinytube.app;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * YouTube extraction. Deliberately mirrors PipePipe's InnerTube layer so upstream fixes
 * port across as small diffs:
 *
 * <ul>
 *   <li>client constants below are copied verbatim from
 *       {@code PipePipeExtractor/.../services/youtube/ClientsConstants.java}</li>
 *   <li>the player request shape follows {@code YoutubeStreamExtractor#fetchPage}</li>
 * </ul>
 *
 * <p>What is deliberately <em>not</em> ported is PipePipe's SABR session protocol. The
 * {@code VISIONOS} client (and the {@code IOS} / {@code ANDROID} fallbacks) return
 * {@code adaptiveFormats} carrying plain {@code url} fields, so no signature or
 * {@code n}-parameter deciphering -- and therefore no JS engine -- is needed. If that changes,
 * the fallback is the one PipePipe itself uses: run YouTube's player JS in the system WebView,
 * which costs no APK size either.
 */
final class Yt {
    private Yt() {}

    private static final String PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String SEARCH_URL =
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";
    private static final String RSS_URL =
            "https://www.youtube.com/feeds/videos.xml?channel_id=";

    /** The "Videos" tab of a channel. Excludes Shorts, which live under their own tab. */
    private static final String VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA";

    // ---- client constants (keep in sync with PipePipe ClientsConstants) -----

    private static final String WEB_CLIENT_ID = "1";
    private static final String WEB_CLIENT_NAME = "WEB";
    private static final String WEB_CLIENT_VERSION = "2.20250122.04.00";

    private static final String IOS_CLIENT_ID = "5";
    private static final String IOS_CLIENT_NAME = "IOS";
    private static final String IOS_CLIENT_VERSION = "20.03.02";
    private static final String IOS_DEVICE_MODEL = "iPhone16,2";
    private static final String IOS_OS_VERSION = "18.2.1.22C161";
    private static final String IOS_USER_AGENT_VERSION = "18_2_1";
    private static final String IOS_USER_AGENT = "com.google.ios.youtube/" + IOS_CLIENT_VERSION
            + " (" + IOS_DEVICE_MODEL + "; U; CPU iOS " + IOS_USER_AGENT_VERSION
            + " like Mac OS X;)";

    /**
     * VISIONOS (Apple Vision Pro). The important client: it is the one yt-dlp settles on, it
     * needs no JS player ({@code REQUIRE_JS_PLAYER: False}) and -- unlike every other client
     * tested -- its URLs serve the <em>whole</em> file without a PO token. Constants copied from
     * yt-dlp's {@code extractor/youtube/_base.py}; note the Safari user agent and
     * {@code RealityDevice17,1}, both of which matter (older values give LOGIN_REQUIRED).
     *
     * <p>Caveat carried over from yt-dlp's own comment: "Made for kids" videos are not available
     * with this client, which is why IOS and ANDROID remain as fallbacks.
     */
    private static final String VISIONOS_CLIENT_ID = "101";
    private static final String VISIONOS_CLIENT_NAME = "VISIONOS";
    private static final String VISIONOS_CLIENT_VERSION = "1.02";
    private static final String VISIONOS_DEVICE_MODEL = "RealityDevice17,1";
    private static final String VISIONOS_OS_VERSION = "26.5.23O471";
    private static final String VISIONOS_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    private static final String ANDROID_CLIENT_ID = "3";
    private static final String ANDROID_CLIENT_NAME = "ANDROID";
    private static final String ANDROID_CLIENT_VERSION = "21.03.36";
    private static final String ANDROID_USER_AGENT = "com.google.android.youtube/"
            + ANDROID_CLIENT_VERSION + " (Linux; U; Android 15; GB) gzip";

    // =========================================================================
    // Player: stream URLs
    // =========================================================================

    /** One selected adaptive stream. */
    static final class Stream {
        String url;
        int itag;
        String mime = "";
        int height;
        int fps;
        int bitrate;
        long bytes;
        /** False for a dubbed/translated audio track. */
        boolean original = true;
        /** True for the loudness-compressed duplicate YouTube ships alongside the real track. */
        boolean drc;
        String trackName = "";

        boolean isVideo() { return mime.startsWith("video"); }
    }

    /** Video + audio pair plus the duration reported by the player response. */
    static final class Media {
        Stream video;
        Stream audio;
        int durationSec;
        String title = "";
        String chan = "";
        String chanId = "";
        /** Wall-clock ms after which the googlevideo URLs stop working (~6 h). */
        long expiresAt;
        /** Both URLs answered a range past the first minute; see {@link #serving}. */
        volatile boolean checked;
        /** Extractors opened and seeked ahead of a tap, or null. Guarded by this. */
        Engine.Prepared prepared;

        synchronized void release() {
            if (prepared != null) { prepared.release(); prepared = null; }
        }
    }

    // ---- resolved-media cache and prefetch ----------------------------------

    /**
     * Resolved streams by id, kept until their URLs expire (~6 h). A screenful is plenty: the
     * point is that the rows at the top of the feed, and the row under a finger, are resolved
     * before they are tapped. The player call is ~400-500 ms of the click-to-playback time.
     */
    private static final java.util.LinkedHashMap<String, Media> CACHE =
            new java.util.LinkedHashMap<String, Media>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Media> e) {
                    if (size() <= 64) return false;
                    e.getValue().release();
                    return true;
                }
            };
    /** Ids being resolved right now, so a tap during a prefetch joins it instead of racing it. */
    private static final Map<String, Object> IN_FLIGHT = new HashMap<>();
    /**
     * Background work -- preparing extractors, fetching heads -- runs one job at a time: each
     * opens streams and pulls a couple of MB, and dozens at once would swamp the link the
     * feed's own polling and thumbnails are using. Priming is queued first, so it wins.
     */
    static final java.util.concurrent.ExecutorService BACKGROUND =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Only the rows the feed primes now should retain open extractors and producer threads. */
    private static final int MAX_PREPARED = 8;
    /** A player is on screen: background transfers would compete with it for the link. */
    static volatile boolean playing;

    /** Called at the start of every background job: waits out any playback first. */
    static void yieldToPlayback() {
        while (playing) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }
    /** URLs this close to expiry are not trusted for a whole video. */
    private static final long EXPIRY_MARGIN_MS = 30 * 60 * 1000;
    /** The cached result, if its URLs are still good for a whole video; null otherwise. */
    static Media cached(String videoId) {
        synchronized (CACHE) {
            Media m = CACHE.get(videoId);
            if (m == null) return null;
            if (m.expiresAt - EXPIRY_MARGIN_MS < System.currentTimeMillis()) {
                CACHE.remove(videoId);
                m.release();
                return null;
            }
            return m;
        }
    }

    /** Drops a cached entry whose URLs turned out not to work. */
    static void forget(String videoId) {
        Media m;
        synchronized (CACHE) { m = CACHE.remove(videoId); }
        if (m != null) m.release();
    }

    /** Drops every cached entry: they were all minted under a visitor identity now replaced. */
    static void forgetAll() {
        List<Media> gone;
        synchronized (CACHE) {
            gone = new ArrayList<>(CACHE.values());
            CACHE.clear();
        }
        for (Media m : gone) m.release();
    }

    /**
     * {@link #resolve} (or, with {@code fresh}, an uncached {@link #media}), checked to serve
     * the whole file. Since 2026-09-29 a visitor identity can go bad: its URLs -- fresh ones
     * included -- serve exactly the first minute of a video and then answer 403 forever. Eight
     * downloads on the phone stood at 46-63 s of media each, the same visitor's URL for another
     * video worked, and a new anonymous identity got 206 for the same bytes. So before bytes are
     * spent, a small range at 95% of each stream is asked for; a 403 there replaces the
     * identity ({@link Ident#refreshAfter403}, rate-limited) and resolves again.
     */
    static Media serving(android.content.Context ctx, String videoId, boolean fresh)
            throws IOException {
        for (int round = 0; ; round++) {
            String visitor = Ident.get(ctx);
            Media m = fresh ? media(videoId, true, visitor) : resolve(videoId, visitor);
            if (m.checked || servesPastStart(m)) {
                m.checked = true;
                if (fresh) {
                    // Share proven URLs with playback and heads, but never displace an entry:
                    // it may be holding a primed row's open extractors.
                    synchronized (CACHE) {
                        if (!CACHE.containsKey(videoId)) CACHE.put(videoId, m);
                    }
                }
                return m;
            }
            Dbg.i("yt: " + videoId + " refused past the first minute (round " + round + ")");
            forget(videoId);
            if (round >= 2 || !Ident.refreshAfter403(ctx)) {
                throw new IOException("HTTP 403 past the first minute of " + videoId);
            }
        }
    }

    /** A short video has no second minute to refuse. */
    private static final int CHECK_MIN_SEC = 75;

    private static boolean servesPastStart(Media m) throws IOException {
        if (m.durationSec > 0 && m.durationSec < CHECK_MIN_SEC) return true;
        return serves(m.video) && serves(m.audio);
    }

    /** One 1 KB range at 95% of the stream: 403 is a refusal, anything else not our business. */
    private static boolean serves(Stream s) throws IOException {
        if (s.bytes < 1 << 20) return true;
        long at = s.bytes / 100 * 95;
        java.net.HttpURLConnection c = Net.open(s.url, Net.UA_WEB,
                "Range", "bytes=" + at + "-" + (at + 1023));
        boolean drained = false;
        try {
            int code = c.getResponseCode();
            Dbg.v("probe " + s.itag + " at 95% -> " + code);
            if (code == 403) return false;
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code + " probing " + Net.shortUrl(s.url));
            }
            Net.drain(c.getInputStream());
            drained = true;
            return true;
        } finally {
            if (!drained) c.disconnect();
        }
    }

    /**
     * The prepared extractors for a video, if they were prepared for exactly this position;
     * the caller owns them from here. Ones prepared for another position are released.
     */
    static Engine.Prepared takePrepared(Media m, long startAtUs) {
        synchronized (m) {
            Engine.Prepared p = m.prepared;
            if (p == null) return null;
            m.prepared = null;
            if (p.startAtUs == startAtUs) return p;
            p.release();
            return null;
        }
    }

    /** {@link #media} through the cache; concurrent callers for one id share a single fetch. */
    static Media resolve(String videoId, String visitorData) throws IOException {
        Media m = cached(videoId);
        if (m != null) return m;
        Object gate;
        synchronized (CACHE) {
            gate = IN_FLIGHT.get(videoId);
            if (gate == null) { gate = new Object(); IN_FLIGHT.put(videoId, gate); }
        }
        synchronized (gate) {
            m = cached(videoId);            // filled by whoever held the gate before us
            if (m != null) return m;
            try {
                m = media(videoId, true, visitorData);
                synchronized (CACHE) { CACHE.put(videoId, m); }
                return m;
            } finally {
                synchronized (CACHE) { IN_FLIGHT.remove(videoId, gate); }
            }
        }
    }

    /**
     * Resolves a video in the background so that tapping it later is instant, and on request
     * also opens and seeks its extractors ({@link Engine#prepareAhead}). Cheap to call
     * repeatedly: an id that is already where it should be returns at once.
     *
     * @param prepare  open the extractors too; costs ~2 MB of traffic per video
     * @param posMs    the position the tap would resume at, so the seek is done here as well
     */
    static void prefetch(android.content.Context ctx, final String videoId,
                         final boolean prepare, final int posMs) {
        Media m = cached(videoId);
        if (m != null && (!prepare || preparedFor(m, posMs))) return;
        final android.content.Context app = ctx.getApplicationContext();
        Runnable work = new Runnable() {
            @Override public void run() {
                if (prepare) yieldToPlayback();
                try {
                    // Priming is worth a probe: a primed row that stops after a minute is worse
                    // than one that was never primed. A touch is not, it must answer now.
                    Media r = prepare ? serving(app, videoId, false)
                            : resolve(videoId, Ident.get(app));
                    if (prepare && !preparedFor(r, posMs)) {
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        Engine.Prepared p = Engine.prepareAhead(r, posMs * 1000L,
                                Heads.load(app, videoId, r, posMs));
                        synchronized (r) {
                            if (r.prepared != null) r.prepared.release();
                            r.prepared = p;
                        }
                        trimPrepared(r);
                        Dbg.v("prepared " + videoId + " at " + posMs + "ms in "
                                + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
                    }
                } catch (Exception e) {
                    Dbg.v("prefetch " + videoId + ": " + e);
                }
            }
        };
        if (prepare) BACKGROUND.execute(work);
        else new Thread(work, "prefetch").start();     // a touch: answer it now
    }

    private static boolean preparedFor(Media m, int posMs) {
        synchronized (m) { return m.prepared != null && m.prepared.startAtUs == posMs * 1000L; }
    }

    /**
     * CACHE holds resolved URLs for many rows, but an open Prepared also owns two extractors,
     * Sources and producer threads. As new videos reached the top of the feed those accumulated
     * until cache eviction (66 source-fill threads on the phone). Keep only the eight most
     * recently used preparations, matching Feed.PRIME_ROWS; resolved URL entries remain cached.
     */
    private static void trimPrepared(Media keep) {
        synchronized (CACHE) {
            int count = 0;
            for (Media m : CACHE.values()) {
                synchronized (m) { if (m.prepared != null) count++; }
            }
            if (count <= MAX_PREPARED) return;
            for (Media m : CACHE.values()) {           // access order: oldest first
                if (count <= MAX_PREPARED) break;
                if (m == keep) continue;
                synchronized (m) {
                    if (m.prepared != null) {
                        m.prepared.release();
                        m.prepared = null;
                        count--;
                    }
                }
            }
        }
    }

    /**
     * Resolves playable streams. Tries {@code VISIONOS} first -- the one client whose URLs serve
     * a whole file -- then {@code IOS}, then {@code ANDROID}, which only matter for the "made
     * for kids" videos VISIONOS refuses.
     *
     * @param visitorData the visitor identity. Not optional: without it VISIONOS answers
     *                    LOGIN_REQUIRED, and the other clients hand out URLs that stop serving
     *                    after roughly the first 90 seconds.
     */
    static Media media(String videoId, boolean wants1080, String visitorData)
            throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject res;
                if (attempt == 0) res = playerVisionOs(videoId, visitorData);
                else if (attempt == 1) res = playerIos(videoId, visitorData);
                else res = playerAndroid(videoId, visitorData);
                JSONObject status = res.optJSONObject("playabilityStatus");
                String s = status == null ? "" : status.optString("status", "");
                if (!"OK".equals(s)) {
                    String reason = status == null ? "" : status.optString("reason", s);
                    last = new IOException("not playable: " + reason);
                    continue;
                }
                Media m = pick(res, wants1080);
                if (m.video != null && m.audio != null) return m;
                last = new IOException("no usable video/audio pair");
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("extraction failed");
    }

    private static JSONObject playerVisionOs(String videoId, String visitorData)
            throws IOException {
        JSONObject client = baseClient(VISIONOS_CLIENT_NAME, VISIONOS_CLIENT_VERSION);
        put(client, "deviceMake", "Apple");
        put(client, "deviceModel", VISIONOS_DEVICE_MODEL);
        put(client, "userAgent", VISIONOS_USER_AGENT);
        put(client, "osName", "visionOS");
        put(client, "osVersion", VISIONOS_OS_VERSION);
        if (visitorData != null) put(client, "visitorData", visitorData);
        return player(videoId, client, VISIONOS_USER_AGENT, VISIONOS_CLIENT_ID,
                VISIONOS_CLIENT_VERSION, visitorData);
    }

    private static JSONObject playerIos(String videoId, String visitorData) throws IOException {
        JSONObject client = baseClient(IOS_CLIENT_NAME, IOS_CLIENT_VERSION);
        if (visitorData != null) put(client, "visitorData", visitorData);
        put(client, "deviceMake", "Apple");
        put(client, "deviceModel", IOS_DEVICE_MODEL);
        put(client, "osName", "iOS");
        put(client, "osVersion", IOS_OS_VERSION);
        put(client, "platform", "MOBILE");
        return player(videoId, client, IOS_USER_AGENT, IOS_CLIENT_ID, IOS_CLIENT_VERSION,
                visitorData);
    }

    private static JSONObject playerAndroid(String videoId, String visitorData)
            throws IOException {
        JSONObject client = baseClient(ANDROID_CLIENT_NAME, ANDROID_CLIENT_VERSION);
        if (visitorData != null) put(client, "visitorData", visitorData);
        put(client, "androidSdkVersion", 35);
        put(client, "osName", "Android");
        put(client, "osVersion", "15");
        put(client, "platform", "MOBILE");
        return player(videoId, client, ANDROID_USER_AGENT, ANDROID_CLIENT_ID,
                ANDROID_CLIENT_VERSION, visitorData);
    }

    private static JSONObject player(String videoId, JSONObject client, String ua,
                                     String clientId, String clientVersion, String visitorData)
            throws IOException {
        JSONObject body = new JSONObject();
        JSONObject context = new JSONObject();
        put(context, "client", client);
        put(body, "context", context);
        put(body, "videoId", videoId);
        put(body, "contentCheckOk", true);
        put(body, "racyCheckOk", true);
        byte[] out = visitorData == null
                ? Net.post(PLAYER_URL, body.toString().getBytes("UTF-8"), ua,
                        "Content-Type", "application/json",
                        "X-YouTube-Client-Name", clientId,
                        "X-YouTube-Client-Version", clientVersion,
                        "Origin", "https://www.youtube.com")
                : Net.post(PLAYER_URL, body.toString().getBytes("UTF-8"), ua,
                        "Content-Type", "application/json",
                        "X-YouTube-Client-Name", clientId,
                        "X-YouTube-Client-Version", clientVersion,
                        "X-Goog-Visitor-Id", visitorData,
                        "Origin", "https://www.youtube.com");
        return parse(out);
    }

    private static Media pick(JSONObject res, boolean wants1080) {
        Media m = new Media();
        JSONObject details = res.optJSONObject("videoDetails");
        if (details != null) {
            m.durationSec = parseInt(details.optString("lengthSeconds", "0"));
            m.title = details.optString("title", "");
            m.chan = details.optString("author", "");
            m.chanId = details.optString("channelId", "");
        }
        JSONObject sd = res.optJSONObject("streamingData");
        if (sd == null) return m;
        m.expiresAt = System.currentTimeMillis()
                + 1000L * Math.max(0, parseInt(sd.optString("expiresInSeconds", "0")));
        JSONArray formats = sd.optJSONArray("adaptiveFormats");
        if (formats == null) return m;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null || f.optString("url", "").isEmpty()) continue;   // SABR-only entry
            Stream st = new Stream();
            st.url = f.optString("url");
            st.itag = f.optInt("itag");
            st.mime = f.optString("mimeType", "");
            st.height = f.optInt("height");
            st.fps = f.optInt("fps");
            st.bitrate = f.optInt("bitrate");
            st.bytes = parseLong(f.optString("contentLength", "0"));
            st.drc = f.optBoolean("isDrc", false);
            JSONObject track = f.optJSONObject("audioTrack");
            if (track != null) {
                st.trackName = track.optString("displayName", "");
                // A video with dubs lists one audio format per language -- 125 of them is normal.
                // The original is the default one, and its display name ends with "original".
                st.original = track.optBoolean("audioIsDefault", false)
                        || st.trackName.toLowerCase(Locale.US).endsWith("original");
            }
            if (st.isVideo()) {
                if (better(m.video, st, wants1080)) m.video = st;
            } else if (st.mime.startsWith("audio")) {
                if (betterAudio(m.audio, st)) m.audio = st;
            }
        }
        return m;
    }

    /**
     * Prefers H.264 at the target height, highest frame rate available.
     *
     * <p>H.264 because it is the one codec every Android device decodes in hardware at 1080p --
     * the OnePlus 5T has no AV1 decoder at all. 1080p60 is kept: the device's Qualcomm decoder
     * handles it, and the frame drops that first appeared at 60 fps came from this app's own
     * decode pipeline, not from the hardware.
     */
    private static boolean better(Stream cur, Stream cand, boolean wants1080) {
        if (!isDecodable(cand.mime)) return false;
        int want = wants1080 ? 1080 : 720;
        if (cand.height > want) return false;
        if (cur == null) return true;
        if (cand.height != cur.height) return cand.height > cur.height;
        boolean candAvc = isAvc(cand.mime), curAvc = isAvc(cur.mime);
        if (candAvc != curAvc) return candAvc;
        if (cand.fps != cur.fps) return cand.fps > cur.fps;
        return cand.bitrate > cur.bitrate;
    }

    /**
     * Picks the <em>original</em> audio track, never a dub.
     *
     * <p>YouTube auto-dubs a lot of channels now and returns one audio format per language --
     * over a hundred on a popular video -- so choosing purely by bitrate lands on whichever
     * translation happens to be encoded highest. The original is flagged
     * {@code audioTrack.audioIsDefault} and its display name ends with "original"; that holds
     * whether the original is English or German, so nothing here is language-specific.
     *
     * <p>m4a/AAC only, because MediaExtractor reads it straight from the stream and every device
     * decodes it. The {@code isDrc} duplicate is a loudness-compressed rendering of the same
     * track, so it loses to the untouched one.
     */
    private static boolean betterAudio(Stream cur, Stream cand) {
        if (!cand.mime.contains("mp4a")) return false;
        if (cur == null) return true;
        if (cand.original != cur.original) return cand.original;
        if (cand.drc != cur.drc) return !cand.drc;
        return cand.bitrate > cur.bitrate;
    }

    private static boolean isAvc(String mime) { return mime.contains("avc1"); }

    private static boolean isDecodable(String mime) {
        return mime.contains("avc1") || mime.contains("vp9") || mime.contains("vp09");
    }

    // =========================================================================
    // Feed: RSS for exact timestamps, browse for duration/views
    // =========================================================================

    /**
     * Channel RSS: 15 newest uploads with an exact publish time. ~6 KB on the wire, which is why
     * this and not a browse call is what every refresh hits for all channels.
     *
     * <p>It includes Shorts and livestreams; those are filtered out later by
     * {@link #browseVideos} not knowing about them.
     */
    static List<Vid> rss(String channelId) throws IOException {
        byte[] xml = Net.get(RSS_URL + channelId, Net.UA_WEB);
        List<Vid> out = new ArrayList<>(15);
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(xml), "UTF-8");
            Vid cur = null;
            String tag = null;
            boolean inEntry = false, inAuthor = false;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    if ("entry".equals(tag)) { cur = new Vid(); inEntry = true; }
                    else if ("author".equals(tag)) inAuthor = true;
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("entry".equals(p.getName())) {
                        if (cur != null && cur.id != null) {
                            cur.chanId = channelId;
                            cur.thumb = thumbUrl(cur.id);
                            out.add(cur);
                        }
                        cur = null; inEntry = false;
                    } else if ("author".equals(p.getName())) inAuthor = false;
                    tag = null;
                } else if (ev == XmlPullParser.TEXT && inEntry && cur != null && tag != null) {
                    String text = p.getText();
                    if (text == null) continue;
                    if ("videoId".equals(tag)) cur.id = text.trim();
                    else if ("title".equals(tag) && !inAuthor) cur.title = text;
                    else if ("name".equals(tag) && inAuthor) cur.chan = text;
                    else if ("published".equals(tag)) cur.pub = parseIso(text.trim());
                }
            }
        } catch (Exception e) {
            throw new IOException("bad RSS for " + channelId + ": " + e, e);
        }
        return out;
    }

    /**
     * The channel's Videos tab: id -&gt; (duration, views). One call, ~25 KB on the wire.
     * Only issued for channels whose RSS turned up something we have not stored yet.
     *
     * <p>Anything absent from the result is a Short, a live stream or a scheduled premiere --
     * none of which appear on this tab -- and gets dropped from the feed.
     */
    static Map<String, Vid> browseVideos(String channelId) throws IOException {
        JSONObject body = new JSONObject();
        JSONObject context = new JSONObject();
        put(context, "client", baseClient(WEB_CLIENT_NAME, WEB_CLIENT_VERSION));
        put(body, "context", context);
        put(body, "browseId", channelId);
        put(body, "params", VIDEOS_TAB_PARAMS);
        byte[] out = Net.post(BROWSE_URL, body.toString().getBytes("UTF-8"), Net.UA_WEB,
                "Content-Type", "application/json",
                "X-YouTube-Client-Name", WEB_CLIENT_ID,
                "X-YouTube-Client-Version", WEB_CLIENT_VERSION,
                "Origin", "https://www.youtube.com");
        JSONObject root = parse(out);

        Map<String, Vid> map = new HashMap<>();
        List<JSONObject> lockups = new ArrayList<>();
        collect(root, "lockupViewModel", lockups);
        for (JSONObject lu : lockups) {
            if (!"LOCKUP_CONTENT_TYPE_VIDEO".equals(lu.optString("contentType"))) continue;
            String id = lu.optString("contentId", "");
            if (id.isEmpty()) continue;
            int dur = durationOf(lu);
            if (dur <= 0) continue;                       // LIVE / UPCOMING badge, not a duration
            Vid v = new Vid(id);
            v.dur = dur;
            v.chanId = channelId;
            v.thumb = thumbUrl(id);
            v.title = firstString(lu, "title");
            v.views = viewsOf(lu);
            v.pub = publishedFrom(ageOf(lu));
            map.put(id, v);
        }
        return map;
    }

    /** Reads the "15:46" badge that sits on the thumbnail. */
    private static int durationOf(JSONObject lockup) {
        List<JSONObject> badges = new ArrayList<>();
        collect(lockup, "thumbnailBadgeViewModel", badges);
        for (JSONObject b : badges) {
            int d = parseClock(b.optString("text", ""));
            if (d > 0) return d;
        }
        return 0;
    }

    /** The "8 hours ago" half of the metadata row, if present. */
    private static String ageOf(JSONObject lockup) {
        List<JSONObject> rows = new ArrayList<>();
        collect(lockup, "contentMetadataViewModel", rows);
        for (JSONObject r : rows) {
            JSONArray metaRows = r.optJSONArray("metadataRows");
            if (metaRows == null) continue;
            for (int i = 0; i < metaRows.length(); i++) {
                JSONObject row = metaRows.optJSONObject(i);
                JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject t = parts.optJSONObject(j);
                    JSONObject txt = t == null ? null : t.optJSONObject("text");
                    String s = txt == null ? "" : txt.optString("content", "");
                    if (s.endsWith(" ago")) return s;
                }
            }
        }
        return "";
    }

    /**
     * Turns "8 hours ago" into an approximate publish time.
     *
     * <p>Only used when a channel's RSS feed is unavailable -- some channels 404 there while
     * browse works fine -- because RSS is the only source of an exact timestamp. Approximate
     * ordering beats dropping the channel from the feed entirely.
     */
    static long publishedFrom(String ago) {
        if (ago == null || ago.isEmpty()) return 0;
        int number = 0;
        int i = 0;
        while (i < ago.length() && !Character.isDigit(ago.charAt(i))) i++;
        while (i < ago.length() && Character.isDigit(ago.charAt(i))) {
            number = number * 10 + (ago.charAt(i++) - '0');
        }
        if (number == 0) number = 1;
        String unit = ago.toLowerCase(Locale.US);
        long ms;
        if (unit.contains("second")) ms = 1000L;
        else if (unit.contains("minute")) ms = 60_000L;
        else if (unit.contains("hour")) ms = 3600_000L;
        else if (unit.contains("day")) ms = 86400_000L;
        else if (unit.contains("week")) ms = 7L * 86400_000L;
        else if (unit.contains("month")) ms = 30L * 86400_000L;
        else if (unit.contains("year")) ms = 365L * 86400_000L;
        else return 0;
        return System.currentTimeMillis() - number * ms;
    }

    private static String viewsOf(JSONObject lockup) {
        List<JSONObject> rows = new ArrayList<>();
        collect(lockup, "contentMetadataViewModel", rows);
        for (JSONObject r : rows) {
            JSONArray metaRows = r.optJSONArray("metadataRows");
            if (metaRows == null) continue;
            for (int i = 0; i < metaRows.length(); i++) {
                JSONObject row = metaRows.optJSONObject(i);
                JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject t = parts.optJSONObject(j);
                    JSONObject txt = t == null ? null : t.optJSONObject("text");
                    String s = txt == null ? "" : txt.optString("content", "");
                    if (s.endsWith("views") || s.endsWith("view")) return s;
                }
            }
        }
        return "";
    }

    // =========================================================================
    // Search
    // =========================================================================

    static List<Vid> search(String query) throws IOException {
        JSONObject body = new JSONObject();
        JSONObject context = new JSONObject();
        put(context, "client", baseClient(WEB_CLIENT_NAME, WEB_CLIENT_VERSION));
        put(body, "context", context);
        put(body, "query", query);
        put(body, "params", "EgIQAQ%3D%3D");     // type=video
        byte[] out = Net.post(SEARCH_URL, body.toString().getBytes("UTF-8"), Net.UA_WEB,
                "Content-Type", "application/json",
                "X-YouTube-Client-Name", WEB_CLIENT_ID,
                "X-YouTube-Client-Version", WEB_CLIENT_VERSION,
                "Origin", "https://www.youtube.com");
        JSONObject root = parse(out);

        List<Vid> res = new ArrayList<>();
        List<JSONObject> items = new ArrayList<>();
        collect(root, "videoRenderer", items);
        for (JSONObject r : items) {
            String id = r.optString("videoId", "");
            if (id.isEmpty()) continue;
            Vid v = new Vid(id);
            v.title = firstString(r.optJSONObject("title"), "text");
            if (v.title.isEmpty()) v.title = runsText(r.optJSONObject("title"));
            JSONObject owner = r.optJSONObject("ownerText");
            v.chan = runsText(owner);
            v.chanId = firstBrowseId(owner);
            v.thumb = thumbUrl(id);
            JSONObject when = r.optJSONObject("publishedTimeText");
            v.pub = publishedFrom(when == null ? "" : when.optString("simpleText", ""));
            JSONObject len = r.optJSONObject("lengthText");
            v.dur = parseClock(len == null ? "" : len.optString("simpleText", ""));
            JSONObject vc = r.optJSONObject("shortViewCountText");
            v.views = vc == null ? "" : vc.optString("simpleText", "");
            if (v.dur > 0) res.add(v);        // drop live entries, which have no lengthText
        }
        return res;
    }

    // =========================================================================
    // helpers
    // =========================================================================

    static String thumbUrl(String videoId) {
        // Stable, unsigned, never expires and exactly 16:9 at 320x180 -- ideal for the disk
        // cache, unlike the signed hq720 URLs the browse response hands out.
        return thumbUrl(videoId, "mqdefault");
    }

    static String thumbUrl(String videoId, String quality) {
        return "https://i.ytimg.com/vi/" + videoId + "/" + quality + ".jpg";
    }

    private static JSONObject baseClient(String name, String version) {
        JSONObject c = new JSONObject();
        put(c, "clientName", name);
        put(c, "clientVersion", version);
        put(c, "hl", "en");
        put(c, "gl", "US");
        put(c, "utcOffsetMinutes", 0);
        return c;
    }

    /** Depth-first collection of every value stored under {@code key}. */
    private static void collect(Object node, String key, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.opt(k);
                if (key.equals(k) && v instanceof JSONObject) out.add((JSONObject) v);
                else collect(v, key, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), key, out);
        }
    }

    /** First {@code {"content": "..."}} found under {@code key}, anywhere in the subtree. */
    private static String firstString(JSONObject root, String key) {
        if (root == null) return "";
        List<JSONObject> hits = new ArrayList<>();
        collect(root, key, hits);
        for (JSONObject h : hits) {
            String s = h.optString("content", "");
            if (!s.isEmpty()) return s;
            s = h.optString("simpleText", "");
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** The channel id behind a text with a browse endpoint, e.g. a search result's owner. */
    private static String firstBrowseId(JSONObject o) {
        if (o == null) return "";
        List<JSONObject> hits = new ArrayList<>();
        collect(o, "browseEndpoint", hits);
        return hits.isEmpty() ? "" : hits.get(0).optString("browseId", "");
    }

    private static String runsText(JSONObject o) {
        if (o == null) return "";
        JSONArray runs = o.optJSONArray("runs");
        if (runs == null) return o.optString("simpleText", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
        }
        return sb.toString();
    }

    /** "15:46" or "3:17:30" to seconds; 0 for "LIVE", "UPCOMING" and anything else. */
    static int parseClock(String s) {
        if (s == null || s.isEmpty()) return 0;
        int total = 0, part = 0, digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') { part = part * 10 + (ch - '0'); digits++; }
            else if (ch == ':') { total = total * 60 + part; part = 0; digits = 0; }
            else return 0;
        }
        if (digits == 0) return 0;
        return total * 60 + part;
    }

    private static long parseIso(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    private static JSONObject parse(byte[] bytes) throws IOException {
        try {
            return new JSONObject(new String(bytes, "UTF-8"));
        } catch (Exception e) {
            throw new IOException("bad InnerTube JSON", e);
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (org.json.JSONException ignored) { }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return 0; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; }
    }
}
