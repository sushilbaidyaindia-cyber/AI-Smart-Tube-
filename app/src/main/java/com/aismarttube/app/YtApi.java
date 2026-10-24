package com.aismarttube.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** YouTube Data API v3 — the signed-in user's liked videos. Costs 1 quota unit per page. */
public final class YtApi {
    private static final int MAX_PAGES = 4;   // 4 x 50 = 200 videos

    private YtApi() { }

    /** @param apiKey unused (the OAuth token is enough); kept so callers can pass null */
    public static List<VideoItem> likedVideos(Context c, String apiKey) throws Exception {
        List<VideoItem> out = new ArrayList<>();
        String page = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            String u = "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails,statistics"
                    + "&myRating=like&maxResults=50"
                    + (page != null ? "&pageToken=" + URLEncoder.encode(page, "UTF-8") : "");
            JSONObject j = get(c, u);
            JSONArray items = j.optJSONArray("items");
            if (items != null) {
                for (int k = 0; k < items.length(); k++) {
                    VideoItem v = parse(items.optJSONObject(k));
                    if (v != null) out.add(v);
                }
            }
            page = j.optString("nextPageToken", "");
            if (page.isEmpty()) break;
        }
        return out;
    }

    private static VideoItem parse(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        JSONObject sn = o.optJSONObject("snippet");
        if (sn == null) sn = new JSONObject();
        JSONObject cd = o.optJSONObject("contentDetails");
        JSONObject st = o.optJSONObject("statistics");
        String thumb = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        String chId = sn.optString("channelId", "");
        return new VideoItem(
                sn.optString("title", ""),
                "https://www.youtube.com/watch?v=" + id,
                sn.optString("channelTitle", ""),
                chId.isEmpty() ? "" : "https://www.youtube.com/channel/" + chId,
                cd == null ? 0 : isoSeconds(cd.optString("duration", "")),
                st == null ? 0 : st.optLong("viewCount", 0),
                thumb);
    }

    private static long isoSeconds(String d) {
        Matcher m = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(d);
        if (!m.matches()) return 0;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long mi = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long s = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        return h * 3600 + mi * 60 + s;
    }

    private static JSONObject get(Context c, String url) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Authorization", "Bearer " + YtAuth.token(c));
            h.setRequestProperty("Accept", "application/json");
            try {
                int code = h.getResponseCode();
                String body = read(code / 100 == 2 ? h.getInputStream() : h.getErrorStream());
                if (code == 401 && attempt == 0) {   // token expired → fresh one, retry once
                    YtAuth.clearToken(c);
                    continue;
                }
                if (code / 100 != 2) throw new Exception(apiError(code, body));
                return new JSONObject(body);
            } finally {
                h.disconnect();
            }
        }
        throw new Exception("লগইন মেয়াদ শেষ, আবার লগইন করুন");
    }

    private static String apiError(int code, String body) {
        String msg = "";
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) msg = e.optString("message", "");
        } catch (Throwable ignored) { }
        if (code == 403) return "অনুমতি নেই বা কোটা শেষ। " + msg;
        return "ত্রুটি " + code + (msg.isEmpty() ? "" : ": " + msg);
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
