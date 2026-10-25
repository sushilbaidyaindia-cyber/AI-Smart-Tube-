package com.aismarttube.app;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Translate any text into Bangla (blocking — call from a background thread). Returns {translation, detectedLanguage}. */
final class Translator {
    private Translator() { }

    static String[] toBangla(String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return new String[]{"", ""};
        if (t.length() > 1500) t = t.substring(0, 1500);
        HttpURLConnection c = null;
        try {
            String u = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=bn&dt=t&q="
                    + URLEncoder.encode(t, "UTF-8");
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("অনুবাদ সার্ভার সাড়া দেয়নি (" + code + ")");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            JSONArray root = new JSONArray(b.toString("UTF-8"));
            JSONArray segs = root.optJSONArray(0);
            StringBuilder out = new StringBuilder();
            if (segs != null) {
                for (int i = 0; i < segs.length(); i++) {
                    JSONArray seg = segs.optJSONArray(i);
                    if (seg != null) out.append(seg.optString(0, ""));
                }
            }
            String lang = root.length() > 2 ? root.optString(2, "") : "";
            return new String[]{out.toString().trim(), lang};
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
