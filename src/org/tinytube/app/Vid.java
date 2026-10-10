package org.tinytube.app;

/** One feed entry. Mutable and reused by the adapter; no getters, to keep dex small. */
final class Vid {
    String id;
    String title = "";
    String chanId = "";
    String chan = "";
    String views = "";
    String thumb = "";
    /** Seconds; 0 when unknown (RSS-only entry whose browse metadata hasn't arrived yet). */
    int dur;
    /** Publish time, epoch millis, exact -- taken from the RSS feed, not from "4 days ago". */
    long pub;

    boolean watched;
    /** Resume position in ms. */
    int pos;
    /** One of {@link Db#CACHE_NONE}, {@link Db#CACHE_BUSY}, {@link Db#CACHE_DONE}. */
    int cache;
    /** The channel auto-caches its oldest unwatched videos. */
    boolean auto;

    Vid() {}

    Vid(String id) { this.id = id; }
}
