package com.aismarttube.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The "more" (⋮) menu of a video row: channel, watch later, share, M4A / MP3 / MP4 download — no need to open the video. */
final class VideoMenu {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private VideoMenu() { }

    static void show(final Context c, final VideoItem it) {
        final List<String> names = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        final boolean hasCh = it.uploaderUrl != null && it.uploaderUrl.startsWith("http");
        if (hasCh) {
            names.add("চ্যানেলে যান: " + it.uploader);
            acts.add(() -> ChannelActivity.start(c, it.uploaderUrl, it.uploader));
            boolean subbed = Store.isSubbed(c, it.uploader);
            names.add(subbed ? "সাবস্ক্রিপশন বাতিল" : "সাবস্ক্রাইব করুন");
            acts.add(() -> {
                boolean now = Store.toggleSub(c, it.uploader, it.uploaderUrl);
                toast(c, now ? it.uploader + " সাবস্ক্রাইব হয়েছে" : "সাবস্ক্রিপশন বাতিল হয়েছে");
            });
        }
        boolean later = Store.listHas(c, "later", it.url);
        names.add(later ? "পরে দেখব থেকে সরান" : "পরে দেখব তালিকায় রাখুন");
        acts.add(() -> {
            boolean now = Store.listToggle(c, "later", it);
            toast(c, now ? "পরে দেখব তালিকায় রাখা হয়েছে" : "তালিকা থেকে সরানো হয়েছে");
        });
        boolean liked = Store.listHas(c, "liked", it.url);
        names.add(liked ? "পছন্দ বাতিল" : "পছন্দ করুন (লাইক)");
        acts.add(() -> {
            boolean now = Store.listToggle(c, "liked", it);
            toast(c, now ? "পছন্দ করা হয়েছে" : "পছন্দ বাতিল হয়েছে");
        });
        if (!it.live) {
            names.add("অডিও ডাউনলোড (M4A)");
            acts.add(() -> download(c, it, "m4a"));
            names.add("MP3 ডাউনলোড");
            acts.add(() -> download(c, it, "mp3"));
            names.add("ভিডিও ডাউনলোড (MP4)");
            acts.add(() -> download(c, it, "mp4"));
        }
        names.add("লিংক শেয়ার করুন");
        acts.add(() -> {
            try {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, it.title + "\n" + it.url);
                c.startActivity(Intent.createChooser(s, "শেয়ার").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) { toast(c, "শেয়ার করা যায়নি"); }
        });
        names.add("লিংক কপি করুন");
        acts.add(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("video", it.url));
                toast(c, "লিংক কপি হয়েছে");
            } catch (Throwable t) { toast(c, "কপি করা যায়নি"); }
        });

        try {
            new AlertDialog.Builder(c)
                    .setTitle(it.title.isEmpty() ? "ভিডিও" : it.title)
                    .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                    .setNegativeButton("বন্ধ", null)
                    .show();
        } catch (Throwable t) { t.printStackTrace(); }
    }

    private static void toast(Context c, String m) {
        Toast.makeText(c.getApplicationContext(), m, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ downloads

    private static void download(final Context c, final VideoItem it, final String kind) {
        if (Build.VERSION.SDK_INT < 29 && !"mp3".equals(kind)
                && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            toast(c, "আগে স্টোরেজের অনুমতি লাগবে: যেকোনো ভিডিও খুলে একবার ডাউনলোড চাপুন, অনুমতি দিন, তারপর এখান থেকে করুন");
            return;
        }
        toast(c, "ডাউনলোডের লিংক আনা হচ্ছে…");
        IO.execute(() -> {
            String msg;
            try {
                JSONObject d = new JSONObject(NewPipeHelper.getStreams(it.url));
                if (!d.optBoolean("ok", true) && d.has("error")) {
                    msg = "লিংক আনা যায়নি: " + d.optString("error");
                } else if ("mp4".equals(kind)) {
                    JSONObject v = pickMuxed(d);
                    msg = v == null ? "এই ভিডিওর MP4 (ছবি+শব্দ একসাথে) পাওয়া যায়নি। অডিও ডাউনলোড করুন"
                            : enqueue(c, v.optString("url"), it.title, "mp4", v.optString("quality", ""));
                } else {
                    JSONObject a = pickAudio(d, "m4a".equals(kind));
                    if (a == null) msg = "এই ভিডিওর অডিও পাওয়া যায়নি";
                    else if ("mp3".equals(kind)) msg = startMp3(c, a.optString("url"), it.title);
                    else msg = enqueue(c, a.optString("url"), it.title, "m4a", "");
                }
            } catch (Throwable t) {
                msg = "ডাউনলোড শুরু করা যায়নি: " + t.getMessage();
            }
            final String m = msg;
            UI.post(() -> toast(c, m));
        });
    }

    private static JSONObject pickAudio(JSONObject d, boolean needM4a) {
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

    /** best MP4 that already has sound (up to 720p) */
    private static JSONObject pickMuxed(JSONObject d) {
        JSONArray a = d.optJSONArray("videoStreams");
        if (a == null) return null;
        JSONObject best = null;
        int bestH = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("url", "").isEmpty() || !o.optBoolean("hasAudio", false)) continue;
            String f = o.optString("format", "").toLowerCase();
            if (f.contains("webm")) continue;
            int h = 0;
            try { h = Integer.parseInt(o.optString("quality", "").replaceAll("[^0-9].*$", "")); } catch (Throwable ignored) { }
            if (h > 720) continue;
            if (h > bestH) { best = o; bestH = h; }
        }
        return best;
    }

    private static String startMp3(Context c, String url, String title) {
        try {
            Intent si = new Intent(c, Mp3Service.class)
                    .putExtra(Mp3Service.EXTRA_URL, url)
                    .putExtra(Mp3Service.EXTRA_TITLE, title);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(si); else c.startService(si);
            return "MP3 তৈরি শুরু হয়েছে। নোটিফিকেশনে শতাংশ দেখা যাবে। ফোল্ডার: Music/AISmartTube";
        } catch (Throwable t) {
            return "MP3 শুরু করা যায়নি: " + t.getMessage();
        }
    }

    @SuppressWarnings("deprecation")
    private static String enqueue(Context c, String url, String title, String kind, String quality) {
        if (url == null || !url.startsWith("https://")) return "ডাউনলোডের লিংক পাওয়া যায়নি";
        try {
            boolean video = "mp4".equals(kind);
            String base = safeName(title);
            String root = video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC;
            File dir = new File(Environment.getExternalStoragePublicDirectory(root), "AISmartTube");
            String ext = video ? ".mp4" : ".m4a";
            String name = base + ext;
            int n = 1;
            while (new File(dir, name).exists() && n < 100) name = base + " (" + (n++) + ")" + ext;
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(base);
            r.setDescription("AI Smart Tube — " + kind.toUpperCase());
            r.setMimeType(video ? "video/mp4" : "audio/mp4");
            r.addRequestHeader("User-Agent", UA);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
            r.setDestinationInExternalPublicDir(root, "AISmartTube/" + name);
            if (Store.settingBool(c, "wifiOnly", false)) r.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return "ডাউনলোড ম্যানেজার পাওয়া যায়নি";
            long id = dm.enqueue(r);
            DownloadLog.add(c, base, kind, "", id);
            try {
                Intent si = new Intent(c, DownloadService.class)
                        .putExtra(DownloadService.EXTRA_ID, id)
                        .putExtra(DownloadService.EXTRA_TITLE, base);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(si); else c.startService(si);
            } catch (Throwable ignored) { }
            return "ডাউনলোড শুরু হয়েছে। ফোল্ডার: " + (video ? "Movies" : "Music") + "/AISmartTube";
        } catch (Throwable t) {
            return "ডাউনলোড শুরু করা যায়নি: " + t.getMessage();
        }
    }

    private static String safeName(String title) {
        String s = title == null ? "video" : title.replaceAll("[\\\\/:*?\"<>|\\n\\r]", " ").trim();
        if (s.isEmpty()) s = "video";
        return s.length() > 80 ? s.substring(0, 80).trim() : s;
    }
}
