package org.tinytube.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Search beyond the subscription list. Reuses the feed's row layout. */
public final class Search extends Activity implements View.OnClickListener,
        View.OnLongClickListener, android.content.DialogInterface.OnClickListener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText query;
    private TextView status;
    private Adapter adapter;
    /** Results added to the feed from here, so their button shows a check. */
    private final java.util.Set<String> added = new java.util.HashSet<>();
    /** The result whose channel the confirmation dialog is about. */
    private Vid pendingSub;

    private static final android.content.res.ColorStateList TINT_IDLE =
            android.content.res.ColorStateList.valueOf(0xFF9A9A9A);
    private static final android.content.res.ColorStateList TINT_ACTIVE =
            android.content.res.ColorStateList.valueOf(0xFFCC0000);

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Thumbs.init(this);
        setContentView(R.layout.search);
        query = findViewById(R.id.query);
        status = findViewById(R.id.status);
        ListView list = findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                startActivity(new Intent(Search.this, Play.class)
                        .putExtra("id", adapter.items.get(pos).id));
            }
        });

        // The screen exists to type into: focus the field and bring the keyboard up with it.
        // In touch mode nothing gets focus on its own, and the manifest's stateVisible only
        // shows the keyboard for a focused editable view.
        query.requestFocus();
        android.view.WindowInsetsController ime = getWindow().getInsetsController();
        if (ime != null) ime.show(android.view.WindowInsets.Type.ime());

        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    run(query.getText().toString().trim());
                    return true;
                }
                return false;
            }
        });
    }

    private void run(final String q) {
        if (q.isEmpty()) return;
        status.setVisibility(View.VISIBLE);
        status.setText("Searching…");
        new Thread(new Runnable() {
            @Override public void run() {
                List<Vid> results;
                String error = null;
                try {
                    results = Yt.search(q);
                } catch (Exception e) {
                    results = new ArrayList<>();
                    error = String.valueOf(e.getMessage());
                }
                final List<Vid> r = results;
                final String err = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        adapter.items = r;
                        adapter.notifyDataSetChanged();
                        status.setText(err != null ? "Search failed: " + err
                                : r.isEmpty() ? "No results" : r.size() + " results");
                    }
                });
            }
        }, "search").start();
    }

    private static final ViewOutlineProvider ROUNDED = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline outline) {
            float r = 9 * view.getResources().getDisplayMetrics().density;
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
        }
    };

    /** The plus: this one video joins the feed, dated now so it sits at the top. */
    @Override
    public void onClick(View v) {
        final Vid vid = (Vid) v.getTag();
        if (vid == null) return;
        final Db db = Db.get(this);
        if (added.contains(vid.id) || db.isKnown(vid.id)) {
            added.add(vid.id);
            adapter.notifyDataSetChanged();
            toast("Already in the feed");
            return;
        }
        vid.pub = System.currentTimeMillis();
        db.putVideos(java.util.Collections.singletonList(vid));
        Sync.feedVersion++;
        added.add(vid.id);
        adapter.notifyDataSetChanged();
        toast("Added to the feed");
        // Ready before it is tapped, like anything else in the feed.
        Heads.enqueue(this, java.util.Collections.singletonList(vid.id));
        new Thread(new Runnable() {
            @Override public void run() { Sponsor.fetchAndStore(Search.this, vid.id); }
        }, "sb").start();
    }

    /** Long press on the plus: subscribe to the channel, after asking. */
    @Override
    public boolean onLongClick(View v) {
        Vid vid = (Vid) v.getTag();
        if (vid == null || vid.chanId.isEmpty()) return false;
        if (Db.get(this).subscribed(vid.chanId)) {
            toast("Already subscribed to " + vid.chan);
            return true;
        }
        pendingSub = vid;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Subscribe to " + vid.chan + "?")
                .setMessage("Its videos join the feed and it is polled like the others.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Subscribe", this)
                .show();
        return true;
    }

    /** The dialog's "Subscribe". */
    @Override
    public void onClick(android.content.DialogInterface dialog, int which) {
        if (pendingSub == null) return;
        Sync.subscribe(this, pendingSub.chanId, pendingSub.chan);
        toast("Subscribed to " + pendingSub.chan);
        pendingSub = null;
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private final class Adapter extends BaseAdapter {
        List<Vid> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = LayoutInflater.from(Search.this).inflate(R.layout.row, parent, false);
                View box = convert.findViewById(R.id.thumb_box);
                box.setOutlineProvider(ROUNDED);
                box.setClipToOutline(true);
                // One action per result, where the feed has its watched button: add to the
                // feed (tap), or subscribe to the channel (long press, confirmed).
                convert.findViewById(R.id.btn_cache).setVisibility(View.GONE);
                convert.findViewById(R.id.progress).setVisibility(View.GONE);
                ImageView add = convert.findViewById(R.id.btn_watch);
                add.setContentDescription("Add to feed");
                add.setOnClickListener(Search.this);
                add.setOnLongClickListener(Search.this);
            }
            Vid v = items.get(position);
            ImageView add = convert.findViewById(R.id.btn_watch);
            add.setTag(v);
            boolean inFeed = added.contains(v.id);
            add.setImageResource(inFeed ? R.drawable.ic_check : R.drawable.ic_plus);
            add.setImageTintList(inFeed ? TINT_ACTIVE : TINT_IDLE);
            ((TextView) convert.findViewById(R.id.title)).setText(v.title);
            ((TextView) convert.findViewById(R.id.chan)).setText(v.chan);
            ((TextView) convert.findViewById(R.id.meta)).setText(v.views);
            TextView dur = convert.findViewById(R.id.dur);
            dur.setText(Feed.clock(v.dur));
            Thumbs.into((ImageView) convert.findViewById(R.id.thumb), v.id);
            return convert;
        }
    }
}
