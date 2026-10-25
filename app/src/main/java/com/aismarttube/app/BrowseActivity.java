package com.aismarttube.app;

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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Native Home feed (RecyclerView) — fast TalkBack like Radio list.
 * Opens MainActivity (WebView player) when a video is tapped.
 */
public class BrowseActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private static final String[][] CHIPS = {
            {"সব", ""},
            {"সঙ্গীত", "বাংলা গান"},
            {"সংবাদ", "বাংলা খবর"},
            {"লাইভ", "বাংলা লাইভ"},
            {"শেখা", "বাংলা টিউটোরিয়াল"}
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private VideoAdapter adapter;
    private TextView status;
    private int chipIdx = 0;
    private int loadGen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Store.settingBool(this, "setupDone", false)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_browse);

        status = findViewById(R.id.status);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        // Keep off-screen views low for TalkBack performance
        list.setItemViewCacheSize(8);
        adapter = new VideoAdapter(this);
        list.setAdapter(adapter);

        findViewById(R.id.btnSearch).setOnClickListener(v ->
                startActivity(new Intent(this, SearchNativeActivity.class)));
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                startActivity(HistoryActivity.open(this, "history")));
        findViewById(R.id.btnFullApp).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class)));

        TabBar.setup(this, TabBar.HOME);
        buildChips();
        loadCurrent(false);
        warmWebView(this);
    }

    // ---- the settings / radio screen is a WebView: starting the browser engine takes a second or two,
    //      so it is started quietly here while the home list is being read ----
    private static android.webkit.WebView warm;
    private static final Handler warmH = new Handler(Looper.getMainLooper());
    private static final Runnable warmKill = BrowseActivity::dropWarm;

    private static void warmWebView(android.content.Context c) {
        if (warm != null) return;
        warmH.postDelayed(() -> {
            try {
                if (warm != null) return;
                warm = new android.webkit.WebView(c.getApplicationContext());
                warm.loadUrl("about:blank");
                warmH.postDelayed(warmKill, 25000);
            } catch (Throwable ignored) {}
        }, 1500);
    }

    static void dropWarm() {
        warmH.removeCallbacks(warmKill);
        try { if (warm != null) warm.destroy(); } catch (Throwable ignored) {}
        warm = null;
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // home tab: leave the app but keep radio / music going
    }

    private final Button[] chipViews = new Button[CHIPS.length];

    /** built once: rebuilding on every tap made TalkBack lose its focus */
    private void buildChips() {
        LinearLayout row = findViewById(R.id.chipRow);
        row.removeAllViews();
        for (int i = 0; i < CHIPS.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(CHIPS[i][0]);
            b.setAllCaps(false);
            b.setMinHeight(48);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                boolean same = idx == chipIdx;
                chipIdx = idx;
                paintChips();
                loadCurrent(same);   // tapping the selected chip again = refresh
            });
            chipViews[i] = b;
            row.addView(b);
        }
        paintChips();
    }

    private void paintChips() {
        for (int i = 0; i < chipViews.length; i++) {
            Button b = chipViews[i];
            boolean on = i == chipIdx;
            styleChip(b, on);
            b.setSelected(on);
            b.setContentDescription(CHIPS[i][0] + " ক্যাটাগরি" + (on ? ", নির্বাচিত। আবার চাপলে রিফ্রেশ" : ""));
        }
    }

    private void styleChip(Button b, boolean on) {
        if (on) {
            b.setBackgroundColor(0xFFEAF5F3);
            b.setTextColor(0xFF09161A);
        } else {
            b.setBackgroundColor(0xFF183039);
            b.setTextColor(0xFFEAF5F3);
        }
    }

    private void loadCurrent(boolean force) {
        final int gen = ++loadGen;

        // home: show the saved list at once, refresh quietly only when it is old
        if (chipIdx == 0 && !force) {
            List<VideoItem> c = NativeFeedLoader.cachedHome();
            if (c != null && !c.isEmpty()) {
                status.setVisibility(View.GONE);
                adapter.submit(c);
                if (NativeFeedLoader.homeFresh()) return;
            }
        }
        if (adapter.getItemCount() == 0 || force) {
            showStatus("লোড হচ্ছে…");
            adapter.submit(new ArrayList<>());
        }

        NativeFeedLoader.Callback cb = (items, error) -> main.post(() -> {
            if (gen != loadGen) return;
            if (items == null || items.isEmpty()) {
                if (adapter.getItemCount() > 0) return;   // keep what is on screen
                showStatus(error != null ? error + "। আবার চাপুন বা সার্চ করুন।" : "কিছু পাওয়া যায়নি");
                return;
            }
            adapter.submit(new ArrayList<>(items));
            status.setVisibility(View.GONE);
        });

        if (chipIdx == 0) {
            NativeFeedLoader.loadHome(this, cb);
        } else {
            NativeFeedLoader.search(CHIPS[chipIdx][1], cb);
        }
    }

    private void showStatus(String msg) {
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        if (item == null || item.url.isEmpty()) {
            Toast.makeText(this, "লিংক নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        NativeFeedLoader.prefetch(item.url);   // runs while the player screen opens
        List<VideoItem> all = adapter.items();
        int idx = 0;
        for (int k = 0; k < all.size(); k++) if (all.get(k).url.equals(item.url)) { idx = k; break; }
        startActivity(PlayerActivity.intent(this, all, idx));
    }
}
