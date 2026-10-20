package com.aismarttube.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native channel page: header, tabs (videos / shorts / live / playlists), sort, list, "more".
 * Same RecyclerView rows as Home, so TalkBack stays quick.
 */
public class ChannelActivity extends AppCompatActivity implements VideoAdapter.Listener {

    static final String EXTRA_URL = "ch_url";
    static final String EXTRA_NAME = "ch_name";

    static void start(Context c, String url, String name) {
        Intent i = new Intent(c, ChannelActivity.class);
        i.putExtra(EXTRA_URL, url == null ? "" : url);
        i.putExtra(EXTRA_NAME, name == null ? "" : name);
        c.startActivity(i);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private VideoAdapter adapter;
    private TextView status, chName, chMeta, chDesc, barTitle;
    private Button chSub, btnMore, btnBackList;
    private LinearLayout tabRow, sortRow;

    private String chUrl = "";
    private String chNameStr = "";
    private JSONObject info;
    private int tabIdx = 0;
    private String nextTok = "";
    private List<VideoItem> items = new ArrayList<>();
    private String sort = "new";           // new | pop
    private boolean playlistView = false;
    private int seq = 0;
    private final List<Button> tabBtns = new ArrayList<>();
    private final List<Button> sortBtns = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_channel);
        chUrl = getIntent().getStringExtra(EXTRA_URL);
        chNameStr = getIntent().getStringExtra(EXTRA_NAME);
        if (chUrl == null) chUrl = "";
        if (chNameStr == null) chNameStr = "";

        status = findViewById(R.id.status);
        chName = findViewById(R.id.chName);
        chMeta = findViewById(R.id.chMeta);
        chDesc = findViewById(R.id.chDesc);
        barTitle = findViewById(R.id.barTitle);
        chSub = findViewById(R.id.chSub);
        btnMore = findViewById(R.id.btnMore);
        btnBackList = findViewById(R.id.btnBackList);
        tabRow = findViewById(R.id.tabRow);
        sortRow = findViewById(R.id.sortRow);
        ViewCompat.setAccessibilityHeading(chName, true);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemViewCacheSize(8);
        adapter = new VideoAdapter(this);
        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnMore.setOnClickListener(v -> loadMore());
        btnBackList.setOnClickListener(v -> {
            playlistView = false;
            loadTab(tabIdx);
        });
        chDesc.setOnClickListener(v -> {
            boolean clamped = chDesc.getMaxLines() <= 2;
            chDesc.setMaxLines(clamped ? 1000 : 2);
        });

        chName.setText(chNameStr.isEmpty() ? "চ্যানেল" : chNameStr);
        barTitle.setText(chNameStr.isEmpty() ? "চ্যানেল" : chNameStr);

        if (chUrl.isEmpty()) {
            status.setText("চ্যানেলের লিংক পাওয়া যায়নি");
            return;
        }
        status.setText("চ্যানেল লোড হচ্ছে…");
        final int my = ++seq;
        io.execute(() -> {
            String raw;
            try {
                NewPipeHelper.ensureInit();
                raw = NewPipeHelper.getChannel(chUrl);
            } catch (Throwable t) {
                raw = "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace('"', ' ') + "\"}";
            }
            final String r = raw;
            ui.post(() -> onChannel(my, r));
        });
    }

    private void onChannel(int my, String raw) {
        if (my != seq || isFinishing()) return;
        try {
            JSONObject d = new JSONObject(raw);
            if (!d.optBoolean("ok", false)) {
                status.setText(d.optString("error", "চ্যানেল খোলা যায়নি"));
                return;
            }
            info = d;
            String nm = d.optString("name", "");
            if (!nm.isEmpty()) chNameStr = nm;
            String u = d.optString("url", "");
            if (!u.isEmpty()) chUrl = u;
            chName.setText(chNameStr);
            barTitle.setText(chNameStr);
            long sc = d.optLong("subscribers", -1);
            chMeta.setText(sc > 0 ? fmtCount(sc) + " জন সাবস্ক্রাইবার" : "");
            String desc = d.optString("description", "").trim();
            if (!desc.isEmpty()) {
                chDesc.setText(desc);
                chDesc.setVisibility(View.VISIBLE);
                chDesc.setContentDescription("চ্যানেলের বিবরণ, পুরোটা দেখতে চাপুন। " + desc);
            }
            chSub.setVisibility(View.VISIBLE);
            chSub.setOnClickListener(v -> {
                boolean now = Store.toggleSub(this, chNameStr, chUrl);
                paintSub();
                Toast.makeText(this, now ? "সাবস্ক্রাইব করা হয়েছে" : "সাবস্ক্রিপশন বাতিল", Toast.LENGTH_SHORT).show();
            });
            paintSub();
            buildTabs();
            buildSort();
            JSONArray tabs = d.optJSONArray("tabs");
            if (tabs != null && tabs.length() > 0) loadTab(0);
            else status.setText("এই চ্যানেলে দেখানোর মতো ট্যাব নেই");
        } catch (Throwable t) {
            status.setText("ত্রুটি: " + t.getMessage());
        }
    }

    private void paintSub() {
        boolean on = Store.isSubbed(this, chNameStr);
        chSub.setText(on ? "সদস্যতা নেওয়া হয়েছে" : "সাবস্ক্রাইব");
        chSub.setContentDescription((on ? "সদস্যতা নেওয়া হয়েছে, বাতিল করতে চাপুন: " : "সাবস্ক্রাইব করুন: ") + chNameStr);
    }

    private static String tabName(String n) {
        if ("videos".equals(n)) return "ভিডিও";
        if ("shorts".equals(n)) return "Shorts";
        if ("livestreams".equals(n)) return "লাইভ";
        if ("playlists".equals(n)) return "প্লেলিস্ট";
        if ("albums".equals(n)) return "অ্যালবাম";
        if ("podcasts".equals(n)) return "পডকাস্ট";
        return n == null || n.isEmpty() ? "ট্যাব" : n;
    }

    private Button chip(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinHeight(48);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void paintChip(Button b, boolean on, String label) {
        b.setBackgroundColor(on ? 0xFFEAF5F3 : 0xFF183039);
        b.setTextColor(on ? 0xFF09161A : 0xFFEAF5F3);
        b.setSelected(on);
        b.setContentDescription(label + (on ? ", নির্বাচিত" : ""));
    }

    private void buildTabs() {
        tabRow.removeAllViews();
        tabBtns.clear();
        JSONArray tabs = info.optJSONArray("tabs");
        if (tabs == null) return;
        for (int i = 0; i < tabs.length(); i++) {
            final int idx = i;
            Button b = chip(tabName(tabs.optJSONObject(i).optString("name", "")));
            b.setOnClickListener(v -> {
                tabIdx = idx;
                playlistView = false;
                loadTab(idx);
            });
            tabBtns.add(b);
            tabRow.addView(b);
        }
        paintTabs();
    }

    private void paintTabs() {
        JSONArray tabs = info == null ? null : info.optJSONArray("tabs");
        for (int i = 0; i < tabBtns.size(); i++) {
            String label = tabs == null ? "" : tabName(tabs.optJSONObject(i).optString("name", ""));
            paintChip(tabBtns.get(i), i == tabIdx, label);
        }
    }

    private void buildSort() {
        sortRow.removeAllViews();
        sortBtns.clear();
        String[][] s = {{"new", "লেটেস্ট"}, {"pop", "জনপ্রিয়"}};
        for (String[] x : s) {
            final String id = x[0];
            Button b = chip(x[1]);
            b.setOnClickListener(v -> {
                sort = id;
                paintSort();
                paintList(false);
            });
            sortBtns.add(b);
            sortRow.addView(b);
        }
        paintSort();
    }

    private void paintSort() {
        String[][] s = {{"new", "লেটেস্ট"}, {"pop", "জনপ্রিয়"}};
        for (int i = 0; i < sortBtns.size(); i++) paintChip(sortBtns.get(i), s[i][0].equals(sort), s[i][1]);
        boolean isPlaylistsTab = false;
        try {
            isPlaylistsTab = "playlists".equals(info.optJSONArray("tabs").optJSONObject(tabIdx).optString("name"));
        } catch (Throwable ignored) {}
        ((View) sortRow.getParent()).setVisibility(isPlaylistsTab || playlistView ? View.GONE : View.VISIBLE);
    }

    private void loadTab(int idx) {
        paintTabs();
        sort = "new";
        paintSort();
        btnBackList.setVisibility(View.GONE);
        btnMore.setVisibility(View.GONE);
        items = new ArrayList<>();
        nextTok = "";
        adapter.submit(new ArrayList<>());
        status.setVisibility(View.VISIBLE);
        status.setText("লোড হচ্ছে…");
        final int my = ++seq;
        final String tabId = info.optJSONArray("tabs").optJSONObject(idx).optString("id", "");
        io.execute(() -> {
            String raw = NewPipeHelper.getChannelTab(tabId);
            ui.post(() -> {
                if (my != seq || isFinishing()) return;
                try {
                    JSONObject d = new JSONObject(raw);
                    if (!d.optBoolean("ok", false)) {
                        status.setText(d.optString("error", "আনা যায়নি"));
                        return;
                    }
                    items = parse(d.optJSONArray("items"));
                    nextTok = d.optString("next", "");
                    paintList(false);
                } catch (Throwable t) {
                    status.setText("ত্রুটি: " + t.getMessage());
                }
            });
        });
    }

    private void loadMore() {
        if (nextTok.isEmpty() || info == null) return;
        btnMore.setEnabled(false);
        btnMore.setText("লোড হচ্ছে…");
        final int my = seq;
        final String tabId = info.optJSONArray("tabs").optJSONObject(tabIdx).optString("id", "");
        final String tok = nextTok;
        io.execute(() -> {
            String raw = NewPipeHelper.getChannelMore(tabId, tok);
            ui.post(() -> {
                btnMore.setEnabled(true);
                btnMore.setText("আরও দেখুন");
                if (my != seq || isFinishing()) return;
                try {
                    JSONObject d = new JSONObject(raw);
                    if (!d.optBoolean("ok", false)) {
                        Toast.makeText(this, d.optString("error", "আনা যায়নি"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<VideoItem> more = parse(d.optJSONArray("items"));
                    items.addAll(more);
                    nextTok = d.optString("next", "");
                    paintList(true);
                    status.setVisibility(View.VISIBLE);
                    status.setText(VideoItem.formatCountBn(more.size()) + "টি নতুন যোগ হয়েছে");
                    ui.postDelayed(() -> status.setVisibility(View.GONE), 2000);
                } catch (Throwable t) {
                    Toast.makeText(this, "ত্রুটি: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void openPlaylist(VideoItem pl) {
        final int my = ++seq;
        items = new ArrayList<>();
        nextTok = "";
        adapter.submit(new ArrayList<>());
        btnMore.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText("প্লেলিস্ট লোড হচ্ছে…");
        io.execute(() -> {
            String raw = NewPipeHelper.getPlaylist(pl.url);
            ui.post(() -> {
                if (my != seq || isFinishing()) return;
                try {
                    JSONObject d = new JSONObject(raw);
                    if (!d.optBoolean("ok", false)) {
                        status.setText(d.optString("error", "আনা যায়নি"));
                        btnBackList.setVisibility(View.VISIBLE);
                        return;
                    }
                    playlistView = true;
                    sort = "new";
                    paintSort();
                    items = parse(d.optJSONArray("items"));
                    nextTok = "";
                    btnBackList.setVisibility(View.VISIBLE);
                    paintList(false);
                    status.setVisibility(View.VISIBLE);
                    status.setText(pl.title + ", " + VideoItem.formatCountBn(items.size()) + "টি ভিডিও");
                } catch (Throwable t) {
                    status.setText("ত্রুটি: " + t.getMessage());
                }
            });
        });
    }

    private List<VideoItem> parse(JSONArray a) {
        List<VideoItem> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            VideoItem v = VideoItem.fromJson(a.optJSONObject(i));
            if (v == null || v.url.isEmpty()) continue;
            if ("video".equals(v.kind) && v.uploader.isEmpty()) {
                VideoItem w = new VideoItem(v.title, v.url, chNameStr, chUrl, v.duration, v.views, v.thumb);
                w.kind = v.kind;
                v = w;
            }
            out.add(v);
        }
        return out;
    }

    private void paintList(boolean keepStatus) {
        List<VideoItem> l = new ArrayList<>(items);
        if ("pop".equals(sort) && !playlistView) {
            Collections.sort(l, (x, y) -> Long.compare(y.views, x.views));
        }
        adapter.submit(l);
        if (l.isEmpty()) {
            status.setVisibility(View.VISIBLE);
            status.setText("এখানে কিছু নেই");
        } else if (!keepStatus) {
            status.setVisibility(View.GONE);
        }
        btnMore.setVisibility(nextTok.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        if (item == null) return;
        if ("playlist".equals(item.kind)) {
            openPlaylist(item);
            return;
        }
        List<VideoItem> vids = new ArrayList<>();
        int idx = 0;
        for (VideoItem v : adapter.items()) {
            if ("playlist".equals(v.kind)) continue;
            if (v.url.equals(item.url)) idx = vids.size();
            vids.add(v);
        }
        NativeFeedLoader.prefetch(item.url);
        startActivity(PlayerActivity.intent(this, vids, idx));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private static String fmtCount(long n) {
        if (n >= 10_000_000) return bnDec(n / 10_000_000.0) + " কোটি";
        if (n >= 100_000) return bnDec(n / 100_000.0) + " লাখ";
        if (n >= 1000) return bnDec(n / 1000.0) + " হাজার";
        return VideoItem.formatCountBn(n);
    }

    private static String bnDec(double v) {
        String s = String.format(java.util.Locale.US, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return toBn(s);
    }

    private static String toBn(String t) {
        String bn = "০১২৩৪৫৬৭৮৯";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            b.append(c >= '0' && c <= '9' ? bn.charAt(c - '0') : c);
        }
        return b.toString();
    }
}
