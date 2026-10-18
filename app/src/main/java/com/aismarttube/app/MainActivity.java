package com.aismarttube.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
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
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView shell — UI in assets/index.html.
 * Heavy work (search, streams, AI, subtitles) runs on a background pool; results come back
 * through window.__nativeResult(id, json) so the screen never freezes.
 *
 * Also provides:
 *  - voice search (system speech dialog)           -> window.__voiceResult(text)
 *  - media session: TalkBack two-finger double-tap, headset button, lock-screen controls
 *                                                    -> window.__remote(cmd)
 *  - one-tap M4A download via the system DownloadManager (Music/AISmartTube)
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_VOICE = 4401;
    private static final int REQ_STORAGE = 4402;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private WebView webView;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private MediaSession mediaSession;
    private String[] pendingDownload; // url, title, wifiOnly("1"/"0")
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pool.execute(() -> {
            try {
                NewPipeHelper.ensureInit();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });

        root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= 21) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new WebAppInterface(), "Android");
        webView.setWebViewClient(new WebViewClient());

        // full-screen video support (the ⛶ button on the video)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                webView.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                hideSystemUi(true);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        setupMediaSession();
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ------------------------------------------------------------ media session

    /**
     * TalkBack's two-finger double-tap (and headset / Bluetooth buttons) send a "play/pause" media key.
     * It only reaches the app if the app owns an active media session — so we create one and
     * forward its commands to the web page.
     */
    private void setupMediaSession() {
        try {
            mediaSession = new MediaSession(this, "AISmartTube");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { remote("play"); }
                @Override public void onPause() { remote("pause"); }
                @Override public void onStop() { remote("pause"); }
                @Override public void onSkipToNext() { remote("next"); }
                @Override public void onSkipToPrevious() { remote("prev"); }
                @Override public void onFastForward() { remote("ff"); }
                @Override public void onRewind() { remote("rew"); }
            });
            applyPlaybackState(false, "AI Smart Tube");
            mediaSession.setActive(true);
        } catch (Throwable t) {
            t.printStackTrace();
            mediaSession = null;
        }
    }

    private void applyPlaybackState(boolean playing, String title) {
        if (mediaSession == null) return;
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
            PlaybackState st = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                    .build();
            mediaSession.setPlaybackState(st);
            if (title != null) {
                mediaSession.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "AI Smart Tube")
                        .build());
            }
            if (!mediaSession.isActive()) mediaSession.setActive(true);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void remote(String cmd) {
        final String js = "window.__remote && window.__remote(" + JSONObject.quote(cmd) + ")";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    // ------------------------------------------------------------------ helpers

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        if (customCallback != null) {
            customCallback.onCustomViewHidden();
            customCallback = null;
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        hideSystemUi(false);
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

    private void deliver(String id, String json) {
        final String js = "window.__nativeResult && window.__nativeResult("
                + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void toast(String m) {
        Toast.makeText(MainActivity.this, m != null ? m : "", Toast.LENGTH_SHORT).show();
    }

    private void sendVoice(String text) {
        final String js = "window.__voiceResult && window.__voiceResult(" + JSONObject.quote(text == null ? "" : text) + ")";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    // ----------------------------------------------------------------- download

    private static String safeName(String title) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (t.isEmpty()) t = "audio";
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t;
    }

    @SuppressWarnings("deprecation")
    private void startAudioDownload(String url, String title, boolean wifiOnly) {
        if (url == null || !url.startsWith("https://")) {
            toast("ডাউনলোডের লিংক পাওয়া যায়নি");
            return;
        }
        // Android 9 and older need the storage permission for the public Music folder
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[]{url, title, wifiOnly ? "1" : "0"};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        try {
            String base = safeName(title);
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC), "AISmartTube");
            String name = base + ".m4a";
            int n = 1;
            while (new File(dir, name).exists() && n < 100) {
                name = base + " (" + (n++) + ").m4a";
            }
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(base);
            r.setDescription("AI Smart Tube — M4A");
            r.setMimeType("audio/mp4");
            r.addRequestHeader("User-Agent", UA);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "AISmartTube/" + name);
            if (wifiOnly) r.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                toast("ডাউনলোড ম্যানেজার পাওয়া যায়নি");
                return;
            }
            dm.enqueue(r);
            toast(wifiOnly
                    ? "ডাউনলোড শুরু হবে (শুধু ওয়াইফাইয়ে)। ফোল্ডার: Music/AISmartTube"
                    : "ডাউনলোড শুরু হয়েছে। ফোল্ডার: Music/AISmartTube");
        } catch (Throwable t) {
            t.printStackTrace();
            toast("ডাউনলোড শুরু করা যায়নি: " + t.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            String[] p = pendingDownload;
            pendingDownload = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && p != null) {
                startAudioDownload(p[0], p[1], "1".equals(p[2]));
            } else {
                toast("অনুমতি ছাড়া ডাউনলোড করা যাবে না");
            }
        }
    }

    // -------------------------------------------------------------------- voice

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE) {
            String text = "";
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (list != null && !list.isEmpty()) text = list.get(0);
            }
            sendVoice(text);
        }
    }

    // ------------------------------------------------------------ JS interface

    public class WebAppInterface {

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> toast(message));
        }

        // ---- async calls: result arrives via window.__nativeResult(id, json) ----

        @JavascriptInterface
        public void searchAsync(String id, String query) {
            pool.execute(() -> deliver(id, NewPipeHelper.search(query)));
        }

        @JavascriptInterface
        public void getStreamsAsync(String id, String videoUrl) {
            pool.execute(() -> deliver(id, NewPipeHelper.getStreams(videoUrl)));
        }

        /** source = "newpipe" | "piped" | "invidious" */
        @JavascriptInterface
        public void getStreamsSrcAsync(String id, String videoUrl, String source) {
            pool.execute(() -> deliver(id, NewPipeHelper.getStreams(videoUrl, source)));
        }

        @JavascriptInterface
        public void getTranscriptAsync(String id, String videoUrl) {
            pool.execute(() -> deliver(id, NewPipeHelper.getTranscript(videoUrl)));
        }

        /**
         * provider = "gemini" | "openai" (OpenAI-compatible: Grok, OpenRouter, Groq ...)
         * videoUrl = public YouTube link to let Gemini watch the video, or "" for text only.
         */
        @JavascriptInterface
        public void aiAsync(String id, String provider, String baseUrl, String model,
                            String key, String system, String user, String videoUrl) {
            pool.execute(() -> deliver(id,
                    AiClient.call(provider, baseUrl, model, key, system, user, videoUrl)));
        }

        /** Which source to try first: newpipe | piped | invidious */
        @JavascriptInterface
        public void setPrimary(String source) {
            NewPipeHelper.setPrimary(source);
        }

        /** false = use only the primary source, no automatic fallback */
        @JavascriptInterface
        public void setFallback(boolean on) {
            NewPipeHelper.setFallback(on);
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text != null ? text : "");
                startActivity(Intent.createChooser(i, "শেয়ার"));
            });
        }

        /** One-tap M4A download of an audio stream URL. */
        @JavascriptInterface
        public void downloadAudio(String url, String title, boolean wifiOnly) {
            runOnUiThread(() -> startAudioDownload(url, title, wifiOnly));
        }

        /** Opens the system voice-input dialog; the text comes back via window.__voiceResult(text). */
        @JavascriptInterface
        public void startVoice(String lang) {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                        (lang == null || lang.isEmpty()) ? "bn-IN" : lang);
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "কী খুঁজবেন, বলুন");
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                try {
                    startActivityForResult(i, REQ_VOICE);
                } catch (ActivityNotFoundException e) {
                    toast("এই ফোনে ভয়েস ইনপুট নেই — Google অ্যাপ বা Google ভয়েস টাইপিং চালু করুন");
                    sendVoice("");
                }
            });
        }

        /** The page tells us whether the video is playing, so media buttons and TalkBack toggle correctly. */
        @JavascriptInterface
        public void setPlayback(boolean playing, String title) {
            runOnUiThread(() -> applyPlaybackState(playing, title));
        }

        // ---- old sync methods kept so nothing else breaks ----

        @JavascriptInterface
        public String search(String query) {
            return NewPipeHelper.search(query);
        }

        @JavascriptInterface
        public String getStreams(String videoUrl) {
            return NewPipeHelper.getStreams(videoUrl);
        }

        @JavascriptInterface
        public String getVideoInfoFromNewPipe(String videoUrl) {
            return NewPipeHelper.getVideoInfo(videoUrl);
        }
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Throwable ignored) {}
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
            return;
        }
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){try{if(window.__appBack&&window.__appBack())return true;}catch(e){}return false;})()",
                    value -> {
                        if ("true".equals(value)) return;
                        MainActivity.super.onBackPressed();
                    });
            return;
        }
        super.onBackPressed();
    }
}
