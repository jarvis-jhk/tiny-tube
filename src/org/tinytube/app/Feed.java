package org.tinytube.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The feed: the app's home screen and the only portrait activity. */
public final class Feed extends Activity {

    /** How often, while the feed is on screen, to let {@link Sync} pick up a couple of channels. */
    private static final long TICK_MS = 20_000;
    /** How long a button's ripple takes to fade; a row marked watched leaves only after it. */
    private static final long RIPPLE_MS = 300;

    private ListView list;
    private TextView empty;
    private TextView warn;
    private Adapter adapter;
    /** Show full-width thumbnails when the current view has at most three videos. */
    private boolean tiles;
    /**
     * Which of the four views the filter button is on. Not remembered across launches on
     * purpose: a launch with no network opens on the downloaded videos, any other on the
     * ready ones.
     */
    private int view;
    /**
     * Whether there was Wi-Fi when {@link #view} was last picked for it. Coming back to the feed
     * with that changed picks again; unchanged, a view chosen by hand stays.
     */
    private boolean viewOnline;
    /** The list is being flung or dragged; an in-place refresh waits until it settles. */
    private boolean scrolling;
    /**
     * A finger is on the screen; a re-query waits until it lifts. Rebinding the rows under a
     * resting finger detaches the button it is pressing, which cancels the button's touch, and
     * the lift then reaches the list itself as a tap on the row: a long press on the cache
     * button -- whose own reload lands 800 ms later -- went on to open the video.
     */
    private boolean touching;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int action = e.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) touching = true;
        else if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) touching = false;
        return super.dispatchTouchEvent(e);
    }
    /** The {@link Sync#feedVersion} the rows on screen were queried at. */
    private int shownFeedVersion;
    /** The {@link Cacher#cacheVersion} the rows on screen were queried at. */
    private int shownCacheVersion;

    private long createdAt;

    @Override
    protected void onCreate(Bundle saved) {
        createdAt = android.os.SystemClock.uptimeMillis();
        super.onCreate(saved);
        Thumbs.init(this);
        viewOnline = Sync.online(this);
        view = viewOnline ? Db.VIEW_READY : Db.VIEW_OFFLINE;

        setContentView(R.layout.feed);
        Dbg.v("feed: inflated at " + (android.os.SystemClock.uptimeMillis() - createdAt) + "ms");
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        warn = findViewById(R.id.warn);

        applyInsets();

        adapter = new Adapter();
        list.setAdapter(adapter);
        hideFabsWhileScrolling();
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                Vid vid = adapter.items.get(pos);
                startActivity(new Intent(Feed.this, Play.class).putExtra("id", vid.id));
            }
        });

        findViewById(R.id.fab_search).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Feed.this, Search.class));
            }
        });
        findViewById(R.id.fab_watched).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                view = (view + 1) % 4;
                syncFabState();
                reload();
            }
        });
        syncFabState();
        reload();
        Dbg.i("feed: " + adapter.items.size() + " rows queried in "
                + (android.os.SystemClock.uptimeMillis() - createdAt) + "ms");
        list.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        list.getViewTreeObserver().removeOnPreDrawListener(this);
                        Dbg.i("feed: first pre-draw at "
                                + (android.os.SystemClock.uptimeMillis() - createdAt)
                                + "ms; inflate " + (dbgInflateNs / 1000000) + "ms x" + dbgInflates
                                + ", bind " + (dbgBindNs / 1000000) + "ms x" + dbgBinds);
                        return true;
                    }
                });
        list.post(new Runnable() {
            @Override public void run() {
                Dbg.i("feed: drawn " + (android.os.SystemClock.uptimeMillis() - createdAt)
                        + "ms after onCreate");
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        Dbg.i("feed: thumbs after 3s - memory=" + Thumbs.dbgFromMemory
                                + " disk=" + Thumbs.dbgFromDisk
                                + " network=" + Thumbs.dbgFromNetwork);
                    }
                }, 3000);
                // Only once the list is on screen: warming the identity fetches a ~900 KB page,
                // and doing that while the first frame is still being built just steals CPU.
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        Ident.warmUp(Feed.this);
                        Cacher.resumeInterrupted(Feed.this);
                        prime();
                        Cacher.reconcileAuto(Feed.this);
                        Sponsor.warmUp();
                        Heads.backfill(Feed.this);
                        Thumbs.backfill(Feed.this);
                        Screen.ensure(Feed.this);            // the feed keeps up while the app is closed
                        Refresh.schedule(Feed.this, false);  // ...and Screen stays up
                    }
                }, 250);
            }
        });
        // A finger landing on a row is a tap in the making: start resolving its streams now,
        // ~150 ms before the player activity is even created.
        list.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    int pos = list.pointToPosition((int) e.getX(), (int) e.getY());
                    if (pos >= 0 && pos < adapter.items.size() && Sync.online(Feed.this)) {
                        Vid row = adapter.items.get(pos);
                        Yt.prefetch(Feed.this, row.id, false, row.pos);
                    }
                }
                return false;
            }
        });
    }

    /** How many videos are primed while the feed is on screen. */
    private static final int PRIME_ROWS = 8;

    /**
     * Uses the time the feed is on screen to get ready for watching: the identity is warmed
     * separately, and here the likeliest taps get their streams resolved and -- on an unmetered
     * network -- their extractors opened and seeked, so playback starts in the time it takes to
     * bring up the codecs. Half-watched rows come first, wherever they are in the list: a video
     * someone stopped in the middle is the one they come back for. Then the top rows. Idempotent
     * and cheap once done: the results live in Yt's cache until the URLs expire.
     */
    private void prime() {
        if ((view != Db.VIEW_READY && view != Db.VIEW_UNWATCHED) || !Sync.online(this)) return;
        boolean prepare = Sync.unmetered(this);
        int primed = 0;
        for (int pass = 0; pass < 2 && primed < PRIME_ROWS; pass++) {
            for (int i = 0; i < adapter.items.size() && primed < PRIME_ROWS; i++) {
                Vid v = adapter.items.get(i);
                boolean halfWatched = v.pos > 0;
                if (pass == 0 ? !halfWatched : halfWatched) continue;
                Yt.prefetch(this, v.id, prepare, v.pos);
                primed++;
            }
        }
    }

    /**
     * Refreshing is invisible by design: no pull gesture, no spinner, no counter. While the feed
     * is open the app quietly polls a couple of channels, which is also when the phone is already
     * awake, so it costs nothing extra. Whatever a poll finds is folded into the list on a later
     * tick, once the list is at rest, without moving what is on screen.
     */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            boolean stale = Sync.feedVersion != shownFeedVersion
                    || Cacher.cacheVersion != shownCacheVersion;
            if (stale && !scrolling && !touching) reloadInPlace();
            else updateWarning();   // a channel recovering changes nothing else on screen
            Sync.tick(Feed.this);
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * Re-queries without disturbing the reader. New rows land above the viewport, which would
     * shift everything down by a row per arrival; instead the row at the top of the screen is
     * found again and pinned where it was. At the very top of the list the new rows are simply
     * allowed to appear, which is the point of them.
     */
    private void reloadInPlace() {
        int first = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        int y = top == null ? 0 : top.getTop() - list.getPaddingTop();
        boolean atTop = first == 0 && y >= 0;
        String anchor = first < adapter.items.size() ? adapter.items.get(first).id : null;
        int before = adapter.items.size();
        reload();
        if (!atTop && anchor != null) {
            for (int i = 0; i < adapter.items.size(); i++) {
                if (anchor.equals(adapter.items.get(i).id)) {
                    if (i != first) list.setSelectionFromTop(i, y);
                    break;
                }
            }
        }
        Dbg.v("feed: refreshed in place, " + before + " -> " + adapter.items.size() + " rows");
        prime();
        Cacher.reconcileAuto(this);
    }

    private final android.os.Handler handler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final Runnable reloader = new Runnable() {
        @Override public void run() {
            if (touching) handler.postDelayed(this, 100); else reload();
        }
    };
    /**
     * A download changed state. Coalesced, because auto-caching reconciles several videos within
     * a moment of each other, and posted rather than run where it happens: the change may come
     * from the download's own thread.
     */
    private final Runnable cachePoke = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(cacheRefresh);
            handler.postDelayed(cacheRefresh, 250);
        }
    };
    /** Mid-scroll the refresh is skipped; the ticker's version check picks it up later. */
    private final Runnable cacheRefresh = new Runnable() {
        @Override public void run() {
            if (touching) handler.postDelayed(this, 100);
            else if (!scrolling) reloadInPlace();
        }
    };

    /**
     * Android 15 forces edge-to-edge, so the bars are painted over the list. Push the content in
     * by the real inset instead of reserving a fixed strip.
     */
    private void applyInsets() {
        final View fabs = findViewById(R.id.fabs);
        final ViewGroup.MarginLayoutParams fabLp =
                (ViewGroup.MarginLayoutParams) fabs.getLayoutParams();
        final int fabMargin = fabLp.bottomMargin;
        listPaddingTop = list.getPaddingTop();
        warnPaddingTop = warn.getPaddingTop();
        final int listBottom = list.getPaddingBottom();
        findViewById(android.R.id.content).setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
                    @Override
                    public android.view.WindowInsets onApplyWindowInsets(
                            View v, android.view.WindowInsets insets) {
                        android.graphics.Insets bars = insets.getInsets(
                                android.view.WindowInsets.Type.systemBars());
                        barsTop = bars.top;
                        list.setPadding(list.getPaddingLeft(), listPaddingTop,
                                list.getPaddingRight(), listBottom + bars.bottom);
                        applyTopInset();
                        fabLp.bottomMargin = fabMargin + bars.bottom;
                        fabs.setLayoutParams(fabLp);
                        return insets;
                    }
                });
    }

    private int barsTop;
    private int listPaddingTop;
    private int warnPaddingTop;

    /**
     * The status bar's inset goes to whatever is at the top of the screen. Normally that is the
     * list, which keeps its rows scrolling out under the bar; while the warning is up it is the
     * warning, which is opaque and sits above the list instead.
     */
    private void applyTopInset() {
        boolean warning = warn.getVisibility() == View.VISIBLE;
        warn.setPadding(warn.getPaddingLeft(), warnPaddingTop + (warning ? barsTop : 0),
                warn.getPaddingRight(), warn.getPaddingBottom());
        list.setPadding(list.getPaddingLeft(), listPaddingTop + (warning ? 0 : barsTop),
                list.getPaddingRight(), list.getPaddingBottom());
    }

    /** The floating controls get out of the way while the list is moving. */
    private void hideFabsWhileScrolling() {
        final View fabs = findViewById(R.id.fabs);
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView v, int state) {
                scrolling = state != SCROLL_STATE_IDLE;
                fabs.animate().alpha(scrolling ? 0f : 1f).setDuration(140).start();
            }

            @Override
            public void onScroll(android.widget.AbsListView v, int first, int visible, int total) { }
        });
    }

    /** onResume fires straight after onCreate, whose reload() is still current. */
    private boolean freshlyCreated = true;

    @Override
    protected void onResume() {
        super.onResume();
        // Playback may have changed watched state or the resume position.
        if (freshlyCreated) freshlyCreated = false;
        else {
            // Back from the player or another app: if Wi-Fi came or went meanwhile, the view
            // follows it as at launch.
            boolean online = Sync.online(this);
            if (online != viewOnline) {
                viewOnline = online;
                view = online ? Db.VIEW_READY : Db.VIEW_OFFLINE;
                syncFabState();
                Dbg.i("feed: wi-fi " + (online ? "back" : "gone") + ", view " + view);
            }
            reload(); prime(); Cacher.reconcileAuto(this); Heads.backfill(this);
        }
        Cacher.onCacheChange = cachePoke;    // downloads colour their rows while we are up
        handler.removeCallbacks(ticker);
        // Polling is opportunistic, so it can wait until the window is settled.
        handler.postDelayed(ticker, 1500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Cacher.onCacheChange = null;         // and do not hold this activity once it is gone
        handler.removeCallbacks(cacheRefresh);
        handler.removeCallbacks(ticker);
    }

    /** The filter button: checked eye (ready), dim eye (unwatched), bright eye (all), disc (offline). */
    private void syncFabState() {
        ImageView fab = findViewById(R.id.fab_watched);
        fab.setImageResource(view == Db.VIEW_OFFLINE ? R.drawable.ic_uncache
                : view == Db.VIEW_READY ? R.drawable.ic_eye_ready : R.drawable.ic_eye);
        fab.setAlpha(view == Db.VIEW_UNWATCHED ? 0.45f : 1f);
        fab.setContentDescription(view == Db.VIEW_READY ? "Ready videos"
                : view == Db.VIEW_UNWATCHED ? "Unwatched videos"
                : view == Db.VIEW_ALL ? "All videos" : "Downloaded videos");
    }

    private void reload() {
        shownFeedVersion = Sync.feedVersion;
        shownCacheVersion = Cacher.cacheVersion;
        adapter.items = Db.get(this).feed(view);
        updateTileMode();
        adapter.notifyDataSetChanged();
        boolean none = adapter.items.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none) empty.setText(view == Db.VIEW_READY || view == Db.VIEW_UNWATCHED ? "All caught up."
                : view == Db.VIEW_ALL ? "Nothing here yet." : "Nothing downloaded.");
        updateWarning();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int tileImageHeight() {
        return Math.round((list.getWidth() - dp(24)) * 9f / 16f);
    }

    private void updateTileMode() {
        boolean showTiles = !adapter.items.isEmpty() && adapter.items.size() <= 3;
        if (tiles != showTiles) {
            tiles = showTiles;
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * A channel that has been failing to update for {@link Sync#STALE_WARN_MS} says so, above
     * the list, until it updates again. Everything else about refreshing is invisible on
     * purpose -- but a channel YouTube has broken looks exactly like a channel with nothing new,
     * and that is the one thing the feed cannot leave the reader to guess. Nothing to dismiss:
     * the bar is gone the moment a poll of that channel succeeds.
     */
    private void updateWarning() {
        long[] oldest = new long[1];
        List<String> stalled = Db.get(this).stalledChannels(
                System.currentTimeMillis() - Sync.STALE_WARN_MS, oldest);
        if (stalled.isEmpty()) {
            if (warn.getVisibility() != View.GONE) {
                warn.setVisibility(View.GONE);
                applyTopInset();
            }
            return;
        }
        StringBuilder who = new StringBuilder();
        for (int i = 0; i < stalled.size() && i < 3; i++) {
            if (i > 0) who.append(", ");
            who.append(stalled.get(i));
        }
        if (stalled.size() > 3) who.append(" and ").append(stalled.size() - 3).append(" more");
        long days = (System.currentTimeMillis() - oldest[0]) / (24 * 3600 * 1000L);
        warn.setText("No update from " + who + " for " + days + " days");
        if (warn.getVisibility() != View.VISIBLE) {
            warn.setVisibility(View.VISIBLE);
            applyTopInset();
            Dbg.i("feed: warning - no update from " + stalled + " for " + days + " days");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---- adapter ------------------------------------------------------------

    /** Rounds the thumbnail's corners. Shared: the provider is stateless. */
    private static final ViewOutlineProvider ROUNDED = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline outline) {
            float r = 9 * view.getResources().getDisplayMetrics().density;
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
        }
    };

    private long dbgInflateNs, dbgBindNs;
    private int dbgInflates, dbgBinds;

    private final class Adapter extends BaseAdapter {
        List<Vid> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return tiles ? 1 : 0; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null || ((Holder) convert.getTag()).tile != tiles) {
                long t0 = System.nanoTime();
                convert = LayoutInflater.from(Feed.this).inflate(
                        tiles ? R.layout.row_tile : R.layout.row, parent, false);
                h = new Holder(convert, tiles);
                convert.setTag(h);
                dbgInflateNs += System.nanoTime() - t0;
                dbgInflates++;
            } else {
                h = (Holder) convert.getTag();
            }
            long t1 = System.nanoTime();
            h.bind(items.get(position));
            dbgBindNs += System.nanoTime() - t1;
            dbgBinds++;
            return convert;
        }
    }

    private static final android.content.res.ColorStateList TINT_IDLE =
            android.content.res.ColorStateList.valueOf(0xFF9A9A9A);
    private static final android.content.res.ColorStateList TINT_ACTIVE =
            android.content.res.ColorStateList.valueOf(0xFFCC0000);
    private static final android.content.res.ColorStateList TINT_BUSY =
            android.content.res.ColorStateList.valueOf(0xFFE8B000);

    private final class Holder implements View.OnClickListener, View.OnLongClickListener,
            android.content.DialogInterface.OnClickListener {
        final View box;
        final ImageView thumb, share, cache, watch;
        final TextView title, chan, meta, dur;
        final View progress;
        final boolean tile;
        Vid vid;
        int iconShown;

        Holder(View root, boolean tile) {
            this.tile = tile;
            box = root.findViewById(R.id.thumb_box);
            if (tile) box.getLayoutParams().height = tileImageHeight();
            thumb = root.findViewById(R.id.thumb);
            dur = root.findViewById(R.id.dur);
            progress = root.findViewById(R.id.progress);
            title = root.findViewById(R.id.title);
            chan = root.findViewById(R.id.chan);
            meta = root.findViewById(R.id.meta);
            share = root.findViewById(R.id.btn_share);
            cache = root.findViewById(R.id.btn_cache);
            watch = root.findViewById(R.id.btn_watch);
            box.setOutlineProvider(ROUNDED);
            box.setClipToOutline(true);
            share.setOnClickListener(this);
            cache.setOnClickListener(this);
            cache.setOnLongClickListener(this);
            watch.setOnClickListener(this);
            watch.setOnLongClickListener(this);
        }

        /**
         * A long press on the cache button switches auto-caching for the row's channel; on the
         * watched button it offers to unsubscribe from the channel.
         */
        @Override
        public boolean onLongClick(View v) {
            if (v == watch) {
                new android.app.AlertDialog.Builder(Feed.this)
                        .setTitle("Unsubscribe from " + vid.chan + "?")
                        .setMessage("Its videos leave the feed and it is no longer polled.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Unsubscribe", this)
                        .show();
                return true;
            }
            if (v != cache) return false;
            boolean on = Cacher.toggleAuto(Feed.this, vid.chanId);
            toast(on ? "Auto-caching " + vid.chan + ": its two oldest unwatched videos"
                    + (Sync.unmetered(Feed.this) ? "" : " (downloads on Wi-Fi)")
                    : "No longer auto-caching " + vid.chan);
            handler.postDelayed(reloader, 800);       // the downloads it started show as busy
            return true;
        }

        /** The dialog's "Unsubscribe". */
        @Override
        public void onClick(android.content.DialogInterface dialog, int which) {
            Sync.unsubscribe(Feed.this, vid.chanId);
            toast("Unsubscribed from " + vid.chan);
            handler.postDelayed(reloader, 800);
        }

        void bind(Vid v) {
            vid = v;
            if (tile && box.getLayoutParams().height != tileImageHeight()) {
                box.getLayoutParams().height = tileImageHeight();
                box.requestLayout();
            }
            title.setText(v.title);
            chan.setText(v.chan);
            meta.setText(metaLine(v));
            dur.setText(clock(v.dur));
            dur.setVisibility(v.dur > 0 ? View.VISIBLE : View.GONE);
            Thumbs.into(thumb, v.id, tile);

            // Scale from the left edge. This also works before a full-width tile is measured.
            int totalMs = v.dur * 1000;
            float frac = totalMs > 0 ? Math.min(1f, v.pos / (float) totalMs) : 0f;
            progress.setPivotX(0);
            progress.setScaleX(frac);
            progress.setVisibility(frac > 0.005f ? View.VISIBLE : View.GONE);

            boolean cached = v.cache == Db.CACHE_DONE;
            int icon = cached ? R.drawable.ic_uncache
                    : v.auto ? R.drawable.ic_cache_auto : R.drawable.ic_cache;
            if (iconShown != icon) {
                iconShown = icon;
                cache.setImageResource(icon);
            }
            cache.setImageTintList(cached ? TINT_ACTIVE
                    : v.cache == Db.CACHE_BUSY ? TINT_BUSY : TINT_IDLE);
            watch.setImageTintList(v.watched ? TINT_ACTIVE : TINT_IDLE);
            // Watched rows are only visible in the "show watched" view; dim them there.
            float alpha = v.watched ? 0.45f : 1f;
            thumb.setAlpha(alpha);
            title.setAlpha(alpha);
            dur.setAlpha(alpha);
        }

        @Override
        public void onClick(View v) {
            if (v == share) {
                // The short link; opening it elsewhere, a browser included, is the share sheet's job.
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/" + vid.id);
                startActivity(Intent.createChooser(send, null));
                return;
            }
            Db db = Db.get(Feed.this);
            if (v == watch) {
                boolean now = !vid.watched;
                vid.watched = now;
                db.setWatched(vid.id, now);
                if (now) {
                    // Watched means gone: the download, a download in flight, and the head.
                    if (vid.cache == Db.CACHE_BUSY || vid.cache == Db.CACHE_DONE) {
                        Cacher.discard(Feed.this, vid.id);
                        vid.cache = Db.CACHE_NONE;
                    }
                    Heads.delete(Feed.this, vid.id);
                    Cacher.reconcileAuto(Feed.this);     // the channel's next oldest follows
                }
                // The row shows its new state until the ripple has faded, then the list is
                // re-queried. Removing it at once put the ripple on the row that slid into its
                // place.
                adapter.notifyDataSetChanged();
                if (now) handler.postDelayed(reloader, RIPPLE_MS);
            } else if (v == cache) {
                if (vid.cache == Db.CACHE_DONE) {
                    Cacher.uncache(Feed.this, vid.id);
                    vid.cache = Db.CACHE_NONE;
                    toast("Removed download");
                } else if (vid.cache == Db.CACHE_BUSY) {
                    Cacher.cancel(Feed.this, vid.id);
                    vid.cache = Db.CACHE_NONE;
                    toast("Download cancelled");
                } else {
                    Cacher.cache(Feed.this, vid.id);
                    vid.cache = Db.CACHE_BUSY;
                    toast("Downloading…");
                }
                adapter.notifyDataSetChanged();
            }
        }
    }

    // ---- formatting ---------------------------------------------------------

    private static String metaLine(Vid v) {
        String when = ago(v.pub);
        StringBuilder sb = new StringBuilder(48);
        if (v.views != null && !v.views.isEmpty()) sb.append(v.views);
        if (!when.isEmpty()) {
            if (sb.length() > 0) sb.append("  •  ");
            sb.append(when);
        }
        // How far in you already are. Only worth saying when it is a real position, not the few
        // seconds a mis-tap leaves behind.
        if (v.pos > 15000) {
            if (sb.length() > 0) sb.append("  •  ");
            sb.append(clock(v.pos / 1000)).append(" watched");
        }
        return sb.toString();
    }

    /**
     * "3 days ago", by hand. {@link DateUtils#getRelativeTimeSpanString} builds a Formatter and
     * does locale lookups on every call, which is measurable when it runs once per visible row
     * during the first frame.
     */
    static String ago(long timestamp) {
        if (timestamp <= 0) return "";
        long seconds = (System.currentTimeMillis() - timestamp) / 1000;
        if (seconds < 60) return "just now";
        long value;
        String unit;
        if (seconds < 3600) { value = seconds / 60; unit = "minute"; }
        else if (seconds < 86400) { value = seconds / 3600; unit = "hour"; }
        else if (seconds < 7 * 86400) { value = seconds / 86400; unit = "day"; }
        else if (seconds < 30 * 86400) { value = seconds / (7 * 86400); unit = "week"; }
        else if (seconds < 365 * 86400) { value = seconds / (30 * 86400); unit = "month"; }
        else { value = seconds / (365 * 86400); unit = "year"; }
        return value + " " + unit + (value == 1 ? " ago" : "s ago");
    }

    /** m:ss or h:mm:ss, built by hand -- String.format is heavy for something on every row. */
    static String clock(int seconds) {
        if (seconds <= 0) return "";
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder(8);
        if (h > 0) {
            sb.append(h).append(':');
            if (m < 10) sb.append('0');
        }
        sb.append(m).append(':');
        if (s < 10) sb.append('0');
        return sb.append(s).toString();
    }

    static Context appOf(Context c) { return c.getApplicationContext(); }
}
