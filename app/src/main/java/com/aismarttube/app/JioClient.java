package com.aismarttube.app;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Talks to the Jio TV service directly (no separate server): OTP login, token refresh,
 * channel list and the playing link of a channel. Everything Jio-specific lives in this one file,
 * so if Jio changes something only this file needs an update.
 * Based on the open source project JioTV Go (the same requests, written for Android).
 */
final class JioClient {

    private JioClient() { }

    static final String SCHEME = "jio://";

    private static final String API = "https://jiotvapi.media.jio.com";
    private static final String URL_REFRESH_AUTH = "https://auth.media.jio.com/tokenservice/apis/v1/refreshtoken?langId=6";
    private static final String URL_REFRESH_SSO = "https://tv.media.jio.com/apis/v2.0/loginotp/refresh?langId=6";
    private static final String URL_CH_AUTH = "https://jiotv.data.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?os=android&devicetype=phone&usertype=tvYR7NSNn7rymo3F";
    private static final String URL_CH_DEFAULT = "https://jiotvapi.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?langId=6&os=android&devicetype=phone&usertype=JIO&version=315&langId=6";
    private static final String URL_PLAY = API + "/playback/apis/v1.1/geturl?langId=6";

    private static final String UA_OKHTTP = "okhttp/4.12.0";
    /** the player-like agent the Jio CDN expects for media files */
    static final String UA_PLAYER = "plaYtv/7.1.8 (Linux;Android 8.1.0) ExoPlayerLib/2.11.7";
    private static final String APPKEY = "NzNiMDhlYzQyNjJm";
    private static final String USERGROUP = "tvYR7NSNn7rymo3F";
    private static final String VERSION_CODE = "422";

    private static final String K_CRED = "jio_cred", K_CH = "jio_chans", K_CH_T = "jio_chans_t", K_DEV = "jio_dev";
    private static final long CH_STALE_MS = 12L * 3600 * 1000;

    private static final Object LOCK = new Object();

    // ------------------------------------------------------------ public types

    /** what the player needs to play one channel */
    static final class Play {
        String url = "";
        String hdnea = "";
        final Map<String, String> headers = new HashMap<>();
    }

    // ------------------------------------------------------------ state

    static boolean isLoggedIn(Context c) {
        try {
            JSONObject o = cred(c);
            return !o.optString("auth", "").isEmpty() && !o.optString("sso", "").isEmpty() && !o.optString("crm", "").isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    static void logout(Context c) {
        Store.set(c, K_CRED, "");
        Store.set(c, K_CH, "");
        Store.set(c, K_CH_T, "");
    }

    private static JSONObject cred(Context c) {
        try {
            String s = Store.get(c, K_CRED);
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void saveCred(Context c, JSONObject o) {
        Store.set(c, K_CRED, o.toString());
    }

    private static String deviceId(Context c) {
        String d = Store.get(c, K_DEV);
        if (d.length() < 8) {
            byte[] b = new byte[8];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
            d = sb.toString();
            Store.set(c, K_DEV, d);
        }
        return d;
    }

    // ------------------------------------------------------------ http

    private static final class Resp {
        int code;
        String body = "";
    }

    private static Resp http(String method, String url, Map<String, String> headers, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setRequestMethod(method);
            h.setConnectTimeout(15000);
            h.setReadTimeout(25000);
            h.setUseCaches(false);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) h.setRequestProperty(e.getKey(), e.getValue());
            }
            if (body != null) {
                h.setDoOutput(true);
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                h.setFixedLengthStreamingMode(b.length);
                h.getOutputStream().write(b);
            }
            Resp r = new Resp();
            r.code = h.getResponseCode();
            InputStream in = r.code >= 400 ? h.getErrorStream() : h.getInputStream();
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 8 * 1024 * 1024) bo.write(buf, 0, n);
                in.close();
                r.body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
            return r;
        } finally {
            h.disconnect();
        }
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("appname", "RJIL_JioTV");
        h.put("os", "android");
        h.put("devicetype", "phone");
        h.put("Content-Type", "application/json");
        h.put("User-Agent", UA_OKHTTP);
        return h;
    }

    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------ login

    /** number: the 10 digit Jio number */
    static void sendOtp(Context c, String number10) throws Exception {
        String num = number10.replaceAll("[^0-9]", "");
        if (num.length() != 10) throw new Exception("১০ অঙ্কের Jio নম্বর দিন");
        JSONObject body = new JSONObject().put("number", b64("+91" + num));
        Resp r = http("POST", API + "/userservice/apis/v1/loginotp/send", jsonHeaders(), body.toString());
        if (r.code != 204 && r.code != 200) throw new Exception("OTP পাঠানো যায়নি (কোড " + r.code + "). নম্বরটি Jio-র কিনা দেখুন। " + shortBody(r.body));
    }

    static void verifyOtp(Context c, String number10, String otp) throws Exception {
        String num = number10.replaceAll("[^0-9]", "");
        String dev = deviceId(c);
        JSONObject platform = new JSONObject().put("name", "SM-G930F").put("version", "");
        JSONObject info = new JSONObject().put("type", "android").put("platform", platform).put("androidId", dev);
        JSONObject device = new JSONObject().put("consumptionDeviceName", "SM-G930F").put("info", info);
        JSONObject body = new JSONObject().put("number", b64("+91" + num)).put("otp", otp.trim()).put("deviceInfo", device);
        Resp r = http("POST", API + "/userservice/apis/v1/loginotp/verify", jsonHeaders(), body.toString());
        if (r.code != 200) throw new Exception("লগইন হয়নি (কোড " + r.code + "). OTP ঠিক আছে কিনা দেখুন। " + shortBody(r.body));
        JSONObject j = new JSONObject(r.body);
        String auth = j.optString("authToken", "");
        if (auth.isEmpty()) throw new Exception("OTP ভুল হয়েছে");
        JSONObject user = j.optJSONObject("sessionAttributes") == null ? null : j.optJSONObject("sessionAttributes").optJSONObject("user");
        JSONObject o = new JSONObject();
        long now = System.currentTimeMillis() / 1000;
        o.put("auth", auth);
        o.put("refresh", j.optString("refreshToken", ""));
        o.put("sso", j.optString("ssoToken", ""));
        o.put("crm", user == null ? "" : user.optString("subscriberId", ""));
        o.put("uid", user == null ? "" : user.optString("unique", ""));
        o.put("tAuth", now);
        o.put("tSso", now);
        saveCred(c, o);
        if (!isLoggedIn(c)) throw new Exception("লগইনের তথ্য অসম্পূর্ণ এসেছে, আবার চেষ্টা করুন");
    }

    private static String shortBody(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    // ------------------------------------------------------------ tokens

    private static long jwtExp(String token) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 3) return 0;
            byte[] b = Base64.decode(p[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(b, StandardCharsets.UTF_8)).optLong("exp", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean needsRefresh(String token, long lastRefresh, long fallbackTtl, long fallbackLead) {
        if (token.isEmpty()) return true;
        long now = System.currentTimeMillis() / 1000;
        long exp = jwtExp(token);
        if (exp > 0) return exp <= now + 30;
        if (lastRefresh <= 0) return true;
        return lastRefresh + fallbackTtl - fallbackLead <= now;
    }

    /** refreshes the access token / SSO token when they are about to expire. Throws when login is needed again. */
    static void ensureFresh(Context c) throws Exception {
        synchronized (LOCK) {
            JSONObject o = cred(c);
            if (o.optString("auth", "").isEmpty()) throw new Exception("আগে Jio-তে লগইন করুন");
            String dev = deviceId(c);
            long now = System.currentTimeMillis() / 1000;

            if (!o.optString("refresh", "").isEmpty() && needsRefresh(o.optString("auth", ""), o.optLong("tAuth", 0), 7200, 600)) {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("devicetype", "phone");
                h.put("versionCode", VERSION_CODE);
                h.put("os", "android");
                h.put("Content-Type", "application/json; charset=utf-8");
                h.put("User-Agent", UA_OKHTTP);
                h.put("accessToken", o.optString("auth", ""));
                JSONObject b = new JSONObject().put("appName", "RJIL_JioTV").put("deviceId", dev).put("refreshToken", o.optString("refresh", ""));
                Resp r = http("POST", URL_REFRESH_AUTH, h, b.toString());
                if (r.code != 200) {
                    if (r.code == 401 || r.code == 403) throw new Exception("Jio লগইনের মেয়াদ শেষ। আবার লগইন করুন");
                    throw new Exception("Jio টোকেন নতুন করা যায়নি (কোড " + r.code + ")");
                }
                String na = new JSONObject(r.body).optString("authToken", "");
                if (na.isEmpty()) throw new Exception("Jio টোকেন নতুন করা যায়নি");
                o.put("auth", na);
                o.put("tAuth", now);
                saveCred(c, o);
            }

            if (!o.optString("sso", "").isEmpty() && !o.optString("uid", "").isEmpty()
                    && needsRefresh(o.optString("sso", ""), o.optLong("tSso", 0), 86400, 3600)) {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("devicetype", "phone");
                h.put("versionCode", VERSION_CODE);
                h.put("os", "android");
                h.put("User-Agent", UA_OKHTTP);
                h.put("ssoToken", o.optString("sso", ""));
                h.put("uniqueid", o.optString("uid", ""));
                h.put("deviceid", dev);
                Resp r = http("GET", URL_REFRESH_SSO, h, null);
                if (r.code != 200) {
                    if (r.code == 401 || r.code == 403) throw new Exception("Jio লগইনের মেয়াদ শেষ। আবার লগইন করুন");
                    throw new Exception("Jio SSO টোকেন নতুন করা যায়নি (কোড " + r.code + ")");
                }
                String ns = new JSONObject(r.body).optString("ssoToken", "");
                if (ns.isEmpty()) throw new Exception("Jio SSO টোকেন নতুন করা যায়নি");
                o.put("sso", ns);
                o.put("tSso", now);
                saveCred(c, o);
            }
        }
    }

    // ------------------------------------------------------------ channels

    private static Map<String, String> listHeaders(JSONObject o, boolean authed) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA_OKHTTP);
        h.put("Accept", "application/json");
        h.put("devicetype", "phone");
        h.put("os", "android");
        h.put("appkey", APPKEY);
        h.put("lbcookie", "1");
        h.put("usertype", "JIO");
        if (authed) {
            h.put("versionCode", VERSION_CODE);
            h.put("usergroup", USERGROUP);
            h.put("languageId", "6");
            h.put("isott", "false");
            if (!o.optString("auth", "").isEmpty()) h.put("accessToken", o.optString("auth", ""));
            if (!o.optString("sso", "").isEmpty()) h.put("ssotoken", o.optString("sso", ""));
            if (!o.optString("crm", "").isEmpty()) {
                h.put("crmid", o.optString("crm", ""));
                h.put("subscriberId", o.optString("crm", ""));
                h.put("userId", o.optString("crm", ""));
            }
            if (!o.optString("uid", "").isEmpty()) h.put("uniqueId", o.optString("uid", ""));
        }
        return h;
    }

    private static JSONArray fetchList(String url, Map<String, String> h) throws Exception {
        Resp r = http("GET", url, h, null);
        if (r.code != 200) throw new Exception("কোড " + r.code);
        JSONObject j = new JSONObject(r.body);
        JSONArray a = j.optJSONArray("result");
        return a == null ? new JSONArray() : a;
    }

    /** downloads the channel list from Jio and keeps it for later (needs the network) */
    static void fetchChannels(Context c) throws Exception {
        ensureFresh(c);
        JSONObject o = cred(c);
        JSONArray main = new JSONArray();
        String err = null;
        try {
            main = fetchList(URL_CH_AUTH, listHeaders(o, true));
        } catch (Throwable t) {
            err = t.getMessage();
        }
        JSONArray def = new JSONArray();
        try {
            def = fetchList(URL_CH_DEFAULT, listHeaders(o, false));
        } catch (Throwable t) {
            if (err == null) err = t.getMessage();
        }
        // merge: the account list first, then whatever only the default list has
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < main.length(); i++) {
            JSONObject x = main.optJSONObject(i);
            if (x == null) continue;
            if (seen.add(x.optString("channel_id", ""))) out.put(x);
        }
        for (int i = 0; i < def.length(); i++) {
            JSONObject x = def.optJSONObject(i);
            if (x == null) continue;
            if (seen.add(x.optString("channel_id", ""))) out.put(x);
        }
        if (out.length() == 0) throw new Exception("Jio চ্যানেলের তালিকা আসেনি" + (err == null ? "" : " (" + err + ")"));
        // keep only the fields we use, so the saved list stays small
        JSONArray slim = new JSONArray();
        for (int i = 0; i < out.length(); i++) {
            JSONObject x = out.optJSONObject(i);
            slim.put(new JSONObject()
                    .put("id", x.optString("channel_id", ""))
                    .put("n", x.optString("channel_name", ""))
                    .put("cat", x.optInt("channelCategoryId", 0))
                    .put("lang", x.optInt("channelLanguageId", 0))
                    .put("prem", "premium".equalsIgnoreCase(x.optString("business_type", "").trim()))
                    .put("cu", x.optBoolean("isCatchupAvailable", false)));
        }
        Store.set(c, K_CH, slim.toString());
        Store.set(c, K_CH_T, String.valueOf(System.currentTimeMillis()));
    }

    static boolean channelsStale(Context c) {
        try {
            String t = Store.get(c, K_CH_T);
            if (t.isEmpty() || Store.get(c, K_CH).isEmpty()) return true;
            return System.currentTimeMillis() - Long.parseLong(t) > CH_STALE_MS;
        } catch (Throwable e) {
            return true;
        }
    }

    /** "bn" (default) = only Bengali channels, "all" = every language */
    static boolean allLanguages(Context c) {
        return "all".equals(Store.settingStr(c, "jioLang", "bn"));
    }

    private static String catKey(int id) {
        switch (id) {
            case 5: return "entertainment";
            case 6: return "movies";
            case 7: return "kids";
            case 8: return "sports";
            case 9: return "lifestyle";
            case 10: return "documentary";
            case 12: return "news";
            case 13: return "music";
            case 15: return "religious";
            case 16: return "business";
            case 17: return "education";
            case 18: return "shop";
            case 19: return "religious";
            default: return "";
        }
    }

    /** the saved Jio channels as list entries (no network, safe on the main thread) */
    static List<TvChannels.Ch> cachedChannels(Context c) {
        List<TvChannels.Ch> out = new ArrayList<>();
        if (!isLoggedIn(c)) return out;
        try {
            String s = Store.get(c, K_CH);
            if (s.isEmpty()) return out;
            boolean all = allLanguages(c);
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                if (x.optBoolean("prem", false)) continue;         // needs a separate paid plan
                if (!all && x.optInt("lang", 0) != 5) continue;     // 5 = Bengali
                String id = x.optString("id", "");
                String name = x.optString("n", "").trim();
                if (id.isEmpty() || name.isEmpty()) continue;
                TvChannels.Ch ch = new TvChannels.Ch();
                ch.name = name;
                ch.addCat(catKey(x.optInt("cat", 0)));
                ch.addStream(new TvChannels.Stream(SCHEME + id, "jio", ""));
                out.add(ch);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    // ------------------------------------------------------------ playing a channel

    private static String utcNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String utcDate() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static boolean isHls(String u) {
        return u != null && u.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    private static boolean isAbs(String u) {
        String l = u == null ? "" : u.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private static String absolute(String u, String base) {
        if (u == null || u.isEmpty()) return "";
        if (isAbs(u)) return u;
        if (u.startsWith("//")) return "https:" + u;
        String first = u.split("/", 2)[0];
        if (first.contains(".") && !u.startsWith("/")) return "https://" + u;
        if (!u.startsWith("/")) u = "/" + u;
        return (base.isEmpty() ? "https://jiotvapi.cdn.jio.com" : base) + u;
    }

    private static String hdneaFrom(String u) {
        if (u == null) return "";
        int q = u.indexOf('?');
        if (q < 0) return "";
        String found = "";
        for (String p : u.substring(q + 1).split("&")) {
            if (p.startsWith("__hdnea__=")) { found = p.substring(10); break; }
            if (p.startsWith("hdnea=") && found.isEmpty()) found = p.substring(6);
        }
        if (found.contains("%")) {
            try { found = URLDecoder.decode(found, "UTF-8"); } catch (Throwable ignored) { }
        }
        return found;
    }

    /** asks Jio for the playing link of a channel */
    static Play resolve(Context c, String channelId) throws Exception {
        ensureFresh(c);
        JSONObject o = cred(c);
        String dev = deviceId(c);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("appkey", APPKEY);
        h.put("channel_id", channelId);
        h.put("crmid", o.optString("crm", ""));
        h.put("userId", o.optString("crm", ""));
        h.put("deviceId", dev);
        h.put("devicetype", "phone");
        h.put("isott", "false");
        h.put("languageId", "6");
        h.put("lbcookie", "1");
        h.put("os", "android");
        h.put("osVersion", "13");
        h.put("subscriberId", o.optString("crm", ""));
        h.put("uniqueId", o.optString("uid", ""));
        h.put("User-Agent", UA_OKHTTP);
        h.put("usergroup", USERGROUP);
        h.put("versionCode", VERSION_CODE);
        h.put("accessToken", o.optString("auth", ""));

        String body = "channel_id=" + URLEncoder.encode(channelId, "UTF-8")
                + "&stream_type=Seek&begin=" + utcNow() + "&srno=" + utcDate();
        Resp r = http("POST", URL_PLAY, h, body);
        if (r.code != 200) {
            if (r.code == 401 || r.code == 403) throw new Exception("Jio এই চ্যানেল দিতে রাজি হয়নি (কোড " + r.code + "). আবার লগইন করে দেখুন। " + shortBody(r.body));
            throw new Exception("Jio থেকে লিংক আসেনি (কোড " + r.code + "). " + shortBody(r.body));
        }
        JSONObject j = new JSONObject(r.body);
        JSONObject br = j.optJSONObject("bitrates");
        JSONObject m3 = j.optJSONObject("m3u8");
        JSONObject mpd = j.optJSONObject("mpd");

        String[] cand = {
                br == null ? "" : br.optString("auto", ""),
                br == null ? "" : br.optString("high", ""),
                br == null ? "" : br.optString("medium", ""),
                br == null ? "" : br.optString("low", ""),
                m3 == null ? "" : m3.optString("auto", ""),
                m3 == null ? "" : m3.optString("high", ""),
        };
        String url = "";
        for (String s : cand) if (s != null && !s.isEmpty()) { url = s; break; }
        if (url.isEmpty() && isHls(j.optString("result", ""))) url = j.optString("result", "");
        if (url.isEmpty() && mpd != null && isHls(mpd.optString("result", ""))) url = mpd.optString("result", "");

        if (url.isEmpty()) {
            String msg = j.optString("message", "");
            if (mpd != null && !mpd.optString("result", "").isEmpty() || j.optBoolean("isDRM", false))
                throw new Exception("এই চ্যানেল কপিরাইট-সুরক্ষিত (DRM) পদ্ধতিতে আসে, এখনও চালানো যায় না");
            throw new Exception("Jio এই চ্যানেলের লিংক দেয়নি" + (msg.isEmpty() ? "" : ": " + msg));
        }

        String base = "";
        for (String s : new String[]{url, j.optString("result", "")}) {
            if (isAbs(s)) {
                try {
                    URL u = new URL(s);
                    base = u.getProtocol() + "://" + u.getHost();
                } catch (Throwable ignored) { }
                break;
            }
        }
        Play p = new Play();
        p.url = absolute(url, base);
        p.hdnea = hdneaFrom(p.url);

        // headers for the playlist, keys and video pieces
        for (String k : new String[]{"appkey", "crmid", "userId", "deviceId", "devicetype", "isott", "languageId", "lbcookie", "os",
                "osVersion", "subscriberId", "uniqueId", "usergroup", "versionCode"}) {
            if (h.get(k) != null) p.headers.put(k, h.get(k));
        }
        p.headers.put("ssotoken", o.optString("sso", ""));
        p.headers.put("srno", "230203144000");
        p.headers.put("channelId", channelId);
        if (!p.hdnea.isEmpty()) p.headers.put("Cookie", "__hdnea__=" + p.hdnea);
        return p;
    }

    /** channel id inside a "jio://123" link, or "" */
    static String idOf(String url) {
        return url != null && url.startsWith(SCHEME) ? url.substring(SCHEME.length()) : "";
    }
}
