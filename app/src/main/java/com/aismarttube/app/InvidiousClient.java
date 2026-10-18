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
 * Third fallback: public Invidious API servers (many have the API switched off or are blocked,
 * so this is tried last). Returns the same JSON shape as NewPipeHelper.
 */
public class InvidiousClient {

    private static final String REGISTRY = "https://api.invidious.io/instances.json?sort_type=health";
    private static final String[] DEFAULTS = {
            "https://inv.nadeko.net",
            "https://yewtu.be"
    };
    private static final int MAX_TRIES = 4;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private static List<String> cache = null;
    private static long cacheAt = 0;

    private static synchronized List<String> instances() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < 30L * 60L * 1000L) return new ArrayList<>(cache);
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(httpGet(REGISTRY, 8000));
            for (int i = 0; i < arr.length(); i++) {
                JSONArray row = arr.optJSONArray(i);
                if (row == null || row.length() < 2) continue;
                JSONObject info = row.optJSONObject(1);
                if (info == null) continue;
                if (!"https".equals(info.optString("type"))) continue;
                if (!info.optBoolean("api", false)) continue; // only instances with the API on
                String uri = info.optString("uri", "");
                while (uri.endsWith("/")) uri = uri.substring(0, uri.length() - 1);
                if (uri.startsWith("https://") && !list.contains(uri)) list.add(uri);
                if (list.size() >= 12) break;
            }
        } catch (Throwable ignored) {
            // registry down → built-in list
        }
        Collections.shuffle(list);
        for (String d : DEFAULTS) if (!list.contains(d)) list.add(d);
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

    private static String abs(String base, String u) {
        if (u == null) return "";
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) return base + u;
        return u;
    }

    public static JSONObject search(String q) throws Exception {
        Exception last = null;
        int tried = 0;
        for (String base : instances()) {
            if (tried++ >= MAX_TRIES) break;
            try {
                String body = httpGet(base + "/api/v1/search?type=video&q="
                        + URLEncoder.encode(q, "UTF-8"), 12000);
                JSONArray in = new JSONArray(body);
                JSONArray out = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    JSONObject it = in.optJSONObject(i);
                    if (it == null) continue;
                    String id = it.optString("videoId", "");
                    if (id.isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("title", it.optString("title", ""));
                    o.put("url", "https://www.youtube.com/watch?v=" + id);
                    o.put("uploader", it.optString("author", ""));
                    o.put("duration", Math.max(0L, it.optLong("lengthSeconds", 0L)));
                    o.put("views", it.optLong("viewCount", 0L));
                    o.put("thumb", "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");
                    out.put(o);
                    if (out.length() >= 30) break;
                }
                if (out.length() == 0) throw new IOException("খালি ফল");
                return new JSONObject().put("ok", true).put("query", q)
                        .put("items", out).put("source", "invidious");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Invidious সার্ভার পাওয়া যায়নি");
    }

    public static JSONObject getStreams(String videoUrl) throws Exception {
        String id = PipedClient.extractId(videoUrl);
        if (id.isEmpty()) throw new IOException("ভিডিও আইডি পাওয়া যায়নি");
        Exception last = null;
        int tried = 0;
        for (String base : instances()) {
            if (tried++ >= MAX_TRIES) break;
            try {
                // local=true → the instance proxies the video (URLs are not locked to its IP)
                JSONObject s = new JSONObject(httpGet(base + "/api/v1/videos/" + id + "?local=true", 15000));
                if (s.has("error")) throw new IOException(s.optString("error"));

                JSONObject root = new JSONObject();
                root.put("ok", true);
                root.put("source", "invidious");
                root.put("title", s.optString("title", ""));
                root.put("uploader", s.optString("author", ""));
                root.put("url", "https://www.youtube.com/watch?v=" + id);
                root.put("views", s.optLong("viewCount", 0L));
                root.put("duration", Math.max(0L, s.optLong("lengthSeconds", 0L)));
                root.put("description", s.optString("description", ""));

                String playUrl = "";
                String mp4 = "";
                JSONArray videos = new JSONArray();
                JSONArray fs = s.optJSONArray("formatStreams");
                if (fs != null) {
                    for (int i = 0; i < fs.length(); i++) {
                        JSONObject f = fs.optJSONObject(i);
                        if (f == null) continue;
                        String u = abs(base, f.optString("url", ""));
                        if (u.isEmpty()) continue;
                        videos.put(new JSONObject().put("url", u)
                                .put("quality", f.optString("qualityLabel", ""))
                                .put("format", f.optString("container", ""))
                                .put("hasVideo", true).put("hasAudio", true));
                        if (playUrl.isEmpty()) playUrl = u;
                        if (mp4.isEmpty() && "mp4".equals(f.optString("container", ""))) mp4 = u;
                    }
                }
                if (!mp4.isEmpty()) playUrl = mp4;
                root.put("videoStreams", videos);

                JSONArray audios = new JSONArray();
                String firstAudio = "";
                JSONArray af = s.optJSONArray("adaptiveFormats");
                if (af != null) {
                    for (int i = 0; i < af.length(); i++) {
                        JSONObject f = af.optJSONObject(i);
                        if (f == null) continue;
                        if (!f.optString("type", "").startsWith("audio/")) continue;
                        String u = abs(base, f.optString("url", ""));
                        if (u.isEmpty()) continue;
                        audios.put(new JSONObject().put("url", u)
                                .put("bitrate", f.optInt("bitrate", 0))
                                .put("format", f.optString("container", "")));
                        if (firstAudio.isEmpty()) firstAudio = u;
                    }
                }
                root.put("audioStreams", audios);

                boolean audioOnly = false;
                if (playUrl.isEmpty()) {
                    String hls = abs(base, s.optString("hlsUrl", ""));
                    if (!hls.isEmpty()) playUrl = hls;
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
        throw last != null ? last : new IOException("Invidious সার্ভার পাওয়া যায়নি");
    }
}
