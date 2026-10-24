package com.aismarttube.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The YouTube account's playlists; tap one to see and play its videos. */
public class YtPlaylistsActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<String[]> lists = new ArrayList<>();
    private final List<VideoItem> videos = new ArrayList<>();
    private RecyclerView list;
    private TextView status, barTitle;
    private VideoAdapter vAdapter;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> pAdapter;
    private boolean inside, started;
    private String openId = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        barTitle = findViewById(R.id.barTitle);
        barTitle.setText("প্লেলিস্ট");
        status = findViewById(R.id.status);
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        vAdapter = new VideoAdapter(this);
        pAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return lists.size(); }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
                TextView v = new TextView(p.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                v.setMinHeight(Math.round(64 * getResources().getDisplayMetrics().density));
                v.setGravity(Gravity.CENTER_VERTICAL);
                int pad = Math.round(16 * getResources().getDisplayMetrics().density);
                v.setPadding(pad + 4, pad, pad + 4, pad);
                v.setTextColor(0xFFEAF5F3);
                v.setTextSize(18);
                v.setClickable(true);
                v.setFocusable(true);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
                return new RecyclerView.ViewHolder(v) { };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                final String[] pl = lists.get(pos);
                TextView v = (TextView) h.itemView;
                v.setText(pl[1] + "\n" + pl[2] + "টি ভিডিও");
                v.setContentDescription(pl[1] + ", " + pl[2] + "টি ভিডিও");
                v.setOnClickListener(x -> openPlaylist(pl[0], pl[1]));
            }
        };
        list.setAdapter(pAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) return;
        started = true;
        if (!YtAuth.isSignedIn(this)) {
            say("প্লেলিস্ট দেখতে \"আপনি\" ট্যাব থেকে Google লগইন করুন");
            return;
        }
        loadLists();
    }

    private void say(String s) {
        status.setVisibility(View.VISIBLE);
        status.setText(s);
    }

    private void loadLists() {
        say("প্লেলিস্ট আনা হচ্ছে…");
        io.execute(() -> {
            try {
                final List<String[]> r = YtApi.playlists(this);
                ui.post(() -> {
                    lists.clear();
                    lists.addAll(r);
                    pAdapter.notifyDataSetChanged();
                    say(r.isEmpty() ? "কোনো প্লেলিস্ট নেই" : r.size() + "টি প্লেলিস্ট");
                });
            } catch (Exception e) {
                failed(e, false);
            }
        });
    }

    private void openPlaylist(final String id, final String name) {
        inside = true;
        openId = id;
        barTitle.setText(name);
        list.setAdapter(vAdapter);
        vAdapter.submit(new ArrayList<>());
        say("ভিডিও আনা হচ্ছে…");
        io.execute(() -> {
            try {
                final List<VideoItem> r = YtApi.playlistItems(this, id);
                ui.post(() -> {
                    videos.clear();
                    videos.addAll(r);
                    vAdapter.submit(new ArrayList<>(videos));
                    say(r.isEmpty() ? "এই প্লেলিস্টে কোনো ভিডিও নেই" : r.size() + "টি ভিডিও");
                });
            } catch (Exception e) {
                failed(e, true);
            }
        });
    }

    private void failed(Exception e, boolean forItems) {
        final Intent recover = YtAuth.recoverIntent(e);
        ui.post(() -> {
            if (recover != null) {
                try { startActivityForResult(recover, YtAuth.REQ_RECOVER); return; } catch (Throwable ignored) { }
            }
            say("ত্রুটি: " + e.getMessage());
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == YtAuth.REQ_RECOVER && resultCode == RESULT_OK) {
            YtAuth.clearToken(this);
            if (inside) openPlaylist(openId, String.valueOf(barTitle.getText())); else loadLists();
        }
    }

    @Override
    public void onBackPressed() {
        if (inside) {
            inside = false;
            barTitle.setText("প্লেলিস্ট");
            list.setAdapter(pAdapter);
            say(lists.size() + "টি প্লেলিস্ট");
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        if (item == null || item.url.isEmpty()) return;
        try { NativeFeedLoader.prefetch(item.url); } catch (Throwable ignored) { }
        startActivity(PlayerActivity.intent(this, videos, position));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
