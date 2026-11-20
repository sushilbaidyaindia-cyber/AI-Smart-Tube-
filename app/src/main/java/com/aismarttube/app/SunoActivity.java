package com.aismarttube.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Suno song link shared to the app (or opened from the browser): finds the song, downloads its MP3
 * into Music/AISmartTube and shows it in the download list. For personal listening.
 */
public class SunoActivity extends AppCompatActivity {

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    private static final String UUID_RE = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final int REQ_STORAGE = 71;

    private TextView status;
    private Button btnList;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingLink = null;
    private volatile boolean working = false;
    private String pageInfo = "";
    private String bodyNote = "";

    /** true if the text holds a Suno link */
    static boolean isSuno(String raw) {
        return raw != null && findLink(raw) != null;
    }

    static String findLink(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("https?://(?:[\\w-]+\\.)?suno\\.(?:com|ai)/[^\\s]+").matcher(raw);
        if (!m.find()) return null;
        String s = m.group();
        while (s.length() > 0 && ".,;:)>]\"'".indexOf(s.charAt(s.length() - 1)) >= 0) s = s.substring(0, s.length() - 1);
        return s;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Suno গান ডাউনলোড");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p * 2, p, p);

        TextView head = new TextView(this);
        head.setText("Suno গান ডাউনলোড");
        head.setTextSize(22);
        head.setPadding(0, 0, 0, p);
        ViewCompat.setAccessibilityHeading(head, true);
        root.addView(head);

        status = new TextView(this);
        status.setTextSize(17);
        status.setText("লিংক দেখা হচ্ছে…");
        ViewCompat.setAccessibilityLiveRegion(status, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);

        btnList = new Button(this);
        btnList.setText("ডাউনলোডের তালিকা খুলুন");
        btnList.setVisibility(View.GONE);
        btnList.setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));
        root.addView(btnList);

        Button close = new Button(this);
        close.setText("বন্ধ করুন");
        close.setOnClickListener(v -> finish());
        root.addView(close);
        setContentView(root);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handle(in);
    }

    private void say(String t) {
        ui.post(() -> {
            status.setText(t);
            status.announceForAccessibility(t);
        });
    }

    private void handle(Intent in) {
        if (in == null || working) return;
        String raw = in.getData() != null ? in.getData().toString() : in.getStringExtra(Intent.EXTRA_TEXT);
        String link = findLink(raw);
        if (link == null) {
            say("Suno-র লিংক পাওয়া যায়নি। Suno-তে গানের পাতায় 'শেয়ার' চেপে এই অ্যাপ বেছে নিন।");
            return;
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingLink = link;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 72); } catch (Throwable ignored) { }
        }
        start(link);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_STORAGE) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && pendingLink != null) start(pendingLink);
            else say("ফোনে ফাইল রাখার অনুমতি না দিলে ডাউনলোড করা যাবে না।");
            pendingLink = null;
        }
    }

    private void start(String link) {
        working = true;
        say("গান খোঁজা হচ্ছে…");
        io.execute(() -> {
            try {
                download(link);
            } catch (Throwable t) {
                say("ডাউনলোড হয়নি: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            } finally {
                working = false;
            }
        });
    }

    // ------------------------------------------------------------------ work

    private static String get(String url, String[] finalUrl, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en");
        int code = c.getResponseCode();
        if (finalUrl != null) finalUrl[0] = c.getURL().toString();
        if (code >= 400) throw new Exception("Suno পাতা খোলেনি (কোড " + code + ")");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < maxBytes) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static String meta(String html, String prop) {
        Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(prop) + "[\"'][^>]*content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + Pattern.quote(prop) + "[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    private static String safeName(String title) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (t.isEmpty()) t = "Suno";
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t;
    }

    private void download(String link) throws Exception {
        Matcher m = Pattern.compile("/song/(" + UUID_RE + ")").matcher(link);
        String id = m.find() ? m.group(1) : null;
        String html = "";
        String[] fin = new String[]{link};
        try {
            html = get(link, fin, 700000);
        } catch (Exception e) {
            if (id == null) throw e;       // the id is known from the link itself: the page is only needed for the title
        }
        if (id == null) {
            m = Pattern.compile("/song/(" + UUID_RE + ")").matcher(fin[0]);
            if (m.find()) id = m.group(1);
        }
        if (id == null) {
            m = Pattern.compile("cdn\\d*\\.suno\\.ai/(" + UUID_RE + ")", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) id = m.group(1);
        }
        if (id == null) {
            m = Pattern.compile("/song/(" + UUID_RE + ")").matcher(html);
            if (m.find()) id = m.group(1);
        }
        if (id == null) throw new Exception("লিংক থেকে গানের পরিচয় পাওয়া গেল না");

        String title = unescape(meta(html, "og:title"));
        if (title.isEmpty()) {
            Matcher t = Pattern.compile("<title>([^<]*)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (t.find()) title = unescape(t.group(1));
        }
        title = title.replaceAll("\\s*[|\\-–]\\s*Suno.*$", "").trim();
        if (title.isEmpty() || title.equalsIgnoreCase("Suno")) title = "Suno " + id.substring(0, 8);

        java.util.List<String> cand = new java.util.ArrayList<>();
        String og = unescape(meta(html, "og:audio"));
        if (og.startsWith("http")) cand.add(og);
        // real audio links written inside the page itself
        Matcher am = Pattern.compile("https:(?:\\\\?/){2}cdn\\d*\\.suno\\.ai(?:\\\\?/)[^\"'\\s<>\\\\]+?\\.(?:mp3|m4a)[^\"'\\s<>\\\\]*").matcher(html);
        while (am.find()) {
            String u = am.group().replace("\\/", "/").replace("\\u0026", "&");
            if (!cand.contains(u)) cand.add(u);
        }
        // Suno's own clip info (public songs)
        try {
            String js = get("https://studio-api.suno.ai/api/clip/" + id, null, 300000);
            Matcher jm = Pattern.compile("\"audio_url\"\\s*:\\s*\"([^\"]+)\"").matcher(js);
            while (jm.find()) {
                String u = jm.group(1).replace("\\/", "/").replace("\\u0026", "&");
                if (u.startsWith("http") && !cand.contains(u)) cand.add(0, u);
            }
        } catch (Throwable ignored) { }
        pageInfo = "পাতা " + html.length() + " অক্ষর, ঠিকানা " + cand.size() + "টি";
        cand.add("https://cdn1.suno.ai/" + id + ".mp3");
        cand.add("https://cdn2.suno.ai/" + id + ".mp3");
        fetchAndSave(cand, title);
    }

    /** Downloads inside the app (full control of the request headers) and saves into Music/AISmartTube. */
    private void fetchAndSave(java.util.List<String> cand, String title) throws Exception {
        final String base = safeName(title);
        HttpURLConnection c = null;
        String codes = "";
        String[] refs = {"https://suno.com/", "https://app.suno.ai/", null};
        outer:
        for (String u : cand) {
            for (String ref : refs) {
                HttpURLConnection t = null;
                try {
                    t = (HttpURLConnection) new URL(u).openConnection();
                    t.setConnectTimeout(15000);
                    t.setReadTimeout(30000);
                    t.setRequestProperty("User-Agent", UA);
                    t.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8");
                    if (ref != null) {
                        t.setRequestProperty("Referer", ref);
                        t.setRequestProperty("Origin", ref.substring(0, ref.length() - 1));
                    }
                    int code = t.getResponseCode();
                    codes += " " + code;
                    if (code == 200) { c = t; break outer; }
                    if (bodyNote.isEmpty()) {
                        try {
                            InputStream es = t.getErrorStream();
                            if (es != null) {
                                byte[] eb = new byte[200];
                                int en = es.read(eb);
                                if (en > 0) bodyNote = new String(eb, 0, en, "UTF-8").replaceAll("\\s+", " ");
                                String sv = t.getHeaderField("Server");
                                if (sv != null) bodyNote = "[" + sv + "] " + bodyNote;
                            }
                        } catch (Throwable ignored2) { }
                    }
                    t.disconnect();
                } catch (java.io.IOException e) {
                    codes += " ?";
                    if (t != null) t.disconnect();
                }
            }
        }
        if (c == null) throw new Exception("Suno-র সার্ভার ফাইল দিচ্ছে না (কোড:" + codes + ") " + pageInfo + " | " + bodyNote);

        final long total = c.getContentLengthLong();
        final Context ctx = getApplicationContext();
        final android.content.ContentResolver cr = ctx.getContentResolver();
        java.io.OutputStream os;
        Uri muri = null;
        File mfile = null;
        String where;
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, base + ".mp3");
            v.put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
            v.put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AISmartTube");
            v.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1);
            muri = cr.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (muri == null) throw new Exception("ফাইল তৈরি করা যায়নি");
            os = cr.openOutputStream(muri);
            if (os == null) throw new Exception("ফাইল খোলা যায়নি");
            where = muri.toString();
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "AISmartTube");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("ফোল্ডার তৈরি করা যায়নি");
            String nm = base + ".mp3";
            int k = 1;
            while (new File(dir, nm).exists() && k < 100) nm = base + " (" + (k++) + ").mp3";
            mfile = new File(dir, nm);
            os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(mfile), 64 * 1024);
            where = Uri.fromFile(mfile).toString();
        }
        boolean ok = false;
        try {
            InputStream in = c.getInputStream();
            byte[] buf = new byte[32 * 1024];
            long got = 0;
            int last = -1, n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : -1;
                if (pct >= 0 && pct / 10 != last) { last = pct / 10; say("নামছে: " + base + " — " + pct + "% (অ্যাপ খোলা রাখুন)"); }
            }
            in.close();
            os.flush();
            os.close();
            if (got < 1024) throw new Exception("ফাইল খুব ছোট (" + got + " বাইট)");
            ok = true;
        } finally {
            c.disconnect();
            if (!ok) {
                try { os.close(); } catch (Throwable ignored) { }
                if (muri != null) { try { cr.delete(muri, null, null); } catch (Throwable ignored) { } }
                if (mfile != null) mfile.delete();
            }
        }
        if (muri != null) {
            android.content.ContentValues u = new android.content.ContentValues();
            u.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(muri, u, null, null);
        } else if (mfile != null) {
            android.media.MediaScannerConnection.scanFile(ctx, new String[]{mfile.getAbsolutePath()}, new String[]{"audio/mpeg"}, null);
        }
        DownloadLog.add(ctx, base, "mp3", where, 0);
        ui.post(() -> btnList.setVisibility(View.VISIBLE));
        say("ডাউনলোড হয়েছে: " + base + "\nফোল্ডার: Music/AISmartTube। ডাউনলোডের তালিকায় চালানো ও মোছা যাবে।");
    }

    @SuppressWarnings("deprecation")
    private void enqueue(String url, String title) {
        String base = safeName(title);
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "AISmartTube");
        String name = base + ".mp3";
        int n = 1;
        while (new File(dir, name).exists() && n < 100) name = base + " (" + (n++) + ").mp3";
        final String fname = name;
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        r.setTitle(base);
        r.setDescription("AI Smart Tube — Suno MP3");
        r.setMimeType("audio/mpeg");
        r.addRequestHeader("User-Agent", UA);
        r.addRequestHeader("Referer", "https://suno.com/");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "AISmartTube/" + fname);
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) throw new IllegalStateException("ডাউনলোড ম্যানেজার পাওয়া যায়নি");
        long id = dm.enqueue(r);
        DownloadLog.add(this, base, "mp3", "", id);
        try {
            Intent si = new Intent(this, DownloadService.class)
                    .putExtra(DownloadService.EXTRA_ID, id)
                    .putExtra(DownloadService.EXTRA_TITLE, base);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si);
        } catch (Throwable ignored) { }
        ui.post(() -> btnList.setVisibility(View.VISIBLE));
        say("ডাউনলোড শুরু হয়েছে: " + base + "\nফোল্ডার: Music/AISmartTube। শেষ হলে ডাউনলোডের তালিকায় দেখা যাবে।");
    }
}
