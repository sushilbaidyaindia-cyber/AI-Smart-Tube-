package com.aismarttube.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** "পছন্দের ভিডিও": videos liked in this app + (when signed in) the YouTube account's likes, merged. */
public class YtLikedActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private VideoAdapter adapter;
    private TextView status;
    private final List<VideoItem> items = new ArrayList<>();
    private boolean started;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        TextView title = findViewById(R.id.barTitle);
        title.setText("পছন্দের ভিডিও");
        status = findViewById(R.id.status);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(this);
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) return;
        started = true;
        showLocal();
        if (YtAuth.isSignedIn(this)) loadGoogle();
    }

    private void showLocal() {
        items.clear();
        try {
            String s = Store.get(this, "liked");
            JSONArray a = s.isEmpty() ? new JSONArray() : new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                VideoItem v = VideoItem.fromJson(a.optJSONObject(i));
                if (v != null && !v.url.isEmpty()) items.add(v);
            }
        } catch (Throwable ignored) { }
        adapter.submit(new ArrayList<>(items));
        paintStatus();
    }

    private void paintStatus() {
        status.setVisibility(View.VISIBLE);
        status.setText(items.isEmpty() ? "কোনো পছন্দের ভিডিও নেই" : items.size() + "টি পছন্দের ভিডিও");
    }

    private void loadGoogle() {
        status.setVisibility(View.VISIBLE);
        status.setText("ইউটিউবের লাইক আনা হচ্ছে…");
        io.execute(() -> {
            try {
                final List<VideoItem> g = YtApi.likedVideos(this, null);
                ui.post(() -> {
                    Set<String> have = new HashSet<>();
                    for (VideoItem v : items) have.add(v.url);
                    for (VideoItem v : g) if (have.add(v.url)) items.add(v);
                    adapter.submit(new ArrayList<>(items));
                    paintStatus();
                });
            } catch (Exception e) {
                final Intent recover = YtAuth.recoverIntent(e);
                ui.post(() -> {
                    if (recover != null) {
                        try { startActivityForResult(recover, YtAuth.REQ_RECOVER); return; } catch (Throwable ignored) { }
                    }
                    paintStatus();
                    Toast.makeText(this, "ইউটিউবের লাইক আনা যায়নি: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == YtAuth.REQ_RECOVER && resultCode == RESULT_OK) {
            YtAuth.clearToken(this);
            loadGoogle();
        }
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        if (item == null || item.url.isEmpty()) return;
        try { NativeFeedLoader.prefetch(item.url); } catch (Throwable ignored) { }
        startActivity(PlayerActivity.intent(this, items, position));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
