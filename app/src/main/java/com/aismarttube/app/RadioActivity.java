package com.aismarttube.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Radio tab: the Bengali All India Radio stations as a native list, with a small player bar (previous / play-pause / next). */
public class RadioActivity extends AppCompatActivity {

    private static final class Row {
        final int type;          // 0 group header, 1 station, 2 "add my own"
        final String text;
        final int index;         // index in RadioStations.all()
        Row(int type, String text, int index) { this.type = type; this.text = text; this.index = index; }
    }

    private final List<Row> rows = new ArrayList<>();
    private List<RadioStations.Station> stations = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private View nowBar;
    private TextView nowText;
    private Button rPlay;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_radio);
        nowBar = findViewById(R.id.nowBar);
        nowText = findViewById(R.id.nowText);
        rPlay = findViewById(R.id.rPlay);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemViewCacheSize(8);
        adapter = new Ad();
        list.setAdapter(adapter);

        findViewById(R.id.rPrev).setOnClickListener(v -> RadioHub.step(-1));
        findViewById(R.id.rNext).setOnClickListener(v -> RadioHub.step(1));
        rPlay.setOnClickListener(v -> RadioHub.toggle());
        TabBar.setup(this, TabBar.RADIO);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioHub.ui = () -> runOnUiThread(this::refresh);
        build();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        RadioHub.ui = null;
    }

    @Override
    public void onBackPressed() {
        TabBar.go(this, TabBar.RADIO, TabBar.HOME);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void build() {
        stations = RadioStations.all(this);
        rows.clear();
        String last = null;
        for (int i = 0; i < stations.size(); i++) {
            RadioStations.Station s = stations.get(i);
            if (!s.group.equals(last)) { rows.add(new Row(0, s.group, -1)); last = s.group; }
            rows.add(new Row(1, s.name, i));
        }
        if (!"আমার রেডিও".equals(last)) rows.add(new Row(0, "আমার রেডিও", -1));
        rows.add(new Row(2, "নিজের স্টেশন যোগ করুন", -1));
        adapter.notifyDataSetChanged();
    }

    private String stateText() {
        switch (RadioHub.state) {
            case "loading": return "লোড হচ্ছে…";
            case "playing": return "চলছে";
            case "paused": return "থামানো";
            case "error": return "চলছে না। আবার চালান চাপুন, অথবা অন্য স্টেশন বাছুন।";
            default: return "";
        }
    }

    private void refresh() {
        if (RadioHub.active() || "error".equals(RadioHub.state)) {
            if (RadioHub.idx >= 0) {
                nowBar.setVisibility(View.VISIBLE);
                nowText.setText(RadioHub.stationName(this) + " — " + stateText());
                boolean on = RadioHub.isPlaying();
                rPlay.setText(on ? "থামান" : "চালান");
                rPlay.setContentDescription(on ? "থামান" : "চালান");
                adapter.notifyDataSetChanged();
                return;
            }
        }
        nowBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void addDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText n = new EditText(this);
        n.setHint("স্টেশনের নাম");
        final EditText u = new EditText(this);
        u.setHint("স্ট্রিমের লিংক (https://…)");
        u.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(n);
        box.addView(u);
        new AlertDialog.Builder(this)
                .setTitle("নিজের স্টেশন")
                .setView(box)
                .setPositiveButton("যোগ করুন", (d, w) -> {
                    String name = n.getText().toString().trim();
                    String url = u.getText().toString().trim();
                    if (name.isEmpty() || !url.startsWith("http")) {
                        nowText.announceForAccessibility("নাম আর https লিংক দুটোই লাগবে");
                        return;
                    }
                    RadioStations.addCustom(this, name, url);
                    build();
                    refresh();
                    nowText.announceForAccessibility(name + " যোগ হয়েছে");
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void removeDialog(final int stationIndex) {
        final RadioStations.Station s = stations.get(stationIndex);
        new AlertDialog.Builder(this)
                .setTitle("স্টেশন মুছবেন?")
                .setMessage(s.name)
                .setPositiveButton("মুছুন", (d, w) -> {
                    if (RadioHub.idx == stationIndex) RadioHub.stop(false);
                    RadioStations.removeCustom(this, stationIndex - RadioStations.builtinCount());
                    if (RadioHub.idx > stationIndex) RadioHub.idx = RadioHub.idx - 1;
                    build();
                    refresh();
                })
                .setNegativeButton("থাক", null)
                .show();
    }

    private class Ad extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int p) { return rows.get(p).type; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            TextView v = new TextView(parent.getContext());
            v.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            v.setTextColor(0xFFEAF5F3);
            v.setGravity(Gravity.CENTER_VERTICAL);
            if (type == 0) {
                v.setTextSize(15);
                v.setTextColor(0xFFFFB84A);
                v.setTypeface(null, android.graphics.Typeface.BOLD);
                v.setPadding(dp(20), dp(16), dp(20), dp(4));
            } else {
                v.setTextSize(18);
                v.setMinHeight(dp(60));
                v.setPadding(dp(20), dp(10), dp(20), dp(10));
                v.setClickable(true);
                v.setFocusable(true);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
            }
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            final Row r = rows.get(pos);
            TextView v = (TextView) h.itemView;
            if (r.type == 0) {
                v.setText(r.text);
                v.setContentDescription(r.text);
                ViewCompat.setAccessibilityHeading(v, true);
                v.setOnClickListener(null);
                return;
            }
            if (r.type == 2) {
                v.setText("＋ " + r.text);
                v.setContentDescription(r.text);
                v.setOnClickListener(x -> addDialog());
                return;
            }
            boolean cur = RadioHub.idx == r.index && RadioHub.active();
            v.setText(cur ? "▶ " + r.text : r.text);
            v.setContentDescription(cur ? r.text + ", এখন " + stateText() : r.text);
            v.setOnClickListener(x -> {
                if (RadioHub.idx == r.index && RadioHub.active()) RadioHub.toggle();
                else RadioHub.start(RadioActivity.this, r.index);
            });
            if (stations.get(r.index).custom) {
                ViewCompat.addAccessibilityAction(v, "স্টেশন মুছুন", (view, args) -> {
                    removeDialog(r.index);
                    return true;
                });
                v.setOnLongClickListener(x -> { removeDialog(r.index); return true; });
            } else {
                v.setOnLongClickListener(null);
            }
        }
    }
}
