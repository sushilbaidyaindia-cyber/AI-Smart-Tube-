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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The TV tab's data. One channel = one name, with several streams (servers) as backups.
 * Main list: iptv-org Bengali. Other popular lists only add backup streams for channels with the same name.
 */
final class TvChannels {

    static final String DEFAULT_SRC = "https://iptv-org.github.io/iptv/languages/ben.m3u";
    /** only used to find more working servers (backup streams) for channels we already have */
    private static final String[] BACKUP_SRCS = {
            "https://iptv-org.github.io/iptv/countries/in.m3u",
            "https://iptv-org.github.io/iptv/countries/bd.m3u"
    };
    private static final String K_CACHE = "tvcache", K_TIME = "tvcache_t", K_VER = "tvcache_v", K_FAVS = "tvfavs",
            K_CUSTOM = "tvcustom", K_GOOD = "tvgood";
    private static final String VER = "2";
    private static final long STALE_MS = 24L * 3600 * 1000;
    private static final int MAX_STREAMS = 6;
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";

    /** the list the player steps through (set by the list screen right before opening the player) */
    static volatile List<Ch> queue = new ArrayList<>();

    private TvChannels() { }

    static final class Stream {
        final String url, ua, ref;
        Stream(String url, String ua, String ref) { this.url = url; this.ua = ua == null ? "" : ua; this.ref = ref == null ? "" : ref; }
    }

    static final class Ch {
        String name = "";
        final List<String> cats = new ArrayList<>();   // english category keys, lower case
        final List<Stream> streams = new ArrayList<>();
        boolean custom = false;
        boolean cu = false;      // Jio channel with earlier programmes (catch-up)
        private String key;

        String key() {
            if (key == null) key = normName(name);
            return key;
        }

        String url() { return streams.isEmpty() ? "" : streams.get(0).url; }

        void addStream(Stream s) {
            if (streams.size() >= MAX_STREAMS) return;
            for (Stream o : streams) if (o.url.equals(s.url)) return;
            streams.add(s);
        }

        JSONObject toJson() {
            try {
                JSONArray s = new JSONArray();
                for (Stream st : streams) s.put(new JSONObject().put("u", st.url).put("a", st.ua).put("r", st.ref));
                JSONArray c = new JSONArray();
                for (String x : cats) c.put(x);
                return new JSONObject().put("n", name).put("c", c).put("s", s);
            } catch (Throwable t) {
                return new JSONObject();
            }
        }

        static Ch fromJson(JSONObject o) {
            Ch c = new Ch();
            c.name = o.optString("n", "");
            JSONArray cs = o.optJSONArray("c");
            if (cs != null) for (int i = 0; i < cs.length(); i++) c.addCat(cs.optString(i, ""));
            else c.addCats(o.optString("g", ""));            // old cache format
            JSONArray ss = o.optJSONArray("s");
            if (ss != null) {
                for (int i = 0; i < ss.length(); i++) {
                    JSONObject x = ss.optJSONObject(i);
                    if (x != null) c.addStream(new Stream(x.optString("u", ""), x.optString("a", ""), x.optString("r", "")));
                }
            } else if (!o.optString("u", "").isEmpty()) {
                c.addStream(new Stream(o.optString("u", ""), o.optString("a", ""), o.optString("r", "")));
            }
            return c;
        }

        void addCat(String s) {
            String k = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty() || k.equals("undefined") || cats.contains(k)) return;
            cats.add(k);
        }

        void addCats(String raw) {
            if (raw == null) return;
            for (String p : raw.split("[;,]")) addCat(p);
        }
    }

    static String normName(String n) {
        String s = n == null ? "" : n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        if (s.endsWith("hd") && s.length() > 4) s = s.substring(0, s.length() - 2);
        return s;
    }

    // ------------------------------------------------------------ categories

    static String catLabel(String key) {
        switch (key) {
            case "animation": return "অ্যানিমেশন";
            case "auto": return "গাড়ি";
            case "business": return "ব্যবসা";
            case "classic": return "ক্লাসিক";
            case "comedy": return "কমেডি";
            case "cooking": return "রান্না";
            case "culture": return "সংস্কৃতি";
            case "documentary": return "তথ্যচিত্র";
            case "education": return "শিক্ষা";
            case "entertainment": return "বিনোদন";
            case "family": return "পারিবারিক";
            case "general": return "সাধারণ";
            case "interactive": return "ইন্টারঅ্যাকটিভ";
            case "kids": return "শিশুদের";
            case "legislative": return "সংসদ";
            case "lifestyle": return "জীবনধারা";
            case "movies": return "সিনেমা";
            case "music": return "সংগীত";
            case "news": return "সংবাদ";
            case "outdoor": return "প্রকৃতি";
            case "public": return "সরকারি";
            case "relax": return "বিশ্রাম";
            case "religious": return "ধর্মীয়";
            case "series": return "ধারাবাহিক";
            case "science": return "বিজ্ঞান";
            case "shop": return "কেনাকাটা";
            case "sports": return "খেলা";
            case "travel": return "ভ্রমণ";
            case "weather": return "আবহাওয়া";
            case "mine": return "আমার চ্যানেল";
            case "jio": return "Jio চ্যানেল";
            case "other": return "অন্যান্য";
            default: return key;
        }
    }

    /** "সংবাদ, বিনোদন" for a channel */
    static String catText(Ch c) {
        StringBuilder sb = new StringBuilder();
        for (String k : c.cats) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(catLabel(k));
        }
        return sb.toString();
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
                if (o == null) continue;
                Ch ch = Ch.fromJson(o);
                if (!ch.streams.isEmpty() && !ch.name.isEmpty()) out.add(ch);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static boolean stale(Context c) {
        try {
            if (!VER.equals(Store.get(c, K_VER))) return true;
            String t = Store.get(c, K_TIME);
            if (t.isEmpty()) return true;
            return System.currentTimeMillis() - Long.parseLong(t) > STALE_MS;
        } catch (Throwable e) {
            return true;
        }
    }

    private static String fetchText(String u, int connectMs, int readMs) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        try {
            h.setRequestProperty("User-Agent", UA);
            h.setConnectTimeout(connectMs);
            h.setReadTimeout(readMs);
            h.setUseCaches(false);
            int code = h.getResponseCode();
            if (code / 100 != 2) throw new Exception("তালিকা-সার্ভার সাড়া দেয়নি (" + code + ")");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 8 * 1024 * 1024) throw new Exception("তালিকা খুব বড়");
                }
            }
            return bos.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    /** downloads the main list (and backup lists), merges same-name channels into one, saves for next time */
    static List<Ch> download(Context c) throws Exception {
        List<Ch> main = mergeByName(parse(fetchText(src(c), 15000, 30000)));
        if (main.isEmpty()) throw new Exception("তালিকায় কোনো চ্যানেল পাওয়া যায়নি");
        Map<String, Ch> byKey = new LinkedHashMap<>();
        for (Ch ch : main) byKey.put(ch.key(), ch);
        for (String extra : BACKUP_SRCS) {
            try {
                List<Ch> more = parse(fetchText(extra, 10000, 25000));
                for (Ch e : more) {
                    Ch m = byKey.get(e.key());
                    if (m == null) continue;
                    for (Stream s : e.streams) m.addStream(s);
                }
            } catch (Throwable ignored) { /* a backup list that is down is fine */ }
        }
        JSONArray a = new JSONArray();
        for (Ch ch : main) {
            sortStreams(ch);
            a.put(ch.toJson());
        }
        Store.set(c, K_CACHE, a.toString());
        Store.set(c, K_TIME, String.valueOf(System.currentTimeMillis()));
        Store.set(c, K_VER, VER);
        return main;
    }

    /** https first (they work on more phones and networks); the original order is kept otherwise */
    private static void sortStreams(Ch ch) {
        List<Stream> https = new ArrayList<>(), rest = new ArrayList<>();
        for (Stream s : ch.streams) (s.url.startsWith("https") ? https : rest).add(s);
        ch.streams.clear();
        ch.streams.addAll(https);
        ch.streams.addAll(rest);
    }

    private static List<Ch> mergeByName(List<Ch> raw) {
        Map<String, Ch> map = new LinkedHashMap<>();
        for (Ch e : raw) {
            if (e.cats.contains("xxx")) continue;           // adult channels are never shown
            Ch m = map.get(e.key());
            if (m == null) { map.put(e.key(), e); continue; }
            for (Stream s : e.streams) m.addStream(s);
            for (String k : e.cats) m.addCat(k);
        }
        return new ArrayList<>(map.values());
    }

    /** one entry per stream line of an M3U file */
    static List<Ch> parse(String text) {
        List<Ch> out = new ArrayList<>();
        String name = "", group = "", ua = "", ref = "";
        boolean open = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                open = true;
                ua = "";
                ref = "";
                group = attr(line, "group-title");
                name = cleanName(afterComma(line));
                if (name.isEmpty()) name = attr(line, "tvg-name");
            } else if (line.startsWith("#EXTVLCOPT")) {
                if (!open) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).toLowerCase(Locale.ROOT);
                String v = line.substring(eq + 1).trim();
                if (k.contains("user-agent")) ua = v;
                else if (k.contains("referrer") || k.contains("referer")) ref = v;
            } else if (line.startsWith("#")) {
                continue;
            } else if (open && line.startsWith("http")) {
                if (!name.isEmpty()) {
                    Ch ch = new Ch();
                    ch.name = name;
                    ch.addCats(group);
                    ch.addStream(new Stream(line, ua, ref));
                    out.add(ch);
                }
                open = false;
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

    // ------------------------------------------------------------ last working server

    /** streams in the order to try: the one that worked last time first */
    static List<Stream> orderedStreams(Context c, Ch ch) {
        List<Stream> out = new ArrayList<>(ch.streams);
        try {
            String s = Store.get(c, K_GOOD);
            if (!s.isEmpty()) {
                String good = new JSONObject(s).optString(ch.key(), "");
                if (!good.isEmpty()) {
                    for (int i = 0; i < out.size(); i++) {
                        if (out.get(i).url.equals(good)) { out.add(0, out.remove(i)); break; }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void markGood(Context c, Ch ch, String url) {
        try {
            String s = Store.get(c, K_GOOD);
            JSONObject o = s.isEmpty() ? new JSONObject() : new JSONObject(s);
            if (url.equals(o.optString(ch.key(), ""))) return;
            if (o.length() > 400) o = new JSONObject();
            o.put(ch.key(), url);
            Store.set(c, K_GOOD, o.toString());
        } catch (Throwable ignored) { }
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
                ch.cats.clear();
                ch.cats.add("mine");
                if (!ch.streams.isEmpty()) out.add(ch);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void addCustom(Context c, String name, String url) {
        try {
            String s = Store.get(c, K_CUSTOM);
            JSONArray old = new JSONArray(s.isEmpty() ? "[]" : s);
            Ch ch = new Ch();
            ch.name = name;
            ch.addStream(new Stream(url, "", ""));
            JSONArray out = new JSONArray();
            for (int i = 0; i < old.length(); i++) out.put(old.opt(i));
            out.put(ch.toJson());
            Store.set(c, K_CUSTOM, out.toString());
        } catch (Throwable ignored) { }
    }

    static void removeCustom(Context c, Ch ch) {
        try {
            String s = Store.get(c, K_CUSTOM);
            if (s.isEmpty()) return;
            JSONArray old = new JSONArray(s), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !ch.name.equals(o.optString("n", ""))) out.put(o);
            }
            Store.set(c, K_CUSTOM, out.toString());
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ favourites (by channel name, so they survive list updates)

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

    static boolean isFavIn(Set<String> favs, Ch ch) {
        if (favs.contains(ch.key())) return true;
        for (Stream s : ch.streams) if (favs.contains(s.url)) return true;   // favourites saved by the first version
        return false;
    }

    static boolean isFav(Context c, Ch ch) { return isFavIn(favSet(c), ch); }

    /** returns true when the channel is now a favourite */
    static boolean toggleFav(Context c, Ch ch) {
        Set<String> set = favSet(c);
        boolean now;
        if (isFavIn(set, ch)) {
            set.remove(ch.key());
            for (Stream s : ch.streams) set.remove(s.url);
            now = false;
        } else {
            set.add(ch.key());
            now = true;
        }
        JSONArray a = new JSONArray();
        for (String s : set) a.put(s);
        Store.set(c, K_FAVS, a.toString());
        return now;
    }

    static Set<String> favs(Context c) { return favSet(c); }
}
