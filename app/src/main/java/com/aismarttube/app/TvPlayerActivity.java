package com.aismarttube.app;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plays one live TV channel. Buttons: previous / play-pause / next channel, favourite, full screen. */
public class TvPlayerActivity extends AppCompatActivity {

    static final String EXTRA_INDEX = "tv_index";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<TvChannels.Ch> queue;
    private int idx = 0;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory http;
    private PlayerView pv;
    private TextView title, state;
    private Button bPlay, bFav, bFull;
    private LinearLayout controls;
    private boolean fullscreen = false;
    private boolean retried = false;

    @Override
    @SuppressWarnings("UnsafeOptInUsageError")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        queue = TvChannels.queue;
        if (queue == null || queue.isEmpty()) { finish(); return; }
        idx = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), queue.size() - 1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF09161A);

        pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setBackgroundColor(0xFF000000);
        pv.setKeepScreenOn(true);
        pv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(pv, new LinearLayout.LayoutParams(-1, dp(230)));

        title = new TextView(this);
        title.setTextSize(20);
        title.setTextColor(0xFFEAF5F3);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(16), dp(12), dp(16), dp(4));
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ViewCompat.setAccessibilityHeading(title, true);
        state = new TextView(this);
        state.setTextSize(15);
        state.setTextColor(0xFFA4BCC0);
        state.setPadding(dp(16), 0, dp(16), dp(8));
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.addView(title);
        controls.addView(state);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(btn("আগের চ্যানেল", v -> step(-1)), w());
        bPlay = btn("থামান", v -> togglePlay());
        r1.addView(bPlay, w());
        r1.addView(btn("পরের চ্যানেল", v -> step(1)), w());
        controls.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        bFav = btn("প্রিয়তে রাখুন", v -> toggleFav());
        r2.addView(bFav, w());
        bFull = btn("পূর্ণ পর্দা", v -> setFullscreen(true));
        r2.addView(bFull, w());
        controls.addView(r2);
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        pv.setOnClickListener(v -> { if (fullscreen) controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); });

        PlayerActivity.yieldToOthers();
        RadioHub.stopForVideo();
        build();
        load();
    }

    private LinearLayout.LayoutParams w() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("UnsafeOptInUsageError")
    private void build() {
        http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        pv.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_BUFFERING) setState("লোড হচ্ছে…");
                else if (st == Player.STATE_READY) { retried = false; setState("চলছে"); }
                updatePlay();
            }

            @Override
            public void onIsPlayingChanged(boolean p) { updatePlay(); }

            @Override
            public void onPlayerError(PlaybackException e) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                if (!retried) {
                    retried = true;
                    setState("আবার চেষ্টা হচ্ছে…");
                    ui.postDelayed(() -> { try { player.prepare(); player.play(); } catch (Throwable ignored) { } }, 2000);
                    return;
                }
                setState("এই চ্যানেল এখন চলছে না। 'পরের চ্যানেল' চাপুন, অথবা একটু পরে আবার চেষ্টা করুন।");
            }
        });
    }

    private void load() {
        final TvChannels.Ch ch = queue.get(idx);
        retried = false;
        title.setText(ch.name);
        title.setContentDescription(ch.name);
        setState("লোড হচ্ছে…");
        http.setUserAgent(ch.ua.isEmpty() ? UA : ch.ua);
        Map<String, String> h = new HashMap<>();
        if (!ch.ref.isEmpty()) h.put("Referer", ch.ref);
        http.setDefaultRequestProperties(h);
        player.setMediaItem(MediaItem.fromUri(ch.url));
        player.prepare();
        player.play();
        updateFav();
        updatePlay();
    }

    private void step(int d) {
        idx = (idx + d + queue.size()) % queue.size();
        load();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_IDLE) { player.prepare(); player.play(); }
        else if (player.isPlaying() || player.getPlayWhenReady()) player.pause();
        else player.play();
        updatePlay();
    }

    private void updatePlay() {
        boolean on = player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_IDLE;
        bPlay.setText(on ? "থামান" : "চালান");
    }

    private void toggleFav() {
        TvChannels.toggleFav(this, queue.get(idx).url);
        updateFav();
        setState(TvChannels.isFav(this, queue.get(idx).url) ? "প্রিয়তে রাখা হয়েছে" : "প্রিয় থেকে সরানো হয়েছে");
    }

    private void updateFav() {
        bFav.setText(TvChannels.isFav(this, queue.get(idx).url) ? "প্রিয় থেকে সরান" : "প্রিয়তে রাখুন");
    }

    private void setState(String s) {
        state.setText(s);
    }

    @SuppressWarnings("deprecation")
    private void setFullscreen(boolean on) {
        fullscreen = on;
        ViewGroup.LayoutParams lp = pv.getLayoutParams();
        lp.height = on ? ViewGroup.LayoutParams.MATCH_PARENT : dp(230);
        pv.setLayoutParams(lp);
        controls.setVisibility(on ? View.GONE : View.VISIBLE);
        setRequestedOrientation(on ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().getDecorView().setSystemUiVisibility(on
                ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                : View.SYSTEM_UI_FLAG_VISIBLE);
        if (on) pv.announceForAccessibility("পূর্ণ পর্দা। ফিরতে পেছনে যান চাপুন, বোতাম দেখতে পর্দায় একবার চাপুন");
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) { setFullscreen(false); return; }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && !Store.settingBool(this, "bgPlay", true)) player.pause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        super.onDestroy();
    }
}
