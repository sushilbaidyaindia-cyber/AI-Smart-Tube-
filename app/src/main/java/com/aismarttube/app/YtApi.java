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

    /** Adds the signed-in user's YouTube subscriptions to the app's local subscription list. Returns how many were new. */
    public static int importSubscriptions(Context c) throws Exception {
        JSONArray subs;
        JSONObject urls;
        String sv = Store.get(c, "subs");
        String uv = Store.get(c, "suburl");
        subs = sv.isEmpty() ? new JSONArray() : new JSONArray(sv);
        urls = uv.isEmpty() ? new JSONObject() : new JSONObject(uv);
        java.util.HashSet<String> have = new java.util.HashSet<>();
        for (int i = 0; i < subs.length(); i++) have.add(subs.optString(i));
        List<String> add = new ArrayList<>();
        String page = null;
        for (int p = 0; p < 10; p++) {   // up to 500 channels
            String u = "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50"
                    + (page != null ? "&pageToken=" + URLEncoder.encode(page, "UTF-8") : "");
            JSONObject j = get(c, u);
            JSONArray items = j.optJSONArray("items");
            if (items != null) {
                for (int k = 0; k < items.length(); k++) {
                    JSONObject sn = items.optJSONObject(k) == null ? null : items.optJSONObject(k).optJSONObject("snippet");
                    if (sn == null) continue;
                    String name = sn.optString("title", "");
                    JSONObject rid = sn.optJSONObject("resourceId");
                    String id = rid == null ? "" : rid.optString("channelId", "");
                    if (name.isEmpty() || id.isEmpty()) continue;
                    if (!urls.has(name)) urls.put(name, "https://www.youtube.com/channel/" + id);
                    if (have.add(name)) add.add(name);
                }
            }
            page = j.optString("nextPageToken", "");
            if (page.isEmpty()) break;
        }
        if (!add.isEmpty()) {
            JSONArray out = new JSONArray();
            for (String n : add) out.put(n);
            for (int i = 0; i < subs.length(); i++) out.put(subs.optString(i));
            Store.set(c, "subs", out.toString());
            Store.set(c, "suburl", urls.toString());
            Store.set(c, "subfeed", "");   // rebuild the "new videos" feed
        }
        return add.size();
    }

    /** The signed-in user's own playlists: each entry {id, title, count}. */
    public static List<String[]> playlists(Context c) throws Exception {
        List<String[]> out = new ArrayList<>();
        String page = null;
        for (int p = 0; p < 4; p++) {
            String u = "https://www.googleapis.com/youtube/v3/playlists?part=snippet,contentDetails&mine=true&maxResults=50"
                    + (page != null ? "&pageToken=" + URLEncoder.encode(page, "UTF-8") : "");
            JSONObject j = get(c, u);
            JSONArray items = j.optJSONArray("items");
            if (items != null) {
                for (int k = 0; k < items.length(); k++) {
                    JSONObject o = items.optJSONObject(k);
                    if (o == null) continue;
                    JSONObject sn = o.optJSONObject("snippet");
                    JSONObject cd = o.optJSONObject("contentDetails");
                    out.add(new String[]{o.optString("id", ""),
                            sn == null ? "" : sn.optString("title", ""),
                            String.valueOf(cd == null ? 0 : cd.optInt("itemCount", 0))});
                }
            }
            page = j.optString("nextPageToken", "");
            if (page.isEmpty()) break;
        }
        return out;
    }

    /** Videos inside one playlist (up to 200). */
    public static List<VideoItem> playlistItems(Context c, String playlistId) throws Exception {
        List<VideoItem> out = new ArrayList<>();
        String page = null;
        for (int p = 0; p < MAX_PAGES; p++) {
            String u = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&playlistId="
                    + URLEncoder.encode(playlistId, "UTF-8")
                    + (page != null ? "&pageToken=" + URLEncoder.encode(page, "UTF-8") : "");
            JSONObject j = get(c, u);
            JSONArray items = j.optJSONArray("items");
            if (items != null) {
                for (int k = 0; k < items.length(); k++) {
                    JSONObject o = items.optJSONObject(k);
                    JSONObject sn = o == null ? null : o.optJSONObject("snippet");
                    if (sn == null) continue;
                    JSONObject rid = sn.optJSONObject("resourceId");
                    String id = rid == null ? "" : rid.optString("videoId", "");
                    String t = sn.optString("title", "");
                    if (id.isEmpty() || t.equals("Private video") || t.equals("Deleted video")) continue;
                    String chId = sn.optString("videoOwnerChannelId", "");
                    out.add(new VideoItem(t, "https://www.youtube.com/watch?v=" + id,
                            sn.optString("videoOwnerChannelTitle", ""),
                            chId.isEmpty() ? "" : "https://www.youtube.com/channel/" + chId,
                            0, 0, "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"));
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
        return call(c, "GET", url, null);
    }

    private static JSONObject call(Context c, String method, String url, String json) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setRequestMethod(method);
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Authorization", "Bearer " + YtAuth.token(c));
            h.setRequestProperty("Accept", "application/json");
            if (json != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                h.getOutputStream().write(json.getBytes("UTF-8"));
            } else if ("POST".equals(method)) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Length", "0");
                h.getOutputStream().close();   // empty body
            }
            try {
                int code = h.getResponseCode();
                String body = read(code / 100 == 2 ? h.getInputStream() : h.getErrorStream());
                if (code == 401 && attempt == 0) {   // token expired → fresh one, retry once
                    YtAuth.clearToken(c);
                    continue;
                }
                if (code / 100 != 2) throw new Exception(apiError(code, body));
                return body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
            } finally {
                h.disconnect();
            }
        }
        throw new Exception("লগইন মেয়াদ শেষ, আবার লগইন করুন");
    }

    // ------------------------------------------------------------ writing to the account (50 quota units each)

    /** rating: "like" or "none" */
    public static void rate(Context c, String videoId, String rating) throws Exception {
        call(c, "POST", "https://www.googleapis.com/youtube/v3/videos/rate?id="
                + URLEncoder.encode(videoId, "UTF-8") + "&rating=" + rating, null);
    }

    public static void subscribe(Context c, String channelId) throws Exception {
        String body = "{\"snippet\":{\"resourceId\":{\"kind\":\"youtube#channel\",\"channelId\":\"" + channelId + "\"}}}";
        try {
            call(c, "POST", "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet", body);
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (!m.contains("already")) throw e;   // already subscribed on YouTube = fine
        }
    }

    public static void unsubscribe(Context c, String channelId) throws Exception {
        JSONObject j = get(c, "https://www.googleapis.com/youtube/v3/subscriptions?part=id&mine=true&forChannelId="
                + URLEncoder.encode(channelId, "UTF-8"));
        JSONArray items = j.optJSONArray("items");
        if (items == null || items.length() == 0) return;   // not subscribed there
        String id = items.getJSONObject(0).optString("id", "");
        if (!id.isEmpty()) call(c, "DELETE", "https://www.googleapis.com/youtube/v3/subscriptions?id="
                + URLEncoder.encode(id, "UTF-8"), null);
    }

    /** Adds the account's liked videos that the app does not know yet to the app's own "liked" list. */
    public static int importLikes(Context c) throws Exception {
        List<VideoItem> g = likedVideos(c, null);
        String sv = Store.get(c, "liked");
        JSONArray cur = sv.isEmpty() ? new JSONArray() : new JSONArray(sv);
        java.util.HashSet<String> have = new java.util.HashSet<>();
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o != null) have.add(o.optString("url"));
        }
        int added = 0;
        for (VideoItem v : g) {
            if (cur.length() >= 200) break;
            if (have.add(v.url)) { cur.put(v.toJson()); added++; }
        }
        if (added > 0) Store.set(c, "liked", cur.toString());
        return added;
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
