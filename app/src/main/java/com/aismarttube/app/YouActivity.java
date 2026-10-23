package com.aismarttube.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

/** "আপনি" tab: history, watch later, liked, settings, privacy. */
public class YouActivity extends AppCompatActivity {

    private static final String[] ROWS = {"ইতিহাস", "পরে দেখব", "পছন্দের ভিডিও", "সার্চ ইতিহাস", "সেটিংস", "গোপনীয়তা"};
    private static final String[] SUB = {"", "", "", "আগের সার্চ দেখুন বা মুছুন", "AI, সোর্স, প্লেব্যাক, অ্যাক্সেসিবিলিটি", "কোন তথ্য কোথায় যায়"};
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        ((TextView) findViewById(R.id.barTitle)).setText("আপনি");
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return ROWS.length; }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
                TextView v = new TextView(p.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                v.setMinHeight(dp(64));
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(20), dp(12), dp(20), dp(12));
                v.setTextColor(0xFFEAF5F3);
                v.setTextSize(18);
                v.setClickable(true);
                v.setFocusable(true);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                TextView v = (TextView) h.itemView;
                String t = ROWS[pos];
                String sub = pos < 3 ? count(pos) : SUB[pos];
                v.setText(sub.isEmpty() ? t : t + "\n" + sub);
                v.setContentDescription(sub.isEmpty() ? t : t + ", " + sub);
                v.setOnClickListener(x -> open(pos));
            }
        };
        list.setAdapter(adapter);
        TabBar.setup(this, TabBar.YOU);
    }

    @Override
    public void onBackPressed() {
        TabBar.go(this, TabBar.YOU, TabBar.HOME);   // back from a tab = home, like YouTube
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.notifyDataSetChanged();   // counts may have changed
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String bn(int n) {
        String d = "০১২৩৪৫৬৭৮৯", t = String.valueOf(n);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < t.length(); i++) s.append(d.charAt(t.charAt(i) - '0'));
        return s.toString();
    }

    private String count(int pos) {
        String key = pos == 0 ? "history" : pos == 1 ? "later" : "liked";
        try {
            String s = Store.get(this, key);
            return bn(s.isEmpty() ? 0 : new JSONArray(s).length()) + "টি ভিডিও";
        } catch (Throwable t) { return ""; }
    }

    private void open(int pos) {
        if (pos == 0) startActivity(HistoryActivity.open(this, "history"));
        else if (pos == 1) startActivity(HistoryActivity.open(this, "later"));
        else if (pos == 2) startActivity(HistoryActivity.open(this, "liked"));
        else if (pos == 3) startActivity(new Intent(this, SearchNativeActivity.class));
        else startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, pos == 4 ? "settings" : "privacy"));
    }
}
