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
    private Button bSleep;
    private final Runnable sleepRun = () -> { try { if (player != null) player.pause(); } catch (Throwable ignored) { } finish(); };
    private TextView nowTv;                          // "now showing" line (Jio channels)
    private String epgId = "";
    private List<JioClient.Prog> epgList = null;
    private final Runnable nowTick = () -> refreshNow();
    private MediaSession session;
    private int gen = 0;         // marks the newest start, so a late answer from Jio is ignored
    private long resumeMs = 0;          // where an earlier programme stopped on an error, so the fresh link continues from there
    private boolean jioRetried = false;   // a failing Jio channel gets one fresh link before the next server
    private String lastJioError = "";
    private String lastPlayErr = "";   // why the player gave up: shown at the end so a failing channel can be understood
    private final Runnable stuck = () -> { lastPlayErr = "১৫ সেকেন্ডে ছবি আসেনি"; nextServer("সময় লাগছে"); };

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
        nowTv = new TextView(this);
        nowTv.setTextSize(16);
        nowTv.setTextColor(0xFFFFB84A);
        nowTv.setPadding(dp(16), 0, dp(16), dp(4));
        nowTv.setVisibility(View.GONE);
        nowTv.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(nowTv);
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

        bSleep = btn("ঘুমের টাইমার", v -> sleepMenu());
        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.addView(bSleep, w());
        controls.addView(r3);

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
        RadioHub.stopForOther();
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
                    resumeMs = 0;
                    setState(serverNote("চলছে"));
                    try { TvChannels.markGood(TvPlayerActivity.this, queue.get(idx), streams.get(si).url); } catch (Throwable ignored) { }
                }
                updatePlay();
            }

            @Override
            public void onIsPlayingChanged(boolean p) { updatePlay(); }

            @Override
            public void onPlayerError(PlaybackException e) {
                if (cuSrno != null) {
                    long cp = 0;
                    try { cp = player.getCurrentPosition(); } catch (Throwable ignored) { }
                    if (cp > 0) resumeMs = cp;
                }
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                // Jio answered 404 for this quality: other qualities of the same channel often still work
                try {
                    Throwable c0 = e.getCause();
                    if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                            && c0 instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                            && ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c0).responseCode == 404
                            && altI < alts.size() && cuSrno == null) {
                        String u = alts.get(altI++);
                        setState(serverNote("Jio: অন্য মানের লিংক চেষ্টা হচ্ছে (" + altI + "/" + alts.size() + ")"));
                        player.setMediaItem(new MediaItem.Builder().setUri(u).setMimeType(MimeTypes.APPLICATION_M3U8).build());
                        player.prepare();
                        player.play();
                        ui.removeCallbacks(stuck);
                        ui.postDelayed(stuck, 15000);
                        return;
                    }
                } catch (Throwable ignored) { }
                try {
                    Throwable c1 = e.getCause();
                    if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                            && c1 instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                            && drmFb != null && !drmFbUsed && cuSrno == null) {
                        drmFbUsed = true;
                        playKind = "DRM (HLS 404 হওয়ায়)";
                        setState(serverNote("Jio: DRM পদ্ধতিতে চেষ্টা হচ্ছে…"));
                        http.setDefaultRequestProperties(drmFb.headers);
                        player.setMediaItem(drmItem(drmFb));
                        player.prepare();
                        player.play();
                        ui.removeCallbacks(stuck);
                        ui.postDelayed(stuck, 15000);
                        return;
                    }
                } catch (Throwable ignored) { }
                String cause = "";
                try {
                    Throwable c = e.getCause();
                    if (c instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        android.net.Uri bu = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c).dataSpec.uri;
                        cause = " — " + (playKind.isEmpty() ? "" : playKind + " ") + "কোড "
                                + ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c).responseCode
                                + " " + bu.getHost() + bu.getPath();
                    } else if (c != null) cause = " — " + (playKind.isEmpty() ? "" : playKind + " ") + c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
                    if (cause.length() > 260) cause = cause.substring(0, 260);
                } catch (Throwable ignored) { }
                lastPlayErr = e.getErrorCodeName() + cause;
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
            setState("Jio-র লিংক নতুন করে আনা হচ্ছে…" + (lastPlayErr.isEmpty() ? "" : " (আগের ভুল: " + lastPlayErr + ")"));
            startStream();
            return;
        }
        failed++;
        if (failed >= Math.max(2, streams.size())) {
            try { player.stop(); } catch (Throwable ignored) { }
            setState("এই চ্যানেল এখন চলছে না (" + streams.size() + "টি সার্ভার চেষ্টা হয়েছে)। 'পরের চ্যানেল' চাপুন, অথবা একটু পরে আবার চেষ্টা করুন।"
                    + (lastPlayErr.isEmpty() ? "" : " কারণ: " + lastPlayErr));
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
        epgList = null;
        epgId = "";
        nowTv.setText("");
        nowTv.setVisibility(View.GONE);
        ui.removeCallbacks(nowTick);
        if (cuSrno == null) refreshNow();
        si = 0;
        failed = 0;
        jioRetried = false;
        resumeMs = 0;
        setState(serverNote("লোড হচ্ছে…"));
        updateFav();
        startStream();
    }

    private JioClient.Play drmFb;
    private boolean drmFbUsed;
    private String playKind = "";

    private MediaItem drmItem(JioClient.Play fp) {
        return new MediaItem.Builder().setUri(fp.url).setMimeType(MimeTypes.APPLICATION_MPD)
                .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(fp.license)
                        .setLicenseRequestHeaders(fp.licenseHeaders)
                        .setMultiSession(true)
                        .build())
                .build();
    }

    private void startStream() {
        final TvChannels.Stream s = streams.get(si);
        final String jioId = JioClient.idOf(s.url);
        ui.removeCallbacks(stuck);
        if (!jioId.isEmpty()) {
            // Jio channel: ask Jio for a fresh playing link first (takes a moment, so not on the main thread)
            final int my = ++gen;
            try { player.stop(); } catch (Throwable ignored) { }
            setState(serverNote("Jio থেকে লিংক আনা হচ্ছে…" + (lastPlayErr.isEmpty() ? "" : " (আগের ভুল: " + lastPlayErr + ")")));
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
                    alts = fp.alts;
                    altI = 0;
                    drmFb = fp.drmFallback;
                    drmFbUsed = false;
                    playKind = (fp.drm ? "DRM" : "HLS") + (fp.info.isEmpty() ? "" : " [" + fp.info + "]");
                    MediaItem mi = fp.drm ? drmItem(fp)
                            : new MediaItem.Builder().setUri(fp.url).setMimeType(MimeTypes.APPLICATION_M3U8).build();
                    if (cuSrno != null && resumeMs > 0) player.setMediaItem(mi, resumeMs);   // fresh link, same place in the programme
                    else player.setMediaItem(mi);
                    player.prepare();
                    player.play();
                    updatePlay();
                    ui.postDelayed(stuck, 15000);   // the 15 s count starts now, not while Jio was still answering
                    scheduleToken(fp, my, jioId);
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
        ui.postDelayed(stuck, 15000);   // no picture after 15 s: next server
    }

    // ---- keep the Jio access token fresh while playing, so the picture does not stop when it runs out
    private int tokFail = 0;
    private List<String> alts = new java.util.ArrayList<>();     // other quality links of the Jio channel playing now
    private int altI = 0;

    private void scheduleToken(JioClient.Play p, final int myGen, final String id) {
        long delay = 4 * 60000L;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("exp=(\\d+)").matcher(p.hdnea);
            if (m.find()) {
                long left = Long.parseLong(m.group(1)) * 1000L - System.currentTimeMillis() - 90000L;   // 90 s before it runs out
                delay = Math.max(20000L, Math.min(left, 20 * 60000L));
            }
        } catch (Throwable ignored) { }
        ui.postDelayed(() -> refreshToken(myGen, id), delay);
    }

    private void refreshToken(final int myGen, final String id) {
        if (isFinishing() || player == null || myGen != gen) return;       // another channel / server was started meanwhile
        new Thread(() -> {
            JioClient.Play np = null;
            try {
                np = cuSrno != null ? JioClient.catchup(getApplicationContext(), id, cuSrno, cuStart, cuEnd)
                        : JioClient.resolve(getApplicationContext(), id);
            } catch (Throwable ignored) { }
            final JioClient.Play fp = np;
            ui.post(() -> {
                if (isFinishing() || player == null || myGen != gen) return;
                if (fp != null && !fp.hdnea.isEmpty()) {
                    http.setDefaultRequestProperties(fp.headers);          // the next pieces ask with the new token; playing is not touched
                    tokFail = 0;
                    scheduleToken(fp, myGen, id);
                } else if (++tokFail <= 5) {
                    ui.postDelayed(() -> refreshToken(myGen, id), 60000L);
                }
            });
        }, "jio-token").start();
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

    /** the Jio channel's schedule gives the name of the programme that is on right now */
    private void refreshNow() {
        ui.removeCallbacks(nowTick);
        if (isFinishing() || queue == null || idx >= queue.size() || cuSrno != null) return;
        String id = "";
        for (TvChannels.Stream x : queue.get(idx).streams) {
            String j = JioClient.idOf(x.url);
            if (!j.isEmpty()) { id = j; break; }
        }
        if (id.isEmpty()) return;
        if (!id.equals(epgId) || epgList == null) {
            final String fid = id;
            epgId = id;
            new Thread(() -> {
                List<JioClient.Prog> got = null;
                try { got = JioClient.epg(getApplicationContext(), fid, 0); } catch (Throwable ignored) { }
                final List<JioClient.Prog> g = got;
                ui.post(() -> {
                    if (!fid.equals(epgId)) return;      // another channel was opened meanwhile
                    epgList = g == null ? new java.util.ArrayList<JioClient.Prog>() : g;
                    showNow();
                    ui.postDelayed(nowTick, 30000);
                });
            }, "jio-epg").start();
            return;
        }
        showNow();
        ui.postDelayed(nowTick, 30000);
    }

    private void showNow() {
        if (epgList == null || nowTv == null) return;
        long now = System.currentTimeMillis();
        JioClient.Prog cur = null;
        for (JioClient.Prog p : epgList) if (p.start <= now && now < p.end) { cur = p; break; }
        if (cur == null) { nowTv.setVisibility(View.GONE); return; }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        String t = "এখন চলছে: " + cur.title + " (" + f.format(new java.util.Date(cur.start)) + " – " + f.format(new java.util.Date(cur.end)) + ")";
        for (JioClient.Prog p : epgList) {
            if (p.start >= cur.end) {
                t += "\nএরপর: " + p.title + " (" + f.format(new java.util.Date(p.start)) + ")";
                break;
            }
        }
        if (!t.contentEquals(nowTv.getText())) nowTv.setText(t);
        nowTv.setVisibility(View.VISIBLE);
    }

    private void sleepMenu() {
        final int[] mins = {0, 15, 30, 60, 90};
        String[] names = {"টাইমার বন্ধ করুন", "১৫ মিনিট পরে বন্ধ", "৩০ মিনিট পরে বন্ধ", "৬০ মিনিট পরে বন্ধ", "৯০ মিনিট পরে বন্ধ"};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("ঘুমের টাইমার")
                .setItems(names, (d, which) -> {
                    ui.removeCallbacks(sleepRun);
                    if (mins[which] == 0) {
                        bSleep.setText("ঘুমের টাইমার");
                        setState("টাইমার বন্ধ করা হয়েছে");
                    } else {
                        ui.postDelayed(sleepRun, mins[which] * 60000L);
                        bSleep.setText("টাইমার: " + mins[which] + " মিনিট");
                        setState(mins[which] + " মিনিট পরে টিভি বন্ধ হয়ে যাবে");
                    }
                }).setNegativeButton("বন্ধ", null).show();
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

    /** play pressed after a failure: ask for a fresh link and start again from the first server */
    private void retryNow() {
        failed = 0;
        jioRetried = false;
        startStream();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_IDLE) retryNow();
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
                @Override public void onPlay() { ui.post(() -> { if (player != null) { if (player.getPlaybackState() == Player.STATE_IDLE) retryNow(); else player.play(); updatePlay(); } }); }
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
