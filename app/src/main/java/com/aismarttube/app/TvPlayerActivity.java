package com.aismarttube.app;

import android.content.pm.ActivityInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
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
import androidx.media3.common.MimeTypes;
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
    static final String EXTRA_CU_SRNO = "cu_srno", EXTRA_CU_START = "cu_start", EXTRA_CU_END = "cu_end", EXTRA_CU_TITLE = "cu_title";
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
    private List<TvChannels.Stream> streams = new java.util.ArrayList<>();
    private int si = 0;          // which server (stream) of this channel is being tried
    private int failed = 0;      // servers that failed in this round
    private String cuSrno = null, cuTitle = "";   // set when an earlier programme is being watched
    private long cuStart = 0, cuEnd = 0;
    private LinearLayout skipRow;
    private MediaSession session;
    private int gen = 0;         // marks the newest start, so a late answer from Jio is ignored
    private boolean jioRetried = false;   // a failing Jio channel gets one fresh link before the next server
    private String lastJioError = "";
    private final Runnable stuck = () -> nextServer("সময় লাগছে");

    @Override
    @SuppressWarnings("UnsafeOptInUsageError")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        queue = TvChannels.queue;
        if (queue == null || queue.isEmpty()) { finish(); return; }
        idx = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), queue.size() - 1));
        cuSrno = getIntent().getStringExtra(EXTRA_CU_SRNO);
        cuStart = getIntent().getLongExtra(EXTRA_CU_START, 0);
        cuEnd = getIntent().getLongExtra(EXTRA_CU_END, 0);
        cuTitle = getIntent().getStringExtra(EXTRA_CU_TITLE) == null ? "" : getIntent().getStringExtra(EXTRA_CU_TITLE);
        if (cuSrno != null && cuSrno.isEmpty()) cuSrno = null;

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
        r2.addView(btn("অন্য সার্ভার", v -> manualServer()), w());
        bFull = btn("পূর্ণ পর্দা", v -> setFullscreen(true));
        r2.addView(bFull, w());
        controls.addView(r2);

        skipRow = new LinearLayout(this);
        skipRow.setOrientation(LinearLayout.HORIZONTAL);
        skipRow.addView(btn("৩০ সেকেন্ড পিছনে", v -> seekBy(-30000)), w());
        skipRow.addView(btn("৩০ সেকেন্ড সামনে", v -> seekBy(30000)), w());
        skipRow.addView(btn("৫ মিনিট সামনে", v -> seekBy(300000)), w());
        skipRow.setVisibility(cuSrno != null ? View.VISIBLE : View.GONE);
        controls.addView(skipRow);
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
        if (Store.settingBool(this, "tvLowData", false)) {
            // data saver: small picture, low bit rate (the player still picks the best one under this limit)
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setMaxVideoSize(640, 360).setMaxVideoBitrate(800000).build());
        }
        setupSession();
        pv.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_BUFFERING) setState(serverNote("লোড হচ্ছে…"));
                else if (st == Player.STATE_READY) {
                    ui.removeCallbacks(stuck);
                    failed = 0;
                    jioRetried = false;
                    setState(serverNote("চলছে"));
                    try { TvChannels.markGood(TvPlayerActivity.this, queue.get(idx), streams.get(si).url); } catch (Throwable ignored) { }
                }
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
                nextServer("চলেনি");
            }
        });
    }

    private String serverNote(String base) {
        return streams.size() > 1 ? base + " (সার্ভার " + (si + 1) + "/" + streams.size() + ")" : base;
    }

    /** this server did not work: try the next one; when all failed once, say so */
    private void nextServer(String why) {
        ui.removeCallbacks(stuck);
        // a Jio link expires after a while: ask for a fresh one once before giving up on it
        if (!jioRetried && si < streams.size() && !JioClient.idOf(streams.get(si).url).isEmpty() && !why.startsWith("Jio:")) {
            jioRetried = true;
            setState("Jio-র লিংক নতুন করে আনা হচ্ছে…");
            startStream();
            return;
        }
        failed++;
        if (failed >= Math.max(2, streams.size())) {
            try { player.stop(); } catch (Throwable ignored) { }
            setState("এই চ্যানেল এখন চলছে না (" + streams.size() + "টি সার্ভার চেষ্টা হয়েছে)। 'পরের চ্যানেল' চাপুন, অথবা একটু পরে আবার চেষ্টা করুন।");
            return;
        }
        si = (si + 1) % streams.size();
        setState("সার্ভার " + why + ", অন্য সার্ভার চেষ্টা হচ্ছে (" + (si + 1) + "/" + streams.size() + ")…");
        startStream();
    }

    private void manualServer() {
        if (streams.size() < 2) { setState("এই চ্যানেলের আর কোনো সার্ভার নেই"); return; }
        si = (si + 1) % streams.size();
        failed = 0;
        setState("সার্ভার " + (si + 1) + "/" + streams.size() + " চেষ্টা হচ্ছে…");
        startStream();
    }

    private void load() {
        final TvChannels.Ch ch = queue.get(idx);
        ui.removeCallbacks(stuck);
        String tt = cuSrno != null && !cuTitle.isEmpty() ? ch.name + " — " + cuTitle : ch.name;
        title.setText(tt);
        title.setContentDescription(tt);
        streams = TvChannels.orderedStreams(this, ch);
        if (cuSrno != null) {
            // an earlier programme only comes from Jio
            List<TvChannels.Stream> only = new java.util.ArrayList<>();
            for (TvChannels.Stream x : streams) if (!JioClient.idOf(x.url).isEmpty()) { only.add(x); break; }
            if (!only.isEmpty()) streams = only; else cuSrno = null;
        }
        if (skipRow != null) skipRow.setVisibility(cuSrno != null ? View.VISIBLE : View.GONE);
        si = 0;
        failed = 0;
        jioRetried = false;
        setState(serverNote("লোড হচ্ছে…"));
        updateFav();
        startStream();
    }

    private void startStream() {
        final TvChannels.Stream s = streams.get(si);
        final String jioId = JioClient.idOf(s.url);
        ui.removeCallbacks(stuck);
        ui.postDelayed(stuck, 15000);   // no picture after 15 s: next server
        if (!jioId.isEmpty()) {
            // Jio channel: ask Jio for a fresh playing link first (takes a moment, so not on the main thread)
            final int my = ++gen;
            try { player.stop(); } catch (Throwable ignored) { }
            setState(serverNote("Jio থেকে লিংক আনা হচ্ছে…"));
            new Thread(() -> {
                JioClient.Play p = null;
                String err = null;
                try {
                    p = cuSrno != null ? JioClient.catchup(getApplicationContext(), jioId, cuSrno, cuStart, cuEnd)
                            : JioClient.resolve(getApplicationContext(), jioId);
                }
                catch (Throwable t) { err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
                final JioClient.Play fp = p;
                final String fe = err;
                ui.post(() -> {
                    if (my != gen || player == null) return;
                    if (fp == null) { jioFailed(fe); return; }
                    http.setUserAgent(JioClient.UA_PLAYER);
                    http.setDefaultRequestProperties(fp.headers);
                    player.setMediaItem(new MediaItem.Builder().setUri(fp.url).setMimeType(MimeTypes.APPLICATION_M3U8).build());
                    player.prepare();
                    player.play();
                    updatePlay();
                });
            }, "jio-resolve").start();
            return;
        }
        gen++;
        http.setUserAgent(s.ua.isEmpty() ? UA : s.ua);
        Map<String, String> h = new HashMap<>();
        if (!s.ref.isEmpty()) h.put("Referer", s.ref);
        http.setDefaultRequestProperties(h);
        player.setMediaItem(MediaItem.fromUri(s.url));
        player.prepare();
        player.play();
        updatePlay();
    }

    /** Jio did not give a link: say why, then try the next server (if the channel has one) */
    private void jioFailed(String why) {
        lastJioError = why == null ? "" : why;
        boolean loginProblem = lastJioError.contains("লগইন");
        if (streams.size() < 2 || loginProblem) {
            ui.removeCallbacks(stuck);
            try { player.stop(); } catch (Throwable ignored) { }
            setState("Jio: " + lastJioError);
            return;
        }
        nextServer("Jio: " + lastJioError);
    }

    private void seekBy(long ms) {
        if (player == null) return;
        long pos = Math.max(0, player.getCurrentPosition() + ms);
        long dur = player.getDuration();
        if (dur != C.TIME_UNSET && dur > 0) pos = Math.min(pos, Math.max(0, dur - 2000));
        player.seekTo(pos);
    }

    private void step(int d) {
        cuSrno = null;      // next / previous channel is always live
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
        applySession(on);
    }

    // ---- media session: so the two-finger double tap of TalkBack (and headset / watch buttons) play and pause the channel
    private void setupSession() {
        try {
            session = new MediaSession(this, "AISmartTubeTv");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { ui.post(() -> { if (player != null) { if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare(); player.play(); updatePlay(); } }); }
                @Override public void onPause() { ui.post(() -> { if (player != null) { player.pause(); updatePlay(); } }); }
                @Override public void onStop() { ui.post(() -> { if (player != null) { player.pause(); updatePlay(); } }); }
                @Override public void onSkipToNext() { ui.post(() -> step(1)); }
                @Override public void onSkipToPrevious() { ui.post(() -> step(-1)); }
            });
            session.setActive(true);
            applySession(false);
        } catch (Throwable t) {
            session = null;
        }
    }

    private void applySession(boolean playing) {
        if (session == null) return;
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                    .build());
            if (queue != null && idx < queue.size()) {
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, queue.get(idx).name).build());
            }
            if (!session.isActive()) session.setActive(true);
        } catch (Throwable ignored) { }
    }

    private void toggleFav() {
        TvChannels.toggleFav(this, queue.get(idx));
        updateFav();
        setState(TvChannels.isFav(this, queue.get(idx)) ? "প্রিয়তে রাখা হয়েছে" : "প্রিয় থেকে সরানো হয়েছে");
    }

    private void updateFav() {
        bFav.setText(TvChannels.isFav(this, queue.get(idx)) ? "প্রিয় থেকে সরান" : "প্রিয়তে রাখুন");
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
        if (session != null) { try { session.setActive(false); session.release(); } catch (Throwable ignored) { } session = null; }
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        super.onDestroy();
    }
}
