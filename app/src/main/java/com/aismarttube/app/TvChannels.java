package com.aismarttube.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The TV tab's data: channel list from an M3U playlist (default: iptv-org Bengali), favourites, own channels. */
final class TvChannels {

    static final String DEFAULT_SRC = "https://iptv-org.github.io/iptv/languages/ben.m3u";
    private static final String K_CACHE = "tvcache", K_TIME = "tvcache_t", K_FAVS = "tvfavs", K_CUSTOM = "tvcustom";
    private static final long STALE_MS = 24L * 3600 * 1000;
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";

    /** the list the player steps through (set by the list screen right before opening the player) */
    static volatile List<Ch> queue = new ArrayList<>();

    private TvChannels() { }

    static final class Ch {
        String name = "", url = "", group = "", ua = "", ref = "";
        boolean custom = false;

        JSONObject toJson() {
            try {
                return new JSONObject().put("n", name).put("u", url).put("g", group).put("a", ua).put("r", ref);
            } catch (Throwable t) {
                return new JSONObject();
            }
        }

        static Ch fromJson(JSONObject o) {
            Ch c = new Ch();
            c.name = o.optString("n", "");
            c.url = o.optString("u", "");
            c.group = o.optString("g", "");
            c.ua = o.optString("a", "");
            c.ref = o.optString("r", "");
            return c;
        }
    }

    // ------------------------------------------------------------ source

    static String src(Context c) {
        String s = Store.settingStr(c, "tvSrc", DEFAULT_SRC).trim();
        return s.startsWith("http") ? s : DEFAULT_SRC;
    }

    static List<Ch> cached(Context c) {
        List<Ch> out = new ArrayList<>();
        try {
            String s = Store.get(c, K_CACHE);
            if (s.isEmpty()) return out;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(Ch.fromJson(o));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static boolean stale(Context c) {
        try {
            String t = Store.get(c, K_TIME);
            if (t.isEmpty()) return true;
            return System.currentTimeMillis() - Long.parseLong(t) > STALE_MS;
        } catch (Throwable e) {
            return true;
        }
    }

    /** downloads and parses the playlist, saves it for next time */
    static List<Ch> download(Context c) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(src(c)).openConnection();
        try {
            h.setRequestProperty("User-Agent", UA);
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setUseCaches(false);
            int code = h.getResponseCode();
            if (code / 100 != 2) throw new Exception("তালিকা-সার্ভার সাড়া দেয়নি (" + code + ")");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 6 * 1024 * 1024) throw new Exception("তালিকা খুব বড়");
                }
            }
            List<Ch> list = parse(bos.toString("UTF-8"));
            if (list.isEmpty()) throw new Exception("তালিকায় কোনো চ্যানেল পাওয়া যায়নি");
            JSONArray a = new JSONArray();
            for (Ch ch : list) a.put(ch.toJson());
            Store.set(c, K_CACHE, a.toString());
            Store.set(c, K_TIME, String.valueOf(System.currentTimeMillis()));
            return list;
        } finally {
            h.disconnect();
        }
    }

    static List<Ch> parse(String text) {
        List<Ch> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Ch cur = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                cur = new Ch();
                cur.group = attr(line, "group-title").replace(";", ", ");
                cur.name = cleanName(afterComma(line));
                if (cur.name.isEmpty()) cur.name = attr(line, "tvg-name");
            } else if (line.startsWith("#EXTVLCOPT")) {
                if (cur == null) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).toLowerCase(java.util.Locale.US);
                String v = line.substring(eq + 1).trim();
                if (k.contains("user-agent")) cur.ua = v;
                else if (k.contains("referrer") || k.contains("referer")) cur.ref = v;
            } else if (line.startsWith("#")) {
                continue;
            } else if (cur != null && line.startsWith("http")) {
                cur.url = line;
                if (!cur.name.isEmpty() && seen.add(cur.url)) out.add(cur);
                cur = null;
            }
        }
        return out;
    }

    private static String attr(String line, String key) {
        int i = line.indexOf(key + "=\"");
        if (i < 0) return "";
        int s = i + key.length() + 2;
        int e = line.indexOf('"', s);
        return e < 0 ? "" : line.substring(s, e).trim();
    }

    /** the channel name is after the last comma that is not inside quotes */
    private static String afterComma(String line) {
        boolean q = false;
        int last = -1;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) last = i;
        }
        return last < 0 ? "" : line.substring(last + 1).trim();
    }

    private static String cleanName(String n) {
        return n.replaceAll("\\s*\\(\\d{3,4}[pi]\\)", "").trim();
    }

    // ------------------------------------------------------------ own channels

    static List<Ch> custom(Context c) {
        List<Ch> out = new ArrayList<>();
        try {
            String s = Store.get(c, K_CUSTOM);
            if (s.isEmpty()) return out;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Ch ch = Ch.fromJson(o);
                ch.custom = true;
                if (ch.group.isEmpty()) ch.group = "আমার চ্যানেল";
                out.add(ch);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void addCustom(Context c, String name, String url) {
        try {
            JSONArray old = new JSONArray(Store.get(c, K_CUSTOM).isEmpty() ? "[]" : Store.get(c, K_CUSTOM));
            Ch ch = new Ch();
            ch.name = name;
            ch.url = url;
            ch.group = "আমার চ্যানেল";
            JSONArray out = new JSONArray();
            for (int i = 0; i < old.length(); i++) out.put(old.opt(i));
            out.put(ch.toJson());
            Store.set(c, K_CUSTOM, out.toString());
        } catch (Throwable ignored) { }
    }

    static void removeCustom(Context c, String url) {
        try {
            String s = Store.get(c, K_CUSTOM);
            if (s.isEmpty()) return;
            JSONArray old = new JSONArray(s), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !url.equals(o.optString("u", ""))) out.put(o);
            }
            Store.set(c, K_CUSTOM, out.toString());
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ favourites

    private static Set<String> favSet(Context c) {
        Set<String> set = new HashSet<>();
        try {
            String s = Store.get(c, K_FAVS);
            if (s.isEmpty()) return set;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) set.add(a.optString(i, ""));
        } catch (Throwable ignored) { }
        return set;
    }

    static boolean isFav(Context c, String url) { return favSet(c).contains(url); }

    /** returns true when the channel is now a favourite */
    static boolean toggleFav(Context c, String url) {
        Set<String> set = favSet(c);
        boolean now;
        if (set.contains(url)) { set.remove(url); now = false; } else { set.add(url); now = true; }
        JSONArray a = new JSONArray();
        for (String s : set) a.put(s);
        Store.set(c, K_FAVS, a.toString());
        return now;
    }

    static Set<String> favs(Context c) { return favSet(c); }
}
