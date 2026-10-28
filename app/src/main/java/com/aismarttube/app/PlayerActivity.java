package com.aismarttube.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native player (ExoPlayer). Fast TalkBack, real background playback, media buttons.
 * AI features stay in the web player: the "AI সহায়তা" button opens it on the same video.
 */
public class PlayerActivity extends AppCompatActivity implements VideoAdapter.Listener {

    static final String EXTRA_QUEUE = "queue";
    static final String EXTRA_INDEX = "index";
    private static final int REQ_STORAGE = 5501;
    private static final int REQ_NOTIFY = 5502;

    /** the running native player; media buttons go here while {@link #owner} is true */
    static volatile PlayerActivity current;
    static volatile boolean owner = false;

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String[] SRC_ORDER = {"newpipe", "piped", "invidious"};
    private static final double[] SPEEDS = {0.75, 1, 1.25, 1.5, 2};
    private static final int[] SLEEP = {0, 15, 30, 60};

    // ------------------------------------------------------------ static helpers

    static Intent intent(Context c, List<VideoItem> list, int idx) {
        int from = Math.max(0, idx - 5);
        int to = Math.min(list.size(), idx + 35);
        JSONArray a = new JSONArray();
        for (int i = from; i < to; i++) a.put(list.get(i).toJson());
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra(EXTRA_QUEUE, a.toString());
        i.putExtra(EXTRA_INDEX, idx - from);
        return i;
    }

    /** notification / headset / TalkBack two-finger tap. Returns false when the web player should handle it. */
    static boolean handleRemote(String cmd) {
        final PlayerActivity a = current;
        if (a == null || !owner || cmd == null) return false;
        a.ui.post(() -> a.exec(cmd));
        return true;
    }

    /** the web player (or radio) started: native must be quiet */
    static void yieldToOthers() {
        final PlayerActivity a = current;
        owner = false;
        if (a != null) a.ui.post(() -> {
            try { if (a.player != null) a.player.pause(); } catch (Throwable ignored) {}
        });
    }

    // ----------------------------------------------------------------- fields

    private boolean replaced = false;
    private int forceHeight = 0;       // 0 = automatic
    private long resumeMs = -1;        // set when the quality is changed mid-video
    private Button pQual, pLang, pSubs;
    private String audioPref = "";    // preferred audio language ("bn") or track id; empty = automatic
    private String subLang = "";      // "" off | bn | en | hi | orig
    final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private ExoPlayer player;
    private PlayerView pv;
    private FrameLayout videoBox;
    private View panel, fsBar;
    private ProgressBar spin;
    private TextView title, meta, status, timeText, desc, upHead;
    private SeekBar seek;
    private ImageButton bPlay, fsPlay;
    private Button bSub, bChan, bWeb, pAudio, pSpeed, pTimer, pLike, pLater, pSwipe;
    private VideoAdapter upAdapter;

    private final List<VideoItem> queue = new ArrayList<>();
    private int qIndex = 0;
    private VideoItem cur;
    private int token = 0;
    private JSONObject streams;
    private final List<String> tried = new ArrayList<>();
    private boolean audioMode = false;
    private double speed = 1;
    private int sleepIdx = 0;
    private Runnable sleepRun;
    private long lastSave = 0;
    private boolean fullscreen = false;
    private boolean seekTouching = false;
    private boolean seekA11yFocus = false;
    private boolean notifAsked = false;
    private String[] pendingDl;
    private MediaSession session;

    // --------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_player);
        final PlayerActivity older = current;
        current = this;
        if (older != null && older != this) older.replaceBy();   // only one video plays; back goes to the screen it was opened from

        pv = findViewById(R.id.playerView);
        videoBox = findViewById(R.id.videoBox);
        panel = findViewById(R.id.scroll);
        fsBar = findViewById(R.id.fsBar);
        spin = findViewById(R.id.spin);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        status = findViewById(R.id.status);
        timeText = findViewById(R.id.timeText);
        desc = findViewById(R.id.desc);
        upHead = findViewById(R.id.upHead);
        seek = findViewById(R.id.seek);
        bPlay = findViewById(R.id.bPlay);
        fsPlay = findViewById(R.id.fsPlay);
        bSub = findViewById(R.id.bSub);
        bChan = findViewById(R.id.bChan);
        bWeb = findViewById(R.id.bWeb);
        ViewCompat.setAccessibilityHeading(title, true);

        // same source order as the web player
        try {
            NewPipeHelper.setPrimary(Store.settingStr(this, "primary", "newpipe"));
            NewPipeHelper.setFallback(Store.settingBool(this, "fallback", true));
        } catch (Throwable ignored) {}
        io.execute(() -> { try { NewPipeHelper.ensureInit(); } catch (Throwable ignored) {} });

        speed = parseSpeed();
        audioMode = Store.settingBool(this, "audioOnly", false);

        buildPlayer();
        buildPills();
        wireButtons();
        setupSession();
        wireSwipe();

        RecyclerView up = findViewById(R.id.upnext);
        up.setLayoutManager(new LinearLayoutManager(this));
        up.setNestedScrollingEnabled(false);
        upAdapter = new VideoAdapter(this);
        up.setAdapter(upAdapter);

        paintSkipLabels();
        ui.post(tick);
        loadFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadFromIntent(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePos();
        if (!Store.settingBool(this, "bgPlay", true) && player != null && player.isPlaying()) player.pause();
    }

    /** a newer player screen took over: stop quietly and leave without touching the shared notification */
    private void replaceBy() {
        replaced = true;
        try { if (player != null) player.stop(); } catch (Throwable ignored) {}
        finish();
    }

    @Override
    protected void onDestroy() {
        final boolean mine = current == this;   // false when a newer player screen replaced this one
        final boolean wasOwner = mine && owner;   // false when the web player or the radio took over: leave their notification alone
        if (mine) {
            current = null;
            owner = false;
        }
        ui.removeCallbacksAndMessages(null);
        savePos();
        try { if (player != null) { player.release(); player = null; } } catch (Throwable ignored) {}
        if (wasOwner) {
            try { stopService(new Intent(this, PlaybackService.class)); } catch (Throwable ignored) {}
        }
        if (session != null) {
            try { session.setActive(false); session.release(); } catch (Throwable ignored) {}
            session = null;
        }
        if (wasOwner) {
            PlaybackService.sessionToken = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    // ----------------------------------------------------------------- player

    @SuppressWarnings("UnsafeOptInUsageError")
    private void buildPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
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
        pv.setUseController(false);
        pv.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                spin.setVisibility(st == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (st == Player.STATE_ENDED) onEnded();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                paintPlay(playing);
                if (replaced) return;
                if (playing) { owner = true; RadioHub.stopForVideo(); askNotify(); }
                applyPlaybackState(playing);
                updateService(playing);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                tryNextSource("প্লে করা যায়নি (" + e.getErrorCodeName() + ")");
            }
        });
    }

    private double parseSpeed() {
        try { return Double.parseDouble(Store.settingStr(this, "speed", "1")); } catch (Throwable t) { return 1; }
    }

    private int skipSecs() {
        try {
            int v = (int) Double.parseDouble(Store.settingStr(this, "skip", "10"));
            return Math.max(5, Math.min(1800, v));
        } catch (Throwable t) { return 10; }
    }

    private void onEnded() {
        if (cur != null) Store.putPos(this, cur.url, 0);
        paintPlay(false);
        if (Store.settingBool(this, "autoplay", true) && qIndex + 1 < queue.size()) next(true);
    }

    // ------------------------------------------------------------- loading

    private void loadFromIntent(Intent in) {
        if (in == null) return;
        String q = in.getStringExtra(EXTRA_QUEUE);
        if (q == null || q.isEmpty()) {
            String vid = externalVideoId(in);
            if (vid == null) return;
            in.setData(null);
            in.removeExtra(Intent.EXTRA_TEXT);
            VideoItem v = new VideoItem("", "https://www.youtube.com/watch?v=" + vid, "", "", 0, 0,
                    "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg");
            JSONArray one = new JSONArray();
            one.put(v.toJson());
            q = one.toString();
            in.putExtra(EXTRA_INDEX, 0);
        }
        in.removeExtra(EXTRA_QUEUE);
        try {
            JSONArray a = new JSONArray(q);
            List<VideoItem> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                VideoItem v = VideoItem.fromJson(a.optJSONObject(i));
                if (v != null && !v.url.isEmpty()) list.add(v);
            }
            if (list.isEmpty()) return;
            int idx = Math.max(0, Math.min(list.size() - 1, in.getIntExtra(EXTRA_INDEX, 0)));
            boolean same = cur != null && cur.url.equals(list.get(idx).url)
                    && player != null && player.getMediaItemCount() > 0;
            queue.clear();
            queue.addAll(list);
            if (same) {
                qIndex = idx;
                refreshUpNext();
                return;
            }
            playIndex(idx, true);
        } catch (Throwable t) {
            say("ভিডিও খোলা যায়নি: " + t.getMessage());
        }
    }

    /** বাইরের লিংক (ক্লিক বা শেয়ার) থেকে ১১ অক্ষরের ভিডিও আইডি বের করে; না পেলে null */
    private static String externalVideoId(Intent in) {
        String raw = null;
        if (in.getData() != null) raw = in.getData().toString();
        else if (Intent.ACTION_SEND.equals(in.getAction())) raw = in.getStringExtra(Intent.EXTRA_TEXT);
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/|/v/)([A-Za-z0-9_-]{11})").matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    private void playIndex(int idx, boolean userAction) {
        if (idx < 0 || idx >= queue.size()) return;
        savePos();
        qIndex = idx;
        cur = queue.get(idx);
        token++;
        tried.clear();
        streams = null;
        try { player.stop(); player.clearMediaItems(); } catch (Throwable ignored) {}
        title.setText(cur.title.isEmpty() ? "ভিডিও" : cur.title);
        paintMeta(0);
        status.setVisibility(View.GONE);
        desc.setText("বিবরণ লোড হচ্ছে…");
        seek.setMax(1);
        seek.setProgress(0);
        timeText.setText("");
        paintLists();
        paintSub();
        refreshUpNext();
        spin.setVisibility(View.VISIBLE);
        applyPlaybackState(false);
        if (userAction && Store.settingBool(this, "focusHeading", true)) {
            ui.postDelayed(() -> {
                try { title.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); }
                catch (Throwable ignored) {}
            }, 350);
        }
        boolean fb = Store.settingBool(this, "fallback", true);
        fetch(token, fb ? "auto" : Store.settingStr(this, "primary", "newpipe"));
    }

    private void fetch(final int t, final String src) {
        final String url = cur.url;
        io.execute(() -> {
            JSONObject d;
            try {
                String raw = "auto".equals(src) ? NewPipeHelper.getStreams(url) : NewPipeHelper.getStreams(url, src);
                d = new JSONObject(raw);
            } catch (Throwable e) {
                d = new JSONObject();
                try { d.put("ok", false); d.put("error", String.valueOf(e.getMessage())); } catch (Throwable ignored) {}
            }
            final JSONObject data = d;
            ui.post(() -> onStreams(t, data, src));
        });
    }

    private void onStreams(int t, JSONObject d, String src) {
        if (t != token || isFinishing() || player == null) return;
        if (!d.optBoolean("ok", false)) {
            if (!"auto".equals(src) && !tried.contains(src)) tried.add(src);
            tryNextSource(d.optString("error", "স্ট্রিম পাওয়া যায়নি"));
            return;
        }
        streams = d;
        String used = d.optString("source", "auto".equals(src) ? "" : src);
        if (!used.isEmpty() && !tried.contains(used)) tried.add(used);

        String dsc = stripHtml(d.optString("description", ""));
        desc.setText(dsc.isEmpty() ? "(বিবরণ নেই)" : dsc);
        long views = d.optLong("views", 0);
        long dur = d.optLong("duration", 0);
        String uu = d.optString("uploaderUrl", "");
        if ((cur.uploaderUrl.isEmpty() && !uu.isEmpty()) || (views > 0 && cur.views == 0) || (dur > 0 && cur.duration == 0)) {
            VideoItem n = new VideoItem(cur.title, cur.url, cur.uploader.isEmpty() ? d.optString("uploader", "") : cur.uploader,
                    cur.uploaderUrl.isEmpty() ? uu : cur.uploaderUrl,
                    cur.duration == 0 ? dur : cur.duration, cur.views == 0 ? views : cur.views, cur.thumb);
            cur = n;
            if (qIndex < queue.size()) queue.set(qIndex, n);
        }
        paintMeta(used.isEmpty() ? 0 : 1, used);
        paintSub();
        Store.addHistory(this, cur);
        startPlayback(d);
    }

    private void tryNextSource(String reason) {
        String next = null;
        for (String s : SRC_ORDER) if (!tried.contains(s)) { next = s; break; }
        boolean fb = Store.settingBool(this, "fallback", true);
        if (next == null || !fb) {
            spin.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
            status.setText(reason + "। \"AI সহায়তা\" বোতামে চাপলে ওয়েব প্লেয়ারে চেষ্টা করতে পারবেন।");
            ViewCompat.setAccessibilityLiveRegion(status, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
            return;
        }
        tried.add(next);
        note("অন্য সোর্স (" + srcName(next) + ") চেষ্টা হচ্ছে");
        spin.setVisibility(View.VISIBLE);
        fetch(token, next);
    }

    private static String srcName(String s) {
        if ("piped".equals(s)) return "Piped";
        if ("invidious".equals(s)) return "Invidious";
        return "NewPipe";
    }

    private void startPlayback(JSONObject d) {
        String url = "";
        boolean am = audioMode;
        if (am) {
            JSONObject a = audioPref.isEmpty() ? null : pickAudioTrack(d, audioPref);
            if (a == null) a = pickAudioForListening(d);
            if (a != null) url = a.optString("url", ""); else am = false;
        }
        if (url.isEmpty()) url = pickVideo(d);
        if (url.isEmpty()) {
            JSONObject a = pickAudio(d, false);
            if (a != null) { url = a.optString("url", ""); am = true; }
        }
        if (url.isEmpty()) { tryNextSource("প্লে করার লিংক পাওয়া যায়নি"); return; }

        pv.setVisibility(am ? View.INVISIBLE : View.VISIBLE);
        pAudio.setText(audioMode ? "শুধু অডিও: চালু" : "শুধু অডিও: বন্ধ");

        int tracks = audioTrackCount(d);
        boolean hasSubs = d.optJSONArray("subtitles") != null && d.optJSONArray("subtitles").length() > 0;
        pLang.setVisibility(tracks >= 2 ? View.VISIBLE : View.GONE);
        pSubs.setVisibility(hasSubs ? View.VISIBLE : View.GONE);
        MediaSource merged = null;
        MediaItem.SubtitleConfiguration subCfg = am || !hasSubs ? null : subtitleFor(d);
        JSONObject trackAudio = (am || audioPref.isEmpty() || tracks < 2) ? null : pickAudioTrack(d, audioPref);
        if (!audioPref.isEmpty() && !am && tracks >= 2 && trackAudio == null) note("এই ভিডিওতে এই ভাষার অডিও নেই, মূল অডিও চলছে");
        if (!am && (forceHeight > 0 || trackAudio != null)) {
            if (trackAudio != null) {
                // a chosen audio language: picture and sound come as separate streams and are joined
                JSONObject vs = pickVideoOnly(d, forceHeight > 0 ? forceHeight : 360);
                if (vs != null) merged = buildMerged(vs.optString("url", ""), trackAudio.optString("url", ""), subCfg);
            } else {
                JSONObject vs = pickByHeight(d, forceHeight);
                if (vs != null) {
                    if (vs.optBoolean("hasAudio", true)) {
                        url = vs.optString("url", url);
                    } else {
                        JSONObject au = pickAudio(d, false);
                        if (au != null) merged = buildMerged(vs.optString("url", ""), au.optString("url", ""), subCfg);
                    }
                }
            }
        }
        MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.parse(url));
        if (url.contains("m3u8") || url.contains("/hls_")) mb.setMimeType("application/x-mpegURL");
        if (subCfg != null) mb.setSubtitleConfigurations(java.util.Collections.singletonList(subCfg));
        long startMs = 0;
        if (Store.settingBool(this, "resume", true)) {
            long pos = Store.getPos(this, cur.url);
            long dur = cur.duration > 0 ? cur.duration : d.optLong("duration", 0);
            if (pos > 10 && dur > 0 && pos < dur - 15) {
                startMs = pos * 1000L;
                note("আগের জায়গা থেকে চালু: " + spoken(pos));
            }
        }
        if (resumeMs >= 0) { startMs = resumeMs; resumeMs = -1; }
        if (merged != null) player.setMediaSource(merged, startMs);
        else player.setMediaItem(mb.build(), startMs);
        player.setPlaybackParameters(new PlaybackParameters((float) speed));
        player.prepare();
        player.play();
        owner = true;
        PlayerActivity.this.applyMeta();
    }

    /** best stream of exactly this height: video+audio together first, then MP4 before WebM */
    private JSONObject pickByHeight(JSONObject d, int h) {
        JSONArray a = d.optJSONArray("videoStreams");
        if (a == null) return null;
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("url", "").isEmpty() || heightOf(o) != h) continue;
            int sc = (o.optBoolean("hasAudio", true) ? 2 : 0)
                    + (o.optString("format", "").toLowerCase().contains("webm") ? 0 : 1);
            if (sc > bestScore) { best = o; bestScore = sc; }
        }
        return best;
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    private MediaSource buildMerged(String videoUrl, String audioUrl, MediaItem.SubtitleConfiguration sub) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DefaultDataSource.Factory dsf = new DefaultDataSource.Factory(this, http);
        MediaItem.Builder vb = new MediaItem.Builder().setUri(Uri.parse(videoUrl));
        if (sub != null) vb.setSubtitleConfigurations(java.util.Collections.singletonList(sub));
        MediaSource video = new DefaultMediaSourceFactory(dsf).createMediaSource(vb.build());
        MediaSource audio = new ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(audioUrl));
        return new MergingMediaSource(video, audio);
    }

    // ------------------------------------------------ audio language and subtitles

    private static String langBase(String tag) {
        if (tag == null) return "";
        String t = tag.trim().toLowerCase(java.util.Locale.US);
        int i = t.indexOf('_');
        if (i < 0) i = t.indexOf('-');
        if (i < 0) i = t.indexOf('.');
        return i > 0 ? t.substring(0, i) : t;
    }

    /** best audio stream of the wanted track: matches the language ("bn") or the exact track id */
    private JSONObject pickAudioTrack(JSONObject d, String pref) {
        JSONArray a = d.optJSONArray("audioStreams");
        if (a == null || pref == null || pref.isEmpty()) return null;
        JSONObject best = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("url", "").isEmpty()) continue;
            String loc = langBase(o.optString("locale", ""));
            String id = o.optString("trackId", "");
            if (!(pref.equals(loc) || pref.equals(id) || (!id.isEmpty() && langBase(id).equals(pref)))) continue;
            if (best == null) { best = o; continue; }
            boolean m4 = o.optString("format", "").toLowerCase().matches(".*(m4a|mp4|aac).*");
            boolean bm4 = best.optString("format", "").toLowerCase().matches(".*(m4a|mp4|aac).*");
            if ((m4 && !bm4) || (m4 == bm4 && o.optLong("bitrate", 0) > best.optLong("bitrate", 0))) best = o;
        }
        return best;
    }

    /** picture-only stream near the wanted height (not above it when possible) */
    private JSONObject pickVideoOnly(JSONObject d, int target) {
        JSONArray a = d.optJSONArray("videoStreams");
        if (a == null) return null;
        JSONObject best = null, smallest = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("url", "").isEmpty() || o.optBoolean("hasAudio", true)) continue;
            int h = heightOf(o);
            if (h <= 0) continue;
            if (smallest == null || h < heightOf(smallest)) smallest = o;
            if (h <= target && (best == null || h > heightOf(best)
                    || (h == heightOf(best) && !o.optString("format", "").toLowerCase().contains("webm")))) best = o;
        }
        return best != null ? best : smallest;
    }

    /** how many different audio languages this video offers */
    private int audioTrackCount(JSONObject d) {
        JSONArray a = d.optJSONArray("audioStreams");
        List<String> keys = new ArrayList<>();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String loc = langBase(o.optString("locale", ""));
                String key = !loc.isEmpty() ? loc : o.optString("trackId", "");
                if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
            }
        }
        return keys.size();
    }

    private void audioMenu() {
        if (streams == null) { say("লিংক এখনও আসেনি, একটু পরে চাপুন"); return; }
        JSONArray a = streams.optJSONArray("audioStreams");
        final List<String> keys = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String loc = langBase(o.optString("locale", ""));
                String id = o.optString("trackId", "");
                String key = !loc.isEmpty() ? loc : id;
                if (key.isEmpty() || keys.contains(key)) continue;
                keys.add(key);
                String nm = o.optString("trackName", "");
                if (nm.isEmpty()) nm = langName(loc.isEmpty() ? id : loc);
                if (o.optString("trackType", "").toUpperCase().contains("DUB")) nm += " (ডাব করা)";
                names.add(nm);
            }
        }
        if (keys.size() < 2) { say("এই ভিডিওতে একটাই অডিও ভাষা আছে"); return; }
        String[] labels = new String[names.size() + 1];
        labels[0] = "অটো (মূল)";
        for (int i = 0; i < names.size(); i++) labels[i + 1] = names.get(i);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("অডিও ভাষা")
                .setItems(labels, (dlg, which) -> {
                    audioPref = which == 0 ? "" : keys.get(which - 1);
                    pLang.setText("অডিও ভাষা: " + (which == 0 ? "অটো" : names.get(which - 1)));
                    say(which == 0 ? "মূল অডিও" : "অডিও ভাষা " + names.get(which - 1));
                    if (player != null && streams != null) {
                        resumeMs = player.getCurrentPosition();
                        startPlayback(streams);
                    }
                })
                .setNegativeButton("থাক", null)
                .show();
    }

    private static String langName(String code) {
        switch (langBase(code)) {
            case "bn": return "বাংলা";
            case "en": return "ইংরেজি";
            case "hi": return "হিন্দি";
            case "ur": return "উর্দু";
            case "ar": return "আরবি";
            case "es": return "স্পেনীয়";
            case "fr": return "ফরাসি";
            case "ja": return "জাপানি";
            case "ko": return "কোরিয়ান";
            case "ta": return "তামিল";
            case "te": return "তেলুগু";
            case "mr": return "মারাঠি";
            default: return code == null || code.isEmpty() ? "অন্য ভাষা" : code;
        }
    }

    private void subMenu() {
        final String[] codes = {"", "bn", "en", "hi", "orig"};
        final String[] labels = {"বন্ধ", "বাংলা (দরকারে অনুবাদ হয়ে)", "ইংরেজি", "হিন্দি", "ভিডিওর মূল ভাষা"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("সাবটাইটেল")
                .setItems(labels, (dlg, which) -> {
                    subLang = codes[which];
                    pSubs.setText("সাবটাইটেল: " + (which == 0 ? "বন্ধ" : which == 1 ? "বাংলা" : labels[which]));
                    say("সাবটাইটেল " + (which == 0 ? "বন্ধ" : labels[which]));
                    if (player != null && streams != null) {
                        resumeMs = player.getCurrentPosition();
                        startPlayback(streams);
                    }
                })
                .setNegativeButton("থাক", null)
                .show();
    }

    /** the subtitle file for the chosen language; a missing language is asked from YouTube as a translation (tlang) */
    private MediaItem.SubtitleConfiguration subtitleFor(JSONObject d) {
        JSONArray ss = d.optJSONArray("subtitles");
        if (subLang.isEmpty() || ss == null || ss.length() == 0) return null;
        JSONObject base = null;
        boolean translate = false;
        if ("orig".equals(subLang)) {
            base = ss.optJSONObject(0);
        } else {
            for (int i = 0; i < ss.length(); i++) {
                JSONObject o = ss.optJSONObject(i);
                if (o == null || !langBase(o.optString("lang", "")).equals(subLang)) continue;
                if (base == null || (base.optBoolean("auto", false) && !o.optBoolean("auto", false))) base = o;
            }
            if (base == null) {
                for (int i = 0; i < ss.length(); i++) {
                    JSONObject o = ss.optJSONObject(i);
                    if (o != null && !o.optBoolean("auto", false)) { base = o; break; }
                }
                if (base == null) base = ss.optJSONObject(0);
                translate = true;
            }
        }
        if (base == null || base.optString("url", "").isEmpty()) return null;
        String u = base.optString("url", "");
        if (translate && !u.contains("tlang=")) u += (u.contains("?") ? "&" : "?") + "tlang=" + subLang;
        String f = base.optString("format", "").toLowerCase();
        String mime = f.contains("vtt") ? "text/vtt"
                : (f.contains("srt") || f.contains("subrip")) ? "application/x-subrip"
                : "application/ttml+xml";
        return new MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
                .setMimeType(mime)
                .setLanguage("orig".equals(subLang) ? langBase(base.optString("lang", "")) : subLang)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();
    }

    private void qualityMenu() {
        if (streams == null) { say("লিংক এখনও আসেনি, একটু পরে চাপুন"); return; }
        JSONArray a = streams.optJSONArray("videoStreams");
        java.util.TreeSet<Integer> hs = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !o.optString("url", "").isEmpty() && heightOf(o) > 0) hs.add(heightOf(o));
            }
        }
        if (hs.isEmpty()) { say("এই ভিডিওতে কোয়ালিটি বাছাই নেই"); return; }
        final List<Integer> opts = new ArrayList<>(hs);
        String[] labels = new String[opts.size() + 1];
        labels[0] = "অটো";
        for (int i = 0; i < opts.size(); i++) labels[i + 1] = toBn(String.valueOf(opts.get(i))) + "p";
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("কোয়ালিটি")
                .setItems(labels, (dlg, which) -> {
                    forceHeight = which == 0 ? 0 : opts.get(which - 1);
                    pQual.setText("কোয়ালিটি: " + (forceHeight == 0 ? "অটো" : toBn(String.valueOf(forceHeight)) + "p"));
                    say(forceHeight == 0 ? "কোয়ালিটি অটো" : "কোয়ালিটি " + toBn(String.valueOf(forceHeight)) + "p");
                    if (player != null && streams != null) {
                        resumeMs = player.getCurrentPosition();
                        startPlayback(streams);
                    }
                })
                .setNegativeButton("থাক", null)
                .show();
    }

    private void openComments() {
        if (cur == null || cur.url.isEmpty()) { say("ভিডিও এখনও আসেনি"); return; }
        startActivity(new Intent(this, CommentsActivity.class)
                .putExtra(CommentsActivity.EXTRA_URL, cur.url)
                .putExtra(CommentsActivity.EXTRA_TITLE, cur.title));
    }

    private int heightOf(JSONObject s) {
        String q = s.optString("quality", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})").matcher(q);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String pickVideo(JSONObject d) {
        JSONArray a = d.optJSONArray("videoStreams");
        List<JSONObject> c = new ArrayList<>();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !o.optString("url", "").isEmpty() && o.optBoolean("hasAudio", true)) c.add(o);
            }
        }
        if (c.isEmpty()) return d.optString("playUrl", "");
        java.util.Collections.sort(c, (x, y) -> Integer.compare(heightOf(x), heightOf(y)));
        String q = Store.settingStr(this, "quality", "auto");
        JSONObject pick;
        if ("max".equals(q)) pick = c.get(c.size() - 1);
        else if ("low".equals(q)) pick = c.get(0);
        else {
            int target = 720;
            if (!"auto".equals(q)) {
                try { target = Integer.parseInt(q.replaceAll("\\D", "")); } catch (Throwable ignored) {}
            }
            pick = c.get(0);
            for (JSONObject o : c) {
                int h = heightOf(o);
                if (h > 0 && h <= target) pick = o;
            }
        }
        return pick.optString("url", "");
    }

    /** audio-only listening: the smallest usable stream when "কম ডাটা অডিও" is on, otherwise the best one */
    private JSONObject pickAudioForListening(JSONObject d) {
        if (Store.settingBool(this, "lowData", false)) {
            JSONArray a = d.optJSONArray("audioStreams");
            JSONObject low = null;
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null || o.optString("url", "").isEmpty()) continue;
                    long br = o.optLong("bitrate", 0);
                    if (br <= 0) continue;
                    boolean m4a = o.optString("format", "").toLowerCase().matches(".*(m4a|mp4|aac).*");
                    if (!m4a) continue;   // same container family as the normal choice, so it always plays
                    if (low == null || br < low.optLong("bitrate", 0)) low = o;
                }
            }
            if (low != null) return low;
        }
        return pickAudio(d, false);
    }

    private JSONObject pickAudio(JSONObject d, boolean needM4a) {
        JSONArray a = d.optJSONArray("audioStreams");
        if (a == null) return null;
        List<JSONObject> all = new ArrayList<>();
        List<JSONObject> m4a = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("url", "").isEmpty()) continue;
            all.add(o);
            if (o.optString("format", "").toLowerCase().matches(".*(m4a|mp4|aac).*")) m4a.add(o);
        }
        List<JSONObject> pool = needM4a ? m4a : (m4a.isEmpty() ? all : m4a);
        if (pool.isEmpty()) return null;
        JSONObject best = pool.get(0);
        for (JSONObject o : pool) if (o.optLong("bitrate", 0) > best.optLong("bitrate", 0)) best = o;
        return best;
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("&quot;", "\"").replaceAll("&#39;", "'").replaceAll("[ \\t]+", " ").trim();
    }

    // -------------------------------------------------------------------- UI

    private Button pill(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setBackgroundResource(R.drawable.pill_bg);
        b.setTextColor(0xFFEAF5F3);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        ((LinearLayout) findViewById(R.id.pills)).addView(b);
        return b;
    }

    private void buildPills() {
        pill("এখন কত সময়", v -> sayPosition());
        pSpeed = pill("", v -> cycleSpeed());
        paintSpeed();
        pill("পূর্ণ পর্দা", v -> setFullscreen(true));
        pQual = pill("কোয়ালিটি: অটো", v -> qualityMenu());
        pLang = pill("অডিও ভাষা: অটো", v -> audioMenu());
        pSubs = pill("সাবটাইটেল: বন্ধ", v -> subMenu());
        pLang.setVisibility(View.GONE);   // shown only for videos that really have other languages
        pSubs.setVisibility(View.GONE);
        pill("মন্তব্য", v -> openComments());
        pill("M4A ডাউনলোড", v -> download());
        pill("MP3 ডাউনলোড", v -> downloadMp3());
        pAudio = pill(audioMode ? "শুধু অডিও: চালু" : "শুধু অডিও: বন্ধ", v -> toggleAudio());
        pTimer = pill("টাইমার বন্ধ", v -> cycleTimer());
        pLike = pill("পছন্দ", v -> toggleList("liked"));
        pLater = pill("পরে দেখব", v -> toggleList("later"));
        pill("শেয়ার", v -> share());
        pSwipe = pill("", v -> flipSwipe());
        paintSwipe();
    }

    // ---- two-finger swipe (TalkBack): one direction = next video, the other = previous ----

    private boolean swipeFlip() { return Store.settingBool(this, "swipeFlip", false); }

    private void paintSwipe() {
        pSwipe.setText(swipeFlip() ? "দুই আঙুল: বামে সোয়াইপ = পরের" : "দুই আঙুল: ডানে সোয়াইপ = পরের");
        pSwipe.setContentDescription((swipeFlip()
                ? "দুই আঙুলে বামে সোয়াইপ করলে পরের ভিডিও, ডানে সোয়াইপে আগের।"
                : "দুই আঙুলে ডানে সোয়াইপ করলে পরের ভিডিও, বামে সোয়াইপে আগের।")
                + " দিক উল্টাতে চাপুন");
    }

    private void flipSwipe() {
        Store.putSettingBool(this, "swipeFlip", !swipeFlip());
        paintSwipe();
        say(swipeFlip() ? "এখন দুই আঙুলে বামে সোয়াইপ করলে পরের ভিডিও" : "এখন দুই আঙুলে ডানে সোয়াইপ করলে পরের ভিডিও");
    }

    private static final AccessibilityNodeInfoCompat.AccessibilityActionCompat A_LEFT =
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_LEFT;
    private static final AccessibilityNodeInfoCompat.AccessibilityActionCompat A_RIGHT =
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT;
    private static final AccessibilityNodeInfoCompat.AccessibilityActionCompat A_FWD =
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD;
    private static final AccessibilityNodeInfoCompat.AccessibilityActionCompat A_BACK =
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD;

    /** gesture -> video. Fingers moving right reach the app as "scroll left". */
    private boolean swipeTo(boolean fromLeftAction) {
        boolean goNext = swipeFlip() ? !fromLeftAction : fromLeftAction;
        if (goNext) next(false); else prev();
        return true;
    }

    /**
     * Works like the file manager's page swipe: TalkBack's two-finger left/right swipe goes to the nearest
     * parent that says it scrolls sideways, so the whole screen is marked that way.
     * "pager" views (outer screen, video box) also take forward/backward; the inner list keeps its own
     * up/down scrolling and only takes left/right.
     */
    private void wireSwipe() {
        AccessibilityDelegateCompat plain = new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setScrollable(true);
                info.addAction(A_LEFT);
                info.addAction(A_RIGHT);
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == A_LEFT.getId()) return swipeTo(true);
                if (action == A_RIGHT.getId()) return swipeTo(false);
                return super.performAccessibilityAction(host, action, args);
            }
        };
        AccessibilityDelegateCompat pager = new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setScrollable(true);
                info.addAction(A_LEFT);
                info.addAction(A_RIGHT);
                info.addAction(A_FWD);
                info.addAction(A_BACK);
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == A_LEFT.getId()) return swipeTo(true);
                if (action == A_RIGHT.getId()) return swipeTo(false);
                if (action == A_FWD.getId()) { next(false); return true; }
                if (action == A_BACK.getId()) { prev(); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        };
        int[] pagerIds = {android.R.id.content, R.id.videoBox};
        for (int id : pagerIds) {
            View v = findViewById(id);
            if (v != null) ViewCompat.setAccessibilityDelegate(v, pager);
        }
        // these sit between the controls and the screen edge and would otherwise swallow the gesture
        View pills = (View) findViewById(R.id.pills).getParent();
        int[] plainIds = {R.id.scroll, R.id.upnext};
        for (int id : plainIds) {
            View v = findViewById(id);
            if (v != null) ViewCompat.setAccessibilityDelegate(v, plain);
        }
        ViewCompat.setAccessibilityDelegate(pills, plain);
    }

    private void wireButtons() {
        bPlay.setOnClickListener(v -> exec("toggle"));
        fsPlay.setOnClickListener(v -> exec("toggle"));
        findViewById(R.id.bPrev).setOnClickListener(v -> prev());
        findViewById(R.id.bNext).setOnClickListener(v -> next(false));
        findViewById(R.id.bRew).setOnClickListener(v -> skip(-1));
        findViewById(R.id.bFwd).setOnClickListener(v -> skip(1));
        findViewById(R.id.fsRew).setOnClickListener(v -> skip(-1));
        findViewById(R.id.fsFwd).setOnClickListener(v -> skip(1));
        findViewById(R.id.fsExit).setOnClickListener(v -> setFullscreen(false));
        pv.setOnClickListener(v -> { if (fullscreen) exec("toggle"); });
        desc.setOnClickListener(v -> desc.setMaxLines(desc.getMaxLines() <= 3 ? 1000 : 3));
        bSub.setOnClickListener(v -> toggleSub());
        bChan.setOnClickListener(v -> openChannel());
        bWeb.setOnClickListener(v -> openWeb());

        seek.setKeyProgressIncrement(skipSecs());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || player == null) return;
                player.seekTo(p * 1000L);
                paintTimeDesc(p * 1000L, player.getDuration());
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { seekTouching = true; }

            @Override public void onStopTrackingTouch(SeekBar sb) { seekTouching = false; }
        });
        // while TalkBack sits on the slider, do not move it under the user's finger
        seek.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) seekA11yFocus = true;
                if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) seekA11yFocus = false;
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    private void paintSkipLabels() {
        String t = spoken(skipSecs());
        findViewById(R.id.bRew).setContentDescription(t + " পিছিয়ে যান");
        findViewById(R.id.bFwd).setContentDescription(t + " এগিয়ে যান");
        findViewById(R.id.fsRew).setContentDescription(t + " পিছিয়ে যান");
        findViewById(R.id.fsFwd).setContentDescription(t + " এগিয়ে যান");
        seek.setKeyProgressIncrement(skipSecs());
    }

    private void paintPlay(boolean playing) {
        bPlay.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        bPlay.setContentDescription(playing ? "থামান" : "চালান");
        fsPlay.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        fsPlay.setContentDescription(playing ? "থামান" : "চালান");
    }

    private void paintMeta(int srcFlag) { paintMeta(srcFlag, ""); }

    private void paintMeta(int srcFlag, String src) {
        StringBuilder sb = new StringBuilder();
        if (!cur.uploader.isEmpty()) sb.append(cur.uploader);
        String v = VideoItem.formatViewsBn(cur.views);
        if (!v.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(v);
        if (cur.duration > 0) sb.append(sb.length() > 0 ? " · " : "").append(clock(cur.duration));
        if (srcFlag == 1 && !src.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append("সোর্স: ").append(srcName(src));
        meta.setText(sb.toString());
    }

    private void paintLists() {
        boolean l = Store.listHas(this, "liked", cur.url);
        boolean s = Store.listHas(this, "later", cur.url);
        pLike.setText(l ? "পছন্দ করা হয়েছে" : "পছন্দ");
        pLike.setContentDescription(l ? "পছন্দ করা হয়েছে, বাতিল করতে চাপুন" : "পছন্দ করুন");
        pLater.setText(s ? "সেভ করা আছে" : "পরে দেখব");
        pLater.setContentDescription(s ? "পরে দেখার তালিকায় আছে, বাদ দিতে চাপুন" : "পরে দেখার তালিকায় রাখুন");
    }

    private void paintSub() {
        if (cur == null || cur.uploader.isEmpty()) {
            bSub.setVisibility(View.GONE);
            return;
        }
        bSub.setVisibility(View.VISIBLE);
        boolean on = Store.isSubbed(this, cur.uploader);
        bSub.setText(on ? "সাবস্ক্রাইবড" : "সাবস্ক্রাইব");
        bSub.setContentDescription((on ? "সাবস্ক্রাইবড, বাতিল করতে চাপুন: " : "সাবস্ক্রাইব করুন: ") + cur.uploader);
    }

    private void paintSpeed() {
        String t = "গতি " + toBn(trimNum(speed)) + "×";
        pSpeed.setText(t);
    }

    private static String trimNum(double v) {
        String s = String.format(java.util.Locale.US, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private void refreshUpNext() {
        List<VideoItem> rest = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) if (i != qIndex) rest.add(queue.get(i));
        upAdapter.submit(rest);
        upHead.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).url.equals(item.url)) {
                NativeFeedLoader.prefetch(item.url);
                playIndex(i, true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- actions

    void exec(String cmd) {
        if (player == null) return;
        switch (cmd) {
            case "play":
                if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                player.play();
                break;
            case "pause":
                player.pause();
                break;
            case "toggle":
                if (player.isPlaying()) player.pause();
                else {
                    if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                    player.play();
                }
                break;
            case "next": next(false); break;
            case "prev": prev(); break;
            case "ff": skip(1); break;
            case "rew": skip(-1); break;
            default: break;
        }
    }

    private void skip(int dir) {
        if (player == null) return;
        long dur = player.getDuration();
        long t = player.getCurrentPosition() + dir * skipSecs() * 1000L;
        if (t < 0) t = 0;
        if (dur != C.TIME_UNSET && dur > 0 && t > dur) t = dur;
        player.seekTo(t);
        onTick();
    }

    private void next(boolean auto) {
        if (qIndex + 1 < queue.size()) playIndex(qIndex + 1, !auto);
        else if (!auto) say("আর পরের ভিডিও নেই");
    }

    private void prev() {
        if (qIndex > 0) playIndex(qIndex - 1, true);
        else say("আর আগের ভিডিও নেই");
    }

    private void sayPosition() {
        long dur = player == null ? C.TIME_UNSET : player.getDuration();
        if (player != null && player.isCurrentMediaItemLive()) {
            long off = player.getCurrentLiveOffset();
            say(off != C.TIME_UNSET && off > 20000 ? "লাইভ চলছে, সরাসরি থেকে " + spoken(off / 1000) + " পিছনে" : "লাইভ চলছে, একদম সরাসরি");
            return;
        }
        if (cur == null || dur == C.TIME_UNSET || dur <= 0) {
            say("ভিডিও চালু নেই");
            return;
        }
        say(spoken(player.getCurrentPosition() / 1000) + " চলেছে, মোট " + spoken(dur / 1000));
    }

    private void cycleSpeed() {
        int i = 0;
        for (int k = 0; k < SPEEDS.length; k++) if (Math.abs(SPEEDS[k] - speed) < 0.01) i = k;
        i = (i + 1) % SPEEDS.length;
        speed = SPEEDS[i];
        player.setPlaybackParameters(new PlaybackParameters((float) speed));
        Store.putSetting(this, "speed", trimNum(speed));
        paintSpeed();
        say("গতি " + toBn(trimNum(speed)) + " গুণ");
    }

    private void toggleAudio() {
        audioMode = !audioMode;
        Store.putSettingBool(this, "audioOnly", audioMode);
        pAudio.setText(audioMode ? "শুধু অডিও: চালু" : "শুধু অডিও: বন্ধ");
        say(audioMode ? "শুধু অডিও চালু" : "শুধু অডিও বন্ধ");
        if (streams != null && player != null) {
            long pos = player.getCurrentPosition();
            boolean wasPlaying = player.isPlaying();
            String u = "";
            boolean am = audioMode;
            if (am) { JSONObject a = pickAudioForListening(streams); if (a != null) u = a.optString("url", ""); else am = false; }
            if (u.isEmpty()) u = pickVideo(streams);
            if (u.isEmpty()) return;
            pv.setVisibility(am ? View.INVISIBLE : View.VISIBLE);
            MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.parse(u));
            if (u.contains("m3u8") || u.contains("/hls_")) mb.setMimeType("application/x-mpegURL");
            player.setMediaItem(mb.build(), pos);
            player.prepare();
            player.setPlayWhenReady(wasPlaying);
        }
    }

    private void cycleTimer() {
        sleepIdx = (sleepIdx + 1) % SLEEP.length;
        if (sleepRun != null) ui.removeCallbacks(sleepRun);
        int m = SLEEP[sleepIdx];
        pTimer.setText(m > 0 ? "টাইমার " + toBn(String.valueOf(m)) + " মিনিট" : "টাইমার বন্ধ");
        if (m > 0) {
            sleepRun = () -> {
                try { player.pause(); } catch (Throwable ignored) {}
                sleepIdx = 0;
                pTimer.setText("টাইমার বন্ধ");
                say("টাইমার শেষ, ভিডিও থামানো হয়েছে");
            };
            ui.postDelayed(sleepRun, m * 60_000L);
            say("টাইমার " + toBn(String.valueOf(m)) + " মিনিট");
        } else {
            say("টাইমার বন্ধ");
        }
    }

    private void toggleList(String key) {
        if (cur == null) return;
        boolean now = Store.listToggle(this, key, cur);
        paintLists();
        if ("liked".equals(key)) say(now ? "পছন্দের তালিকায় যোগ হয়েছে" : "পছন্দের তালিকা থেকে বাদ");
        else say(now ? "পরে দেখার তালিকায় রাখা হয়েছে" : "পরে দেখার তালিকা থেকে বাদ");
    }

    private void toggleSub() {
        if (cur == null || cur.uploader.isEmpty()) return;
        boolean now = Store.toggleSub(this, cur.uploader, cur.uploaderUrl);
        paintSub();
        say(now ? "সাবস্ক্রাইব করা হয়েছে" : "সাবস্ক্রিপশন বাতিল");
    }

    private void openChannel() {
        if (cur == null) return;
        if (cur.uploaderUrl.isEmpty()) {
            say("চ্যানেলের লিংক এখনও আসেনি, একটু পরে চাপুন");
            return;
        }
        ChannelActivity.start(this, cur.uploaderUrl, cur.uploader);
    }

    private void share() {
        if (cur == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, cur.title + "\n" + cur.url);
        startActivity(Intent.createChooser(i, "শেয়ার"));
    }

    /** AI summary / translate / ask / scene description: native AI page */
    private void openWeb() {
        if (cur == null) return;
        savePos();
        int pos = 0;
        try { if (player != null) { pos = (int) (player.getCurrentPosition() / 1000); player.pause(); } } catch (Throwable ignored) {}
        AiActivity.start(this, cur.url, cur.title, cur.uploader, cur.duration > 0 ? cur.duration : 0, pos);
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        panel.setVisibility(on ? View.GONE : View.VISIBLE);
        fsBar.setVisibility(on ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams lp = videoBox.getLayoutParams();
        lp.height = on ? ViewGroup.LayoutParams.MATCH_PARENT : dp(230);
        videoBox.setLayoutParams(lp);
        setRequestedOrientation(on ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        hideSystemUi(on);
        if (on) say("পূর্ণ পর্দা। ছবিতে চাপলে চালান বা থামান");
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi(boolean hide) {
        View d = getWindow().getDecorView();
        if (hide) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // --------------------------------------------------------------- download

    private void download() {
        if (streams == null || cur == null) {
            say("লিংক এখনও আসেনি, একটু পরে চাপুন");
            return;
        }
        JSONObject a = pickAudio(streams, true);
        if (a == null) {
            say("এই ভিডিওর M4A অডিও পাওয়া যায়নি");
            return;
        }
        startDownload(a.optString("url", ""), cur.title, Store.settingBool(this, "wifiOnly", false));
    }

    private void downloadMp3() {
        if (streams == null || cur == null) {
            say("লিংক এখনও আসেনি, একটু পরে চাপুন");
            return;
        }
        JSONObject a = pickAudio(streams, false);
        if (a == null) {
            say("এই ভিডিওর অডিও পাওয়া যায়নি");
            return;
        }
        String url = a.optString("url", "");
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDl = new String[]{url, cur.title, "mp3"};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        startMp3(url, cur.title);
    }

    private void startMp3(String url, String title) {
        try {
            askNotify();
            Intent si = new Intent(this, Mp3Service.class)
                    .putExtra(Mp3Service.EXTRA_URL, url)
                    .putExtra(Mp3Service.EXTRA_TITLE, title);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si);
            say("MP3 তৈরি শুরু হয়েছে। নোটিফিকেশনে শতাংশ দেখা যাবে। শেষ হলে জানানো হবে। ফোল্ডার: Music/AISmartTube");
        } catch (Throwable t) {
            t.printStackTrace();
            say("MP3 শুরু করা যায়নি: " + t.getMessage());
        }
    }

    private static String safeName(String title) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (t.isEmpty()) t = "audio";
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t;
    }

    @SuppressWarnings("deprecation")
    private void startDownload(String url, String title, boolean wifiOnly) {
        if (url == null || !url.startsWith("https://")) {
            say("ডাউনলোডের লিংক পাওয়া যায়নি");
            return;
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDl = new String[]{url, title, wifiOnly ? "1" : "0"};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        try {
            String base = safeName(title);
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "AISmartTube");
            String name = base + ".m4a";
            int n = 1;
            while (new File(dir, name).exists() && n < 100) name = base + " (" + (n++) + ").m4a";
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(base);
            r.setDescription("AI Smart Tube — M4A");
            r.setMimeType("audio/mp4");
            r.addRequestHeader("User-Agent", UA);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "AISmartTube/" + name);
            if (wifiOnly) r.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                say("ডাউনলোড ম্যানেজার পাওয়া যায়নি");
                return;
            }
            long id = dm.enqueue(r);
            DownloadLog.add(this, base, "m4a", "", id);
            askNotify();
            try {
                Intent si = new Intent(this, DownloadService.class)
                        .putExtra(DownloadService.EXTRA_ID, id)
                        .putExtra(DownloadService.EXTRA_TITLE, base);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            say(wifiOnly ? "ডাউনলোড শুরু হবে (শুধু ওয়াইফাইয়ে)। নোটিফিকেশনে শতাংশ দেখা যাবে"
                    : "ডাউনলোড শুরু হয়েছে। নোটিফিকেশনে শতাংশ দেখা যাবে। ফোল্ডার: Music/AISmartTube");
        } catch (Throwable t) {
            t.printStackTrace();
            say("ডাউনলোড শুরু করা যায়নি: " + t.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_STORAGE) {
            String[] p = pendingDl;
            pendingDl = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && p != null) {
                if ("mp3".equals(p[2])) startMp3(p[0], p[1]);
                else startDownload(p[0], p[1], "1".equals(p[2]));
            } else {
                say("অনুমতি ছাড়া ডাউনলোড করা যাবে না");
            }
        }
    }

    private void askNotify() {
        if (notifAsked || Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifAsked = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    // ------------------------------------------------------ position / ticker

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try { onTick(); } catch (Throwable ignored) {}
            ui.postDelayed(this, 1000);
        }
    };

    private void onTick() {
        if (player == null) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        boolean known = dur != C.TIME_UNSET && dur > 0;
        if (known) {
            int maxS = (int) (dur / 1000);
            if (seek.getMax() != maxS) seek.setMax(maxS);
        }
        boolean userBusy = seekTouching || (seekA11yFocus && player.isPlaying());
        if (!userBusy) {
            seek.setProgress((int) (pos / 1000));
            paintTimeDesc(pos, known ? dur : C.TIME_UNSET);
        }
        boolean live = false;
        try { live = player.isCurrentMediaItemLive(); } catch (Throwable ignored) {}
        if (live) {
            long off = player.getCurrentLiveOffset();
            String behind = off != C.TIME_UNSET && off > 20000 ? clock(off / 1000) + " পিছনে" : "সরাসরি";
            timeText.setText("লাইভ · " + behind);
            if (!userBusy) ViewCompat.setStateDescription(seek, "লাইভ, " + (off != C.TIME_UNSET && off > 20000 ? spoken(off / 1000) + " পিছনে" : "একদম সরাসরি"));
        } else {
            timeText.setText(clock(pos / 1000) + (known ? " / " + clock(dur / 1000) : ""));
        }
        long now = System.currentTimeMillis();
        if (player.isPlaying() && cur != null && now - lastSave > 5000 && known) {
            lastSave = now;
            Store.putPos(this, cur.url, pos / 1000);
        }
    }

    private void paintTimeDesc(long pos, long dur) {
        String d = "এখন " + spoken(pos / 1000);
        if (dur != C.TIME_UNSET && dur > 0) d += ", মোট " + spoken(dur / 1000);
        ViewCompat.setStateDescription(seek, d);
    }

    private void savePos() {
        try {
            if (player == null || cur == null) return;
            long dur = player.getDuration();
            long pos = player.getCurrentPosition() / 1000;
            if (dur != C.TIME_UNSET && dur > 0 && player.getMediaItemCount() > 0
                    && player.getPlaybackState() != Player.STATE_ENDED) {
                Store.putPos(this, cur.url, pos);
            }
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------- media session / service

    private void setupSession() {
        try {
            session = new MediaSession(this, "AISmartTubePlayer");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { owner = true; exec("play"); }
                @Override public void onPause() { exec("pause"); }
                @Override public void onStop() { exec("pause"); }
                @Override public void onSkipToNext() { exec("next"); }
                @Override public void onSkipToPrevious() { exec("prev"); }
                @Override public void onFastForward() { exec("ff"); }
                @Override public void onRewind() { exec("rew"); }
            });
            applyPlaybackState(false);
            session.setActive(true);
            PlaybackService.sessionToken = session.getSessionToken();
        } catch (Throwable t) {
            t.printStackTrace();
            session = null;
        }
    }

    private void applyMeta() {
        if (session == null || cur == null) return;
        try {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, cur.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, cur.uploader.isEmpty() ? "AI Smart Tube" : cur.uploader)
                    .build());
        } catch (Throwable ignored) {}
    }

    private void applyPlaybackState(boolean playing) {
        if (session == null) return;
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                    .build());
            if (!session.isActive()) session.setActive(true);
            if (playing) PlaybackService.sessionToken = session.getSessionToken();
        } catch (Throwable ignored) {}
    }

    private void updateService(boolean playing) {
        if (replaced) return;
        if (!owner && RadioHub.active()) return;   // the radio owns the notification now
        try {
            if (!Store.settingBool(this, "bgPlay", true)) {
                stopService(new Intent(this, PlaybackService.class));
                return;
            }
            Intent i = new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_UPDATE)
                    .putExtra(PlaybackService.EXTRA_TITLE, cur == null ? "" : cur.title)
                    .putExtra(PlaybackService.EXTRA_PLAYING, playing);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // ----------------------------------------------------------- messages / fmt

    /** answer to something the user just did: always shown (TalkBack reads it) */
    private void say(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    /** automatic notices: skipped while music plays and "quiet play" is on */
    private void note(String m) {
        boolean quiet = Store.settingBool(this, "quietPlay", true);
        if (quiet && player != null && player.isPlaying()) return;
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String spoken(long sec) {
        String s = VideoItem.formatDurationBn(sec);
        return s.isEmpty() ? "০ সেকেন্ড" : s;
    }

    private static String clock(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        String t = h > 0 ? h + ":" + two(m) + ":" + two(s) : m + ":" + two(s);
        return toBn(t);
    }

    private static String two(long n) {
        return n < 10 ? "0" + n : String.valueOf(n);
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
