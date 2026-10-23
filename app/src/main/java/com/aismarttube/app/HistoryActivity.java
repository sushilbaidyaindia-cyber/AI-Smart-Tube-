package com.aismarttube.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Native list for history / watch-later / liked. Plain rows (one spoken label per video) so TalkBack stays fast.
 * History is grouped by day, each day has its own "delete this day" button, and the top bar clears everything.
 */
public class HistoryActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private static final String EXTRA_KEY = "key";
    private static final String[] MONTHS = {"জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
            "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"};

    static Intent open(Context c, String key) {
        return new Intent(c, HistoryActivity.class).putExtra(EXTRA_KEY, key);
    }

    private String key = "history";
    private final List<VideoItem> items = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();   // same order as items; 0 = unknown (older entries)
    private final List<Object> rows = new ArrayList<>();  // Header or VideoItem
    private RecyclerView list;
    private TextView status;
    private Adapter adapter;

    private static final class Header {
        final String label; final long dayKey; final int count;
        Header(String label, long dayKey, int count) { this.label = label; this.dayKey = dayKey; this.count = count; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_history);
        String k = getIntent().getStringExtra(EXTRA_KEY);
        if ("later".equals(k) || "liked".equals(k)) key = k;

        TextView bar = findViewById(R.id.barTitle);
        bar.setText(title());
        status = findViewById(R.id.status);
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemViewCacheSize(8);
        adapter = new Adapter();
        list.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        Button clear = findViewById(R.id.btnClearAll);
        clear.setText("history".equals(key) ? "সব ইতিহাস মুছুন" : "খালি করুন");
        clear.setContentDescription("history".equals(key) ? "সব ইতিহাস মুছে ফেলুন" : title() + " খালি করুন");
        clear.setOnClickListener(v -> confirm(
                "history".equals(key) ? "সব ইতিহাস মুছবেন?" : title() + " খালি করবেন?",
                "history".equals(key) ? "দেখা ভিডিওর তালিকা ও কোথায় থেমেছিলেন, সব মুছে যাবে।" : "এই তালিকার সব ভিডিও সরে যাবে।",
                this::clearAll));
        load();
    }

    private String title() {
        if ("later".equals(key)) return "পরে দেখব";
        if ("liked".equals(key)) return "পছন্দের ভিডিও";
        return "ইতিহাস";
    }

    // ------------------------------------------------------------------ data

    private void load() {
        items.clear();
        times.clear();
        try {
            String s = Store.get(this, key);
            JSONArray a = s.isEmpty() ? new JSONArray() : new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                VideoItem v = VideoItem.fromJson(o);
                if (v == null || v.url.isEmpty()) continue;
                items.add(v);
                times.add(o.optLong("t", 0));
            }
        } catch (Throwable ignored) {}
        buildRows();
    }

    private static long dayKey(long t) {
        if (t <= 0) return 0;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String bn(String t) {
        String d = "০১২৩৪৫৬৭৮৯";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            s.append(ch >= '0' && ch <= '9' ? d.charAt(ch - '0') : ch);
        }
        return s.toString();
    }

    private static String dayLabel(long dk) {
        if (dk == 0) return "আগের";
        long today = dayKey(System.currentTimeMillis());
        if (dk == today) return "আজ";
        if (dk == dayKey(today - 12 * 3600_000L)) return "গতকাল";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dk);
        return bn(c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR));
    }

    private void buildRows() {
        rows.clear();
        if ("history".equals(key)) {
            // entries are newest first; a header whenever the day changes
            int i = 0;
            while (i < items.size()) {
                long dk = dayKey(times.get(i));
                int j = i;
                while (j < items.size() && dayKey(times.get(j)) == dk) j++;
                rows.add(new Header(dayLabel(dk), dk, j - i));
                for (int k = i; k < j; k++) rows.add(items.get(k));
                i = j;
            }
        } else {
            rows.addAll(items);
        }
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            status.setVisibility(View.VISIBLE);
            status.setText("এখানে এখন কিছু নেই");
            findViewById(R.id.btnClearAll).setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            findViewById(R.id.btnClearAll).setVisibility(View.VISIBLE);
        }
    }

    private void write(JSONArray a) {
        Store.set(this, key, a.toString());
    }

    private void clearAll() {
        write(new JSONArray());
        if ("history".equals(key)) Store.set(this, "pos", "");
        load();
        announce("history".equals(key) ? "সব ইতিহাস মুছে ফেলা হয়েছে" : "তালিকা খালি হয়েছে");
    }

    private void deleteDay(long dk) {
        try {
            String s = Store.get(this, key);
            JSONArray a = s.isEmpty() ? new JSONArray() : new JSONArray(s);
            JSONArray out = new JSONArray();
            int gone = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (dayKey(o.optLong("t", 0)) == dk) {
                    gone++;
                    Store.putPos(this, o.optString("url"), 0);
                } else out.put(o);
            }
            write(out);
            load();
            announce(dayLabel(dk) + "-এর " + bn(String.valueOf(gone)) + "টি ভিডিও ইতিহাস থেকে মুছে গেছে");
        } catch (Throwable t) {
            announce("মোছা গেল না");
        }
    }

    private void deleteOne(VideoItem v) {
        try {
            String s = Store.get(this, key);
            JSONArray a = s.isEmpty() ? new JSONArray() : new JSONArray(s);
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !v.url.equals(o.optString("url"))) out.put(o);
            }
            write(out);
            load();
            announce("তালিকা থেকে সরানো হয়েছে");
        } catch (Throwable ignored) {}
    }

    private void announce(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        list.announceForAccessibility(m);
    }

    private void confirm(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("মুছুন", (d, w) -> yes.run())
                .setNegativeButton("বাতিল", null)
                .show();
    }

    // ---------------------------------------------------------------- actions

    @Override
    public void onOpen(VideoItem item, int position) {
        NativeFeedLoader.prefetch(item.url);
        int idx = 0;
        for (int k = 0; k < items.size(); k++) if (items.get(k).url.equals(item.url)) { idx = k; break; }
        startActivity(PlayerActivity.intent(this, new ArrayList<>(items), idx));
    }

    private void rowMenu(VideoItem v) {
        new AlertDialog.Builder(this)
                .setTitle(v.title)
                .setItems(new CharSequence[]{"তালিকা থেকে সরান"}, (d, w) -> deleteOne(v))
                .show();
    }

    // ---------------------------------------------------------------- adapter

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof Header ? 0 : 1; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == 0) {
                LinearLayout row = new LinearLayout(parent.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                row.setPadding(dp(16), dp(12), dp(8), dp(4));
                TextView t = new TextView(parent.getContext());
                t.setId(android.R.id.text1);
                t.setTextColor(0xFFEAF5F3);
                t.setTextSize(18);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
                Button del = new Button(parent.getContext());
                del.setId(android.R.id.button1);
                del.setAllCaps(false);
                del.setTextColor(0xFFEAF5F3);
                del.setBackgroundColor(0xFF7A2B2B);
                del.setMinHeight(dp(48));
                del.setMinimumHeight(dp(48));
                del.setPadding(dp(12), 0, dp(12), 0);
                del.setText("এই দিনের ইতিহাস মুছুন");
                row.addView(del, new LinearLayout.LayoutParams(-2, dp(48)));
                return new RecyclerView.ViewHolder(row) {};
            }
            View v = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_video_card, parent, false);
            return new VideoAdapter.VH(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int p) {
            Object o = rows.get(p);
            if (o instanceof Header) {
                final Header hd = (Header) o;
                TextView t = h.itemView.findViewById(android.R.id.text1);
                t.setText(hd.label + " · " + bn(String.valueOf(hd.count)) + "টি");
                t.setContentDescription(hd.label + ", " + bn(String.valueOf(hd.count)) + "টি ভিডিও");
                ViewCompat.setAccessibilityHeading(t, true);
                Button del = h.itemView.findViewById(android.R.id.button1);
                del.setText(hd.dayKey == 0 ? "আগের ইতিহাস মুছুন" : "এই দিনের ইতিহাস মুছুন");
                del.setContentDescription(hd.label + "-এর ইতিহাস মুছুন");
                del.setOnClickListener(v -> confirm(hd.label + "-এর ইতিহাস মুছবেন?",
                        "ওই অংশের " + bn(String.valueOf(hd.count)) + "টি ভিডিও তালিকা থেকে সরে যাবে।",
                        () -> deleteDay(hd.dayKey)));
            } else {
                final VideoItem v = (VideoItem) o;
                VideoAdapter.bind((VideoAdapter.VH) h, v, HistoryActivity.this);
                h.itemView.setOnLongClickListener(x -> { rowMenu(v); return true; });
                ViewCompat.addAccessibilityAction(h.itemView, "তালিকা থেকে সরান", (view, args) -> {
                    confirm("তালিকা থেকে সরাবেন?", v.title, () -> deleteOne(v));
                    return true;
                });
            }
        }
    }
}
