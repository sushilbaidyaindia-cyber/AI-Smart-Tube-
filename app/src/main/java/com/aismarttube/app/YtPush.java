package com.aismarttube.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends a like / subscribe done inside the app to the signed-in YouTube account (quietly, in the background). */
final class YtPush {
    private YtPush() { }

    static void like(Context c, String videoUrl, boolean on) {
        final Context app = c.getApplicationContext();
        if (!YtAuth.isSignedIn(app) || videoUrl == null) return;
        Matcher m = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})").matcher(videoUrl);
        if (!m.find()) return;
        final String id = m.group(1);
        run(app, "লাইক", () -> YtApi.rate(app, id, on ? "like" : "none"));
    }

    static void subscribe(Context c, String name, String url, boolean on) {
        final Context app = c.getApplicationContext();
        if (!YtAuth.isSignedIn(app)) return;
        String u = url == null ? "" : url;
        if (u.isEmpty()) {
            try {
                String s = Store.get(app, "suburl");
                if (!s.isEmpty()) u = new JSONObject(s).optString(name, "");
            } catch (Throwable ignored) { }
        }
        Matcher m = Pattern.compile("/channel/(UC[A-Za-z0-9_-]{22})").matcher(u);
        if (!m.find()) return;   // handle-style links cannot be matched to a channel id
        final String id = m.group(1);
        run(app, "সাবস্ক্রিপশন", () -> { if (on) YtApi.subscribe(app, id); else YtApi.unsubscribe(app, id); });
    }

    private interface Job { void go() throws Exception; }

    private static void run(final Context app, final String what, final Job j) {
        new Thread(() -> {
            try {
                j.go();
            } catch (final Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, "ইউটিউবে " + what + " পাঠানো যায়নি: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
