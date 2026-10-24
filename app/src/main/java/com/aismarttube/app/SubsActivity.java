package com.aismarttube.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Subscriptions tab: "নতুন ভিডিও" (latest videos of your channels, YouTube-like) and "চ্যানেল" (the channel list).
 * The feed is kept on the phone, so it shows at once and refreshes quietly when it is older than 30 minutes.
 */
public class SubsActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private static final long FEED_FRESH_MS = 30 * 60 * 1000L;
    private static final int MAX_CHANNELS = 14;
    private static final int PER_CHANNEL = 4;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final List<String> names = new ArrayList<>();
    private JSONObject urls = new JSONObject();
    private VideoAdapter feedAdapter;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> chanAdapter;
    private RecyclerView feedList, chanList;
    private TextView status;
    private Button btnFeed, btnChans;
    private boolean feedMode = true;
    private int feedGen = 0;
    private boolean loadingFeed = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_subs);
        status = findViewById(R.id.status);
        feedList = findViewById(R.id.feedList);
        chanList = findViewById(R.id.chanList);
        btnFeed = findViewById(R.id.btnFeed);
        btnChans = findViewById(R.id.btnChans);

        feedList.setLayoutManager(new LinearLayoutManager(this));
        feedList.setItemViewCacheSize(8);
        feedAdapter = new VideoAdapter(this);
        feedList.setAdapter(feedAdapter);

        chanList.setLayoutManager(new LinearLayoutManager(this));
        chanAdapter = new ChanAd();
        chanList.setAdapter(chanAdapter);

        btnFeed.setOnClickListener(v -> setMode(true));
        btnChans.setOnClickListener(v -> setMode(false));
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            if (feedMode) loadFeed(true); else { loadNames(); }
        });
        TabBar.setup(this, TabBar.SUBS);
        paintMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadNames();
        if (feedMode) loadFeed(false);
        YtSync.auto(this, () -> { loadNames(); if (feedMode) loadFeed(true); });
    }

    @Override
    protected void onDestroy() {
        feedGen++;
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        TabBar.go(this, TabBar.SUBS, TabBar.HOME);   // back from a tab = home, like YouTube
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------------ modes

    private void setMode(boolean feed) {
        feedMode = feed;
        paintMode();
        if (feed) loadFeed(false); else showChannelsStatus();
    }

    private void paintMode() {
        feedList.setVisibility(feedMode ? View.VISIBLE : View.GONE);
        chanList.setVisibility(feedMode ? View.GONE : View.VISIBLE);
        paint(btnFeed, feedMode, "নতুন ভিডিও");
        paint(btnChans, !feedMode, "চ্যানেল");
    }

    private void paint(Button b, boolean on, String label) {
        b.setBackgroundColor(on ? 0xFFEAF5F3 : 0xFF183039);
        b.setTextColor(on ? 0xFF09161A : 0xFFEAF5F3);
        b.setContentDescription(label + (on ? ", নির্বাচিত" : ""));
        b.setSelected(on);
    }

    // --------------------------------------------------------------- channels

    private void loadNames() {
        names.clear();
        try {
            String s = Store.get(this, "subs");
            JSONArray a = s.isEmpty() ? new JSONArray() : new JSONArray(s);
            for (int i = 0; i < a.length(); i++) names.add(a.optString(i));
            String m = Store.get(this, "suburl");
            urls = m.isEmpty() ? new JSONObject() : new JSONObject(m);
        } catch (Throwable ignored) {}
        chanAdapter.notifyDataSetChanged();
        if (!feedMode) showChannelsStatus();
    }

    private void showChannelsStatus() {
        if (names.isEmpty()) say("কোনো চ্যানেল সাবস্ক্রাইব করা নেই। ভিডিও দেখার সময় \"সাবস্ক্রাইব\" চাপুন। এগুলো শুধু এই ফোনে থাকে।");
        else say("");
    }

    private void say(String s) {
        status.setText(s);
        status.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
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
                    Store.set(this, "subfeed", "");
                    loadNames();
                    status.announceForAccessibility("সাবস্ক্রিপশন বাতিল হয়েছে");
                })
                .setNegativeButton("থাক", null)
                .show();
    }

    private class ChanAd extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
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
    }

    // ------------------------------------------------------------------- feed

    private void showCached() {
        try {
            String s = Store.get(this, "subfeed");
            if (s.isEmpty()) return;
            JSONObject o = new JSONObject(s);
            JSONArray a = o.optJSONArray("items");
            List<VideoItem> list = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                VideoItem v = VideoItem.fromJson(a.optJSONObject(i));
                if (v != null && !v.url.isEmpty()) list.add(v);
            }
            if (!list.isEmpty()) {
                feedAdapter.submit(list);
                say("");
            }
        } catch (Throwable ignored) {}
    }

    private long cacheAge() {
        try {
            String s = Store.get(this, "subfeed");
            if (s.isEmpty()) return Long.MAX_VALUE;
            return System.currentTimeMillis() - new JSONObject(s).optLong("t", 0);
        } catch (Throwable t) { return Long.MAX_VALUE; }
    }

    private void loadFeed(final boolean force) {
        if (names.isEmpty()) {
            feedAdapter.submit(new ArrayList<>());
            say("কোনো চ্যানেল সাবস্ক্রাইব করা নেই। ভিডিও দেখার সময় \"সাবস্ক্রাইব\" চাপুন।");
            return;
        }
        if (feedAdapter.getItemCount() == 0) showCached();
        if (!force && feedAdapter.getItemCount() > 0 && cacheAge() < FEED_FRESH_MS) return;
        if (loadingFeed) return;
        loadingFeed = true;
        final int gen = ++feedGen;
        if (feedAdapter.getItemCount() == 0) say("সাবস্ক্রাইব করা চ্যানেলের নতুন ভিডিও আনা হচ্ছে…");
        else if (force) say("নতুন করে আনা হচ্ছে…");

        final List<String> chNames = new ArrayList<>();
        final List<String> chUrls = new ArrayList<>();
        for (String n : names) {
            String u = urls.optString(n, "");
            if (u.isEmpty()) continue;
            chNames.add(n);
            chUrls.add(u);
            if (chNames.size() >= MAX_CHANNELS) break;
        }
        if (chNames.isEmpty()) {
            loadingFeed = false;
            say("চ্যানেলের লিংক সেভ নেই। কোনো ভিডিও খুলে চ্যানেল বোতামে একবার চাপুন।");
            return;
        }
        final List<List<VideoItem>> per = new ArrayList<>();
        for (int i = 0; i < chNames.size(); i++) per.add(new ArrayList<VideoItem>());
        final CountDownLatch latch = new CountDownLatch(chNames.size());
        try { NewPipeHelper.ensureInit(); } catch (Throwable ignored) {}
        for (int i = 0; i < chNames.size(); i++) {
            final int k = i;
            pool.execute(() -> {
                try {
                    per.get(k).addAll(latestOf(chNames.get(k), chUrls.get(k)));
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        new Thread(() -> {
            try { latch.await(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            // interleave: first video of every channel, then the second ...
            final List<VideoItem> out = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int round = 0; round < PER_CHANNEL; round++) {
                for (int k = 0; k < per.size(); k++) {
                    List<VideoItem> l;
                    synchronized (per.get(k)) { l = new ArrayList<>(per.get(k)); }
                    if (round < l.size() && seen.add(l.get(round).url)) out.add(l.get(round));
                }
            }
            ui.post(() -> {
                loadingFeed = false;
                if (gen != feedGen || isFinishing()) return;
                if (out.isEmpty()) {
                    if (feedAdapter.getItemCount() == 0) say("নতুন ভিডিও আনা যায়নি। ইন্টারনেট দেখে রিফ্রেশ চাপুন।");
                    else say("");
                    return;
                }
                feedAdapter.submit(out);
                say("");
                saveCache(out);
                status.announceForAccessibility("নতুন ভিডিও এসেছে");
            });
        }).start();
    }

    private List<VideoItem> latestOf(String chName, String chUrl) {
        List<VideoItem> list = new ArrayList<>();
        try {
            JSONObject info = new JSONObject(NewPipeHelper.getChannel(chUrl));
            if (!info.optBoolean("ok", false)) return list;
            JSONArray tabs = info.optJSONArray("tabs");
            if (tabs == null || tabs.length() == 0) return list;
            String id = "";
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject t = tabs.optJSONObject(i);
                if (t != null && "videos".equals(t.optString("name"))) { id = t.optString("id", ""); break; }
            }
            if (id.isEmpty()) id = tabs.optJSONObject(0).optString("id", "");
            if (id.isEmpty()) return list;
            JSONObject d = new JSONObject(NewPipeHelper.getChannelTab(id));
            JSONArray a = d.optJSONArray("items");
            for (int i = 0; a != null && i < a.length() && list.size() < PER_CHANNEL; i++) {
                VideoItem v = VideoItem.fromJson(a.optJSONObject(i));
                if (v == null || v.url.isEmpty() || "playlist".equals(v.kind)) continue;
                if (v.uploader.isEmpty() || v.uploaderUrl.isEmpty()) {
                    v = new VideoItem(v.title, v.url, v.uploader.isEmpty() ? chName : v.uploader,
                            v.uploaderUrl.isEmpty() ? chUrl : v.uploaderUrl, v.duration, v.views, v.thumb);
                }
                list.add(v);
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private void saveCache(List<VideoItem> items) {
        try {
            JSONArray a = new JSONArray();
            for (VideoItem v : items) a.put(v.toJson());
            Store.set(this, "subfeed", new JSONObject().put("t", System.currentTimeMillis()).put("items", a).toString());
        } catch (Throwable ignored) {}
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        if (item == null || item.url.isEmpty()) return;
        NativeFeedLoader.prefetch(item.url);
        List<VideoItem> all = feedAdapter.items();
        int idx = 0;
        for (int k = 0; k < all.size(); k++) if (all.get(k).url.equals(item.url)) { idx = k; break; }
        startActivity(PlayerActivity.intent(this, all, idx));
    }
}
