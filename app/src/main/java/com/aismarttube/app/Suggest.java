package com.aismarttube.app;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** YouTube search suggestions while typing (blocking — call from a background thread). */
final class Suggest {
    private Suggest() { }

    static List<String> fetch(String q) {
        List<String> out = new ArrayList<>();
        HttpURLConnection c = null;
        try {
            String u = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=bn&q="
                    + URLEncoder.encode(q, "UTF-8");
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            if (c.getResponseCode() / 100 != 2) return out;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            JSONArray root = new JSONArray(b.toString("UTF-8"));
            JSONArray arr = root.optJSONArray(1);
            if (arr != null) {
                for (int i = 0; i < arr.length() && out.size() < 10; i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }
}
