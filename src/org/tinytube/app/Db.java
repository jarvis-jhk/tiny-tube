package org.tinytube.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything persistent lives here: the feed, per-video state and cached SponsorBlock
 * segments. Framework SQLite, no Room, no ORM.
 */
final class Db extends SQLiteOpenHelper {

    static final int CACHE_NONE = 0;
    static final int CACHE_BUSY = 1;
    static final int CACHE_DONE = 2;
    /**
     * Not cached, and not to be auto-cached either: the user cancelled or removed this download
     * by hand on a channel that auto-caches, so reconciling must not just bring it back.
     */
    static final int CACHE_SKIP = 3;

    /**
     * A video counts as watched once playback is within this much of its end.
     *
     * <p>Not a fraction: 95% of a long video is minutes still unwatched, and a video that left
     * the feed is indistinguishable from one that was missed. Only marking it by hand, or
     * actually watching into the last three seconds, counts.
     */
    static final long WATCHED_TAIL_US = 3_000_000L;

    private static Db instance;

    static synchronized Db get(Context ctx) {
        if (instance == null) instance = new Db(ctx.getApplicationContext());
        return instance;
    }

    static final int VERSION = 10;

    private Db(Context ctx) { this(ctx, "tinytube.db"); }
    Db(Context ctx, String path) { super(ctx, path, null, VERSION); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE vid ("
                + "id TEXT PRIMARY KEY, chan_id TEXT, chan TEXT, title TEXT,"
                + "dur INTEGER, pub INTEGER, views TEXT, thumb TEXT)");
        db.execSQL("CREATE INDEX vid_pub ON vid (pub DESC)");
        db.execSQL("CREATE TABLE state ("
                + "id TEXT PRIMARY KEY, watched INTEGER DEFAULT 0, pos INTEGER DEFAULT 0,"
                + "cache INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE retry (id TEXT PRIMARY KEY, at INTEGER, rounds INTEGER)");
        // SponsorBlock segments, cached so a cached video also skips correctly offline.
        db.execSQL("CREATE TABLE sb (id TEXT PRIMARY KEY, segs TEXT, at INTEGER)");
        createChannelCursor(db);
        createAutoTable(db);
        createUnsubTable(db);
        createSubTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) createChannelCursor(db);
        if (from < 3) createAutoTable(db);
        if (from < 4) createUnsubTable(db);
        if (from < 5) createSubTable(db);
        if (from >= 2 && from < 6) {
            // from < 2 got the column from createChannelCursor above.
            db.execSQL("ALTER TABLE chan ADD COLUMN fail INTEGER DEFAULT 0");
        }
        if (from < 8) db.execSQL("DROP TABLE IF EXISTS pref");
        if (from < 9) db.execSQL("CREATE TABLE retry (id TEXT PRIMARY KEY, at INTEGER, rounds INTEGER)");
        // Downloads deferred for a day by builds that could not get past the first-minute
        // refusal (CLAUDE.md item 20) are due now, under the probe that can.
        if (from == 9) db.execSQL("UPDATE retry SET at=0");
    }

    /** Channels subscribed to from search, on top of the compiled-in list. */
    private static void createSubTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sub (chan_id TEXT PRIMARY KEY, name TEXT)");
    }

    /** Adds a channel to the subscriptions; it is polled first thing, being the stalest. */
    synchronized void subscribe(String chanId, String name) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR REPLACE INTO sub (chan_id, name) VALUES (?, ?)",
                    new Object[]{chanId, name});
            db.delete("unsub", "chan_id=?", new String[]{chanId});
            db.execSQL("INSERT OR IGNORE INTO chan (id, last) VALUES (?, 0)", new Object[]{chanId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized boolean subscribed(String chanId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM chan WHERE id=?",
                new String[]{chanId});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    /** A channel's name: from the compiled-in list, else from the subscriptions added here. */
    synchronized String chanName(String chanId) {
        String name = Chans.nameOf(chanId);
        if (!name.isEmpty()) return name;
        Cursor c = getReadableDatabase().rawQuery("SELECT name FROM sub WHERE chan_id=?",
                new String[]{chanId});
        try { return c.moveToFirst() ? c.getString(0) : ""; } finally { c.close(); }
    }

    /**
     * Channels dropped from the compiled-in list by a long press on a row's watched button.
     * The list itself is regenerated from PipePipe, so the removal lives here; re-subscribing
     * means deleting the row (no UI for that, on purpose).
     */
    private static void createUnsubTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS unsub (chan_id TEXT PRIMARY KEY)");
    }

    synchronized List<String> videoIdsOf(String chanId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT id FROM vid WHERE chan_id=?",
                new String[]{chanId});
        List<String> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    /** Drops a channel's videos from the feed and stops polling it. Watch history rows stay. */
    synchronized void unsubscribe(String chanId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO unsub (chan_id) VALUES (?)", new Object[]{chanId});
            db.execSQL("DELETE FROM sb WHERE id IN (SELECT id FROM vid WHERE chan_id=?)",
                    new Object[]{chanId});
            db.delete("vid", "chan_id=?", new String[]{chanId});
            db.delete("chan", "id=?", new String[]{chanId});
            db.delete("auto", "chan_id=?", new String[]{chanId});
            db.delete("sub", "chan_id=?", new String[]{chanId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Channels whose two oldest unwatched videos are kept downloaded. */
    private static void createAutoTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS auto (chan_id TEXT PRIMARY KEY)");
    }

    synchronized boolean isAuto(String chanId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM auto WHERE chan_id=?",
                new String[]{chanId});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    synchronized void setAuto(String chanId, boolean on) {
        if (on) {
            getWritableDatabase().execSQL("INSERT OR IGNORE INTO auto (chan_id) VALUES (?)",
                    new Object[]{chanId});
        } else {
            getWritableDatabase().delete("auto", "chan_id=?", new String[]{chanId});
        }
    }

    synchronized List<String> autoChannels() {
        Cursor c = getReadableDatabase().rawQuery("SELECT chan_id FROM auto", null);
        List<String> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    /**
     * A channel's {@code n} oldest unwatched feed videos, oldest first, with only their id and
     * cache state filled in. Videos the user skipped by hand do not count towards the {@code n}.
     */
    synchronized List<Vid> autoOldest(String chanId, int n) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT v.id, IFNULL(s.cache,0) FROM vid v LEFT JOIN state s ON s.id = v.id"
                        + " WHERE v.chan_id = ? AND IFNULL(s.watched,0) = 0"
                        + " AND IFNULL(s.cache,0) != " + CACHE_SKIP
                        + " ORDER BY v.pub ASC LIMIT " + n, new String[]{chanId});
        List<Vid> out = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                Vid v = new Vid(c.getString(0));
                v.cache = c.getInt(1);
                out.add(v);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Every unwatched feed video with its resume position, newest first. */
    synchronized List<Vid> unwatchedWithPos() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT v.id, IFNULL(s.pos,0) FROM vid v LEFT JOIN state s ON s.id = v.id"
                        + " WHERE IFNULL(s.watched,0) = 0 ORDER BY v.pub DESC", null);
        List<Vid> out = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                Vid v = new Vid(c.getString(0));
                v.pos = c.getInt(1);
                out.add(v);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Unwatched feed videos whose SponsorBlock segments are due ({@link Sponsor#dueSql}), stalest
     * first.
     */
    synchronized List<String> staleSegmentIds(int n, long now) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT v.id FROM vid v LEFT JOIN state s ON s.id = v.id"
                        + " LEFT JOIN sb ON sb.id = v.id"
                        + " WHERE IFNULL(s.watched,0) = 0 AND " + Sponsor.dueSql(now)
                        + " ORDER BY IFNULL(sb.at,0) ASC LIMIT " + n, null);
        List<String> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    /**
     * When each channel was last polled, so refreshing can round-robin a couple at a time.
     *
     * <p>{@code last} is the last <em>successful</em> poll -- a failed one leaves it alone, so
     * the channel stays stale and is retried first. {@code fail} is when the current run of
     * failures started, 0 when the last poll worked; the two together are what tells a channel
     * that is failing apart from one the app has simply not got round to.
     */
    private static void createChannelCursor(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chan ("
                + "id TEXT PRIMARY KEY, last INTEGER, fail INTEGER DEFAULT 0)");
    }

    /** The {@code count} least-recently-polled channels that are older than {@code staleBefore}. */
    synchronized List<String> stalestChannels(int count, long staleBefore) {
        List<String> out = new ArrayList<>(count);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, IFNULL(last, 0) AS last FROM chan"
                        + " WHERE id NOT IN (SELECT chan_id FROM unsub)"
                        + " ORDER BY last ASC LIMIT " + count,
                null);
        try {
            while (c.moveToNext()) {
                if (c.getLong(1) > staleBefore) break;
                out.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Registers every configured channel once, so the cursor table has a row per channel. */
    synchronized void seedChannels() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < Chans.count(); i++) {
                db.execSQL("INSERT OR IGNORE INTO chan (id, last) VALUES (?, 0)",
                        new Object[]{Chans.id(i)});
            }
            db.execSQL("INSERT OR IGNORE INTO chan (id, last) SELECT chan_id, 0 FROM sub");
            db.execSQL("DELETE FROM chan WHERE id NOT IN ("
                    + placeholders(Chans.count()) + ") AND id NOT IN (SELECT chan_id FROM sub)",
                    channelIds());
            db.execSQL("DELETE FROM chan WHERE id IN (SELECT chan_id FROM unsub)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    private static Object[] channelIds() {
        Object[] ids = new Object[Chans.count()];
        for (int i = 0; i < ids.length; i++) ids[i] = Chans.id(i);
        return ids;
    }

    synchronized void markChannelPolled(String channelId) {
        getWritableDatabase().execSQL(
                "INSERT OR REPLACE INTO chan (id, last, fail) VALUES (?, ?, 0)",
                new Object[]{channelId, System.currentTimeMillis()});
    }

    /**
     * Records that a poll of this channel failed. {@code last} is untouched, so the channel is
     * retried on the next tick; {@code fail} keeps the time the first failure of this run was
     * seen, and a later success clears it.
     */
    synchronized void markChannelFailed(String channelId) {
        getWritableDatabase().execSQL(
                "UPDATE chan SET fail=? WHERE id=? AND IFNULL(fail, 0)=0",
                new Object[]{System.currentTimeMillis(), channelId});
    }

    /**
     * Channels that are failing and have had nothing work since {@code before}: the ones worth
     * warning about. Names, longest-stalled first; the time the oldest of them was last in good
     * order is written to {@code oldestOut[0]}.
     *
     * <p>Both halves matter. Without {@code fail} this would also fire on a channel nobody has
     * opened the app long enough to reach, and without a time it would fire on a single failed
     * fetch minutes ago. For a channel that has never once been polled successfully -- one just
     * added from search, say -- the run counts from its first failure rather than from the
     * epoch.
     */
    synchronized List<String> stalledChannels(long before, long[] oldestOut) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, CASE WHEN IFNULL(last, 0) > 0 THEN last ELSE fail END AS since"
                        + " FROM chan WHERE IFNULL(fail, 0) > 0"
                        // Inlined for the same reason as staleSegmentIds: bound as TEXT this
                        // comparison is always true, and the warning fired on the first failure.
                        + " AND (CASE WHEN IFNULL(last, 0) > 0 THEN last ELSE fail END) < " + before
                        + " AND id NOT IN (SELECT chan_id FROM unsub)"
                        + " ORDER BY since ASC", null);
        try {
            while (c.moveToNext()) {
                if (out.isEmpty()) oldestOut[0] = c.getLong(1);
                String name = chanName(c.getString(0));
                out.add(name.isEmpty() ? c.getString(0) : name);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ---- feed ---------------------------------------------------------------

    /** Inserts or refreshes feed rows. Existing per-video state is untouched. */
    synchronized void putVideos(List<Vid> vids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            for (Vid v : vids) {
                cv.clear();
                cv.put("id", v.id);
                cv.put("chan_id", v.chanId);
                cv.put("chan", v.chan);
                cv.put("title", v.title);
                cv.put("dur", v.dur);
                cv.put("pub", v.pub);
                cv.put("views", v.views);
                cv.put("thumb", v.thumb);
                db.insertWithOnConflict("vid", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** True when we have never seen this id -- used to decide whether a browse call is needed. */
    synchronized boolean isKnown(String id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM vid WHERE id=? LIMIT 1",
                new String[]{id});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    /** Feed views, in filter-button order after the online default. */
    static final int VIEW_UNWATCHED = 0, VIEW_ALL = 1, VIEW_OFFLINE = 2, VIEW_READY = 3;

    /**
     * The feed, newest first.
     *
     * <p>Nothing ages out and nothing is capped: a video leaves this list only by being marked
     * watched. That is a deliberate rule -- an unwatched video disappearing on its own would be
     * indistinguishable from having missed it.
     *
     * @param view {@link #VIEW_READY}: downloaded videos and unwatched videos outside auto-cache
     *             channels; {@link #VIEW_UNWATCHED}: watched videos are hidden, except ones that are still
     *             downloaded, which stay in view, dimmed, until the download is removed;
     *             {@link #VIEW_ALL}: everything; {@link #VIEW_OFFLINE}: downloaded videos only,
     *             the ones that play with no network.
     */
    synchronized List<Vid> feed(int view) {
        String where = view == VIEW_ALL ? ""
                : view == VIEW_OFFLINE ? " WHERE IFNULL(s.cache,0) = " + CACHE_DONE
                : view == VIEW_READY ? " WHERE IFNULL(s.cache,0) = " + CACHE_DONE
                        + " OR (IFNULL(s.watched,0) = 0 AND NOT EXISTS"
                        + "(SELECT 1 FROM auto a WHERE a.chan_id = v.chan_id))"
                : " WHERE IFNULL(s.watched,0) = 0 OR IFNULL(s.cache,0) IN ("
                        + CACHE_BUSY + "," + CACHE_DONE + ")";
        String sql = "SELECT v.id, v.chan_id, v.chan, v.title, v.dur, v.pub, v.views, v.thumb,"
                + " IFNULL(s.watched,0), IFNULL(s.pos,0), IFNULL(s.cache,0),"
                + " EXISTS(SELECT 1 FROM auto a WHERE a.chan_id = v.chan_id)"
                + " FROM vid v LEFT JOIN state s ON s.id = v.id"
                + where
                + " ORDER BY v.pub DESC";
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        List<Vid> out = new ArrayList<>(c.getCount());
        try {
            while (c.moveToNext()) {
                Vid v = new Vid(c.getString(0));
                v.chanId = c.getString(1);
                v.chan = c.getString(2);
                v.title = c.getString(3);
                v.dur = c.getInt(4);
                v.pub = c.getLong(5);
                v.views = c.getString(6);
                v.thumb = c.getString(7);
                v.watched = c.getInt(8) != 0;
                v.pos = c.getInt(9);
                v.cache = c.getInt(10);
                v.auto = c.getInt(11) != 0;
                out.add(v);
            }
        } finally {
            c.close();
        }
        return out;
    }

    synchronized Vid video(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT v.id, v.chan_id, v.chan, v.title, v.dur, v.pub, v.views, v.thumb,"
                        + " IFNULL(s.watched,0), IFNULL(s.pos,0), IFNULL(s.cache,0)"
                        + " FROM vid v LEFT JOIN state s ON s.id = v.id WHERE v.id=?",
                new String[]{id});
        try {
            if (!c.moveToFirst()) return null;
            Vid v = new Vid(c.getString(0));
            v.chanId = c.getString(1); v.chan = c.getString(2); v.title = c.getString(3);
            v.dur = c.getInt(4); v.pub = c.getLong(5); v.views = c.getString(6);
            v.thumb = c.getString(7); v.watched = c.getInt(8) != 0; v.pos = c.getInt(9);
            v.cache = c.getInt(10);
            return v;
        } finally {
            c.close();
        }
    }

    // ---- per-video state ----------------------------------------------------

    private synchronized void setState(String id, String col, Object val) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        if (val instanceof Integer) cv.put(col, (Integer) val); else cv.put(col, (String) val);
        // INSERT OR IGNORE then UPDATE: keeps the other columns when the row already exists.
        db.insertWithOnConflict("state", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        cv.remove("id");
        db.update("state", cv, "id=?", new String[]{id});
    }

    synchronized void setWatched(String id, boolean watched) { setState(id, "watched", watched ? 1 : 0); }

    synchronized void setPos(String id, int ms) { setState(id, "pos", ms); }

    synchronized void setCache(String id, int state) { setState(id, "cache", state); }

    /** A failed download stays BUSY but cannot restart until its saved retry time. */
    synchronized int deferCache(String id, long at) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT INTO retry (id, at, rounds) VALUES (?, ?, 1)"
                        + " ON CONFLICT(id) DO UPDATE SET at=excluded.at, rounds=rounds+1",
                new Object[]{id, at});
        Cursor c = db.rawQuery("SELECT rounds FROM retry WHERE id=?", new String[]{id});
        try { return c.moveToFirst() ? c.getInt(0) : 1; } finally { c.close(); }
    }

    synchronized int retryRounds(String id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT rounds FROM retry WHERE id=?",
                new String[]{id});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /** Makes every deferred download due now; how many were waiting. */
    synchronized int releaseDeferred() {
        ContentValues cv = new ContentValues();
        cv.put("at", 0);
        // Inlined, not bound: a bound argument is TEXT (item 16 in CLAUDE.md).
        return getWritableDatabase().update("retry", cv, "at > " + System.currentTimeMillis(),
                null);
    }

    synchronized void clearCacheRetry(String id) {
        getWritableDatabase().delete("retry", "id=?", new String[]{id});
    }

    synchronized List<String> dueDownloads() {
        Cursor c = getReadableDatabase().rawQuery("SELECT s.id FROM state s"
                + " LEFT JOIN retry r ON r.id=s.id WHERE s.cache=" + CACHE_BUSY
                + " AND IFNULL(r.at,0) <= " + System.currentTimeMillis(), null);
        List<String> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    synchronized int cacheState(String id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT cache FROM state WHERE id=?",
                new String[]{id});
        try { return c.moveToFirst() ? c.getInt(0) : CACHE_NONE; } finally { c.close(); }
    }

    /** Video ids sitting in a given cache state, e.g. downloads interrupted by a restart. */
    synchronized List<String> idsWithCache(int state) {
        Cursor c = getReadableDatabase().rawQuery("SELECT id FROM state WHERE cache = ?",
                new String[]{String.valueOf(state)});
        List<String> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    synchronized List<String> cachedIds() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM state WHERE cache != 0", null);
        List<String> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    // ---- SponsorBlock cache -------------------------------------------------

    /** Segments serialised as "start:end,start:end" in seconds. */
    synchronized void putSegs(String id, String segs) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("segs", segs);
        cv.put("at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("sb", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Whether this video's segments are due ({@link Sponsor#dueSql}); one not in the feed counts as old. */
    synchronized boolean segsDue(String id, long now) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM (SELECT ? AS id) x LEFT JOIN vid v ON v.id = x.id"
                        + " LEFT JOIN sb ON sb.id = x.id WHERE " + Sponsor.dueSql(now),
                new String[]{id});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    synchronized String segs(String id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT segs FROM sb WHERE id=?",
                new String[]{id});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    synchronized void forget(String id) {
        getWritableDatabase().delete("state", "id=?", new String[]{id});
        getWritableDatabase().delete("sb", "id=?", new String[]{id});
    }
}
