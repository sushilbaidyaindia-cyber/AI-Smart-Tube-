package com.aismarttube.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.List;

/** Subscriptions tab: your channels, one button each. */
public class SubsActivity extends AppCompatActivity {

    private final List<String> names = new ArrayList<>();
    private JSONObject urls = new JSONObject();
    private TextView status;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        ((TextView) findViewById(R.id.barTitle)).setText("সাবস্ক্রিপশন");
        status = findViewById(R.id.status);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return names.size(); }

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
                final String n = names.get(pos);
                TextView v = (TextView) h.itemView;
                v.setText(n);
                v.setContentDescription(n + " চ্যানেল খুলুন");
                v.setOnClickListener(x -> openChannel(n));
                ViewCompat.addAccessibilityAction(v, "সাবস্ক্রিপশন বাতিল", (view, args) -> {
                    confirmRemove(n);
                    return true;
                });
            }
        };
        list.setAdapter(adapter);
        TabBar.setup(this, TabBar.SUBS);
    }

    @Override
    public void onBackPressed() {
        TabBar.go(this, TabBar.SUBS, TabBar.HOME);   // back from a tab = home, like YouTube
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void load() {
        names.clear();
        try {
            String s = Store.get(this, "subs");
            JSONArray a = s.isEmpty() ? new JSONArray() : new JSONArray(s);
            for (int i = 0; i < a.length(); i++) names.add(a.optString(i));
            String m = Store.get(this, "suburl");
            urls = m.isEmpty() ? new JSONObject() : new JSONObject(m);
        } catch (Throwable ignored) {}
        adapter.notifyDataSetChanged();
        if (names.isEmpty()) {
            status.setVisibility(View.VISIBLE);
            status.setText("কোনো চ্যানেল সাবস্ক্রাইব করা নেই। ভিডিও দেখার সময় \"সাবস্ক্রাইব\" চাপুন। এগুলো শুধু এই ফোনে থাকে।");
        } else {
            status.setVisibility(View.GONE);
        }
    }

    private void openChannel(String name) {
        String url = urls.optString(name, "");
        if (url.isEmpty()) {
            Toast.makeText(this, "এই চ্যানেলের লিংক সেভ নেই। ওর কোনো ভিডিও খুলে চ্যানেল বোতামে চাপুন।", Toast.LENGTH_LONG).show();
            return;
        }
        ChannelActivity.start(this, url, name);
    }

    private void confirmRemove(final String name) {
        new AlertDialog.Builder(this)
                .setTitle(name + " থেকে সাবস্ক্রিপশন বাতিল করবেন?")
                .setPositiveButton("বাতিল করুন", (d, w) -> {
                    Store.toggleSub(this, name, "");
                    load();
                    status.announceForAccessibility("সাবস্ক্রিপশন বাতিল হয়েছে");
                })
                .setNegativeButton("থাক", null)
                .show();
    }
}
