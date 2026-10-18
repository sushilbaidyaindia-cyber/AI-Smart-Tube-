package com.aismarttube.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView shell — UI in assets/index.html.
 * Heavy work (search, streams, AI) runs on a background pool; results come back through
 * window.__nativeResult(id, json) so the screen never freezes.
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
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
        if (android.os.Build.VERSION.SDK_INT >= 21) {
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

        webView.loadUrl("file:///android_asset/index.html");
    }

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

    public class WebAppInterface {

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this,
                            message != null ? message : "", Toast.LENGTH_SHORT).show());
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

        /** provider = "gemini" | "openai" (OpenAI-compatible: Grok, OpenRouter, Groq ...) */
        @JavascriptInterface
        public void aiAsync(String id, String provider, String baseUrl, String model,
                            String key, String system, String user) {
            pool.execute(() -> deliver(id,
                    AiClient.call(provider, baseUrl, model, key, system, user)));
        }

        /** Which source to try first: newpipe | piped | invidious */
        @JavascriptInterface
        public void setPrimary(String source) {
            NewPipeHelper.setPrimary(source);
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
