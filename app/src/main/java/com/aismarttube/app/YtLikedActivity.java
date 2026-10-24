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

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** YouTube account liked videos via Data API v3 + OAuth. */
public class YtLikedActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private VideoAdapter adapter;
    private TextView status;
    private final List<VideoItem> items = new ArrayList<>();
    private boolean loaded, busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        TextView title = findViewById(R.id.barTitle);
        title.setText("Google লাইক");
        title.setContentDescription("Google এ লাইক করা ভিডিও");
        status = findViewById(R.id.status);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(this);
        list.setAdapter(adapter);

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loaded && !busy) begin();
    }

    private void begin() {
        if (!YtAuth.isSignedIn(this)) {
            busy = true;
            setStatus("Google লগইন করুন…");
            YtAuth.signIn(this);
        } else {
            load();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == YtAuth.REQ_SIGN_IN) {
            busy = false;
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                task.getResult(ApiException.class);
                YtAuth.clearToken(this);
                say("লগইন হয়েছে");
                load();
            } catch (ApiException e) {
                busy = true;   // do not re-open the sign-in on its own
                setStatus("লগইন হয়নি (কোড " + e.getStatusCode() + ")। কোড ১০ মানে SHA-1 মেলেনি।");
                say("লগইন ব্যর্থ। Console এ SHA-1 ও package মিলান।");
            }
        } else if (requestCode == YtAuth.REQ_RECOVER && resultCode == RESULT_OK) {
            YtAuth.clearToken(this);
            load();
        }
    }

    private void load() {
        busy = true;   // no second load from onResume
        setStatus("লাইক লোড হচ্ছে…");
        io.execute(() -> {
            try {
                final List<VideoItem> list = YtApi.likedVideos(this, null);
                ui.post(() -> {
                    loaded = true;
                    items.clear();
                    items.addAll(list);
                    adapter.submit(items);
                    setStatus(list.isEmpty()
                            ? "কোনো লাইক নেই বা খালি"
                            : list.size() + "টি লাইক করা ভিডিও");
                });
            } catch (Exception e) {
                Intent recover = YtAuth.recoverIntent(e);
                ui.post(() -> {
                    if (recover != null) {
                        try {
                            startActivityForResult(recover, YtAuth.REQ_RECOVER);
                        } catch (Throwable t) {
                            setStatus("অনুমতি লাগবে: " + e.getMessage());
                        }
                    } else {
                        setStatus("ত্রুটি: " + e.getMessage());
                        say(String.valueOf(e.getMessage()));
                    }
                });
            }
        });
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        if (item == null || item.url.isEmpty()) return;
        try { NativeFeedLoader.prefetch(item.url); } catch (Throwable ignored) {}
        startActivity(PlayerActivity.intent(this, items, position));
    }

    private void setStatus(String s) {
        status.setVisibility(View.VISIBLE);
        status.setText(s);
    }

    private void say(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
