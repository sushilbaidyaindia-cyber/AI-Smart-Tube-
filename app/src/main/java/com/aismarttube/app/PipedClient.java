package com.aismarttube.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fallback source: public Piped API servers.
 * Tries several instances one after another; returns the same JSON shape as NewPipeHelper.
 */
public class PipedClient {

    private static final String REGISTRY = "https://piped-instances.kavin.rocks/";
    private static final String[] DEFAULTS = {
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee",
            "https://pipedapi.leptons.xyz"
    };
    private static final int MAX_TRIES = 5;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private static List<String> cache = null;
    private static long cacheAt = 0;

    private static synchronized List<String> instances() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < 30L * 60L * 1000L) {
            return new ArrayList<>(cache);
        }
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(httpGet(REGISTRY, 8000));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String api = o.optString("api_url", "");
                while (api.endsWith("/")) api = api.substring(0, api.length() - 1);
                if (api.startsWith("https://") && !list.contains(api)) list.add(api);
            }
        } catch (Throwable ignored) {
            // registry down → use built-in list below
        }
        Collections.shuffle(list); // spread load, avoid always hitting a dead first one
        for (String d : DEFAULTS) {
            if (!list.contains(d)) list.add(d);
        }
        cache = list;
        cacheAt = now;
        return new ArrayList<>(list);
    }

    private static String httpGet(String u, int timeoutMs) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            in.close();
            return bos.toString("UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String extractId(String url) {
        if (url == null) return "";
        int i = url.indexOf("v=");
        if (i >= 0) {
            String id = url.substring(i + 2);
            int amp = id.indexOf('&');
            return amp >= 0 ? id.substring(0, amp) : id;
        }
        i = url.indexOf("youtu.be/");
        if (i >= 0) {
            String id = url.substring(i + 9);
            int q = id.indexOf('?');
            return q >= 0 ? id.substring(0, q) : id;
        }
        return "";
    }

    /** Search videos via Piped. Throws if every instance fails. */
    public static JSONObject search(String q) throws Exception {
        Exception last = null;
        int tried = 0;
        for (String base : instances()) {
            if (tried++ >= MAX_TRIES) break;
            try {
                String body = httpGet(base + "/search?q=" + URLEncoder.encode(q, "UTF-8")
                        + "&filter=videos", 12000);
                JSONObject root = new JSONObject(body);
                JSONArray in = root.optJSONArray("items");
                if (in == null) throw new IOException("items নেই");
                JSONArray out = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    JSONObject it = in.optJSONObject(i);
                    if (it == null) continue;
                    String id = extractId(it.optString("url", ""));
                    if (id.isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("title", it.optString("title", ""));
                    o.put("url", "https://www.youtube.com/watch?v=" + id);
                    o.put("uploader", it.optString("uploaderName", ""));
                    o.put("duration", Math.max(0L, it.optLong("duration", 0L)));
                    o.put("views", it.optLong("views", 0L));
                    o.put("thumb", it.optString("thumbnail", ""));
                    out.put(o);
                    if (out.length() >= 30) break;
                }
                if (out.length() == 0) throw new IOException("খালি ফল");
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("query", q);
                res.put("items", out);
                res.put("source", "piped");
                return res;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Piped সার্ভার পাওয়া যায়নি");
    }

    /** Stream info via Piped. Throws if every instance fails. */
    public static JSONObject getStreams(String videoUrl) throws Exception {
        String id = extractId(videoUrl);
        if (id.isEmpty()) throw new IOException("ভিডিও আইডি পাওয়া যায়নি");
        Exception last = null;
        int tried = 0;
        for (String base : instances()) {
            if (tried++ >= MAX_TRIES) break;
            try {
                JSONObject s = new JSONObject(httpGet(base + "/streams/" + id, 15000));
                if (s.has("error") && !s.has("videoStreams")) {
                    throw new IOException(s.optString("message", s.optString("error")));
                }

                JSONObject root = new JSONObject();
                root.put("ok", true);
                root.put("source", "piped");
                root.put("title", s.optString("title", ""));
                root.put("uploader", s.optString("uploader", ""));
                root.put("url", "https://www.youtube.com/watch?v=" + id);
                root.put("views", s.optLong("views", 0L));
                root.put("duration", Math.max(0L, s.optLong("duration", 0L)));
                root.put("description", s.optString("description", ""));

                String playUrl = "";
                String mp4Url = "";
                JSONArray videos = new JSONArray();
                JSONArray vs = s.optJSONArray("videoStreams");
                if (vs != null) {
                    for (int i = 0; i < vs.length(); i++) {
                        JSONObject v = vs.optJSONObject(i);
                        if (v == null) continue;
                        String u = v.optString("url", "");
                        boolean vOnly = v.optBoolean("videoOnly", false);
                        JSONObject o = new JSONObject();
                        o.put("url", u);
                        o.put("quality", v.optString("quality", ""));
                        o.put("format", v.optString("format", ""));
                        o.put("hasVideo", true);
                        o.put("hasAudio", !vOnly);
                        videos.put(o);
                        if (!vOnly && !u.isEmpty()) {
                            if (playUrl.isEmpty()) playUrl = u;
                            if (mp4Url.isEmpty() && v.optString("mimeType", "").contains("mp4")) mp4Url = u;
                        }
                    }
                }
                if (!mp4Url.isEmpty()) playUrl = mp4Url;
                root.put("videoStreams", videos);

                JSONArray audios = new JSONArray();
                String firstAudio = "";
                JSONArray as = s.optJSONArray("audioStreams");
                if (as != null) {
                    for (int i = 0; i < as.length(); i++) {
                        JSONObject a = as.optJSONObject(i);
                        if (a == null) continue;
                        String u = a.optString("url", "");
                        JSONObject o = new JSONObject();
                        o.put("url", u);
                        o.put("bitrate", a.optInt("bitrate", 0));
                        o.put("format", a.optString("format", ""));
                        audios.put(o);
                        if (firstAudio.isEmpty() && !u.isEmpty()) firstAudio = u;
                    }
                }
                root.put("audioStreams", audios);

                boolean audioOnly = false;
                if (playUrl.isEmpty()) {
                    String hls = s.optString("hls", "");
                    if (!hls.isEmpty() && !"null".equals(hls)) playUrl = hls;
                }
                if (playUrl.isEmpty() && !firstAudio.isEmpty()) {
                    playUrl = firstAudio;
                    audioOnly = true;
                }
                if (playUrl.isEmpty()) throw new IOException("প্লে URL নেই");
                root.put("playUrl", playUrl);
                root.put("audioOnly", audioOnly);
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Piped সার্ভার পাওয়া যায়নি");
    }
}
