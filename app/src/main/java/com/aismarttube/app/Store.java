package com.aismarttube.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One place for the data that both the web screens and the native screens use:
 * subscriptions, history, liked, watch-later, resume positions and settings.
 * index.html reads and writes these through Android.storeGet / storeSet.
 */
final class Store {

    private Store() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("aist_store", Context.MODE_PRIVATE);
    }

    static String get(Context c, String key) {
        try { return p(c).getString(key, ""); } catch (Throwable t) { return ""; }
    }

    static void set(Context c, String key, String json) {
        try { p(c).edit().putString(key, json == null ? "" : json).apply(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ settings

    private static JSONObject settings(Context c) {
        try {
            String s = get(c, "settings");
            if (!s.isEmpty()) return new JSONObject(s);
        } catch (Throwable ignored) {}
        return new JSONObject();
    }

    static String settingStr(Context c, String id, String def) {
        try {
            JSONObject o = settings(c);
            if (o.has(id)) {
                Object v = o.get(id);
                String s = String.valueOf(v);
                return s.isEmpty() ? def : s;
            }
        } catch (Throwable ignored) {}
        return def;
    }

    static boolean settingBool(Context c, String id, boolean def) {
        try {
            JSONObject o = settings(c);
            if (o.has(id)) {
                Object v = o.get(id);
                if (v instanceof Boolean) return (Boolean) v;
                return "true".equals(String.valueOf(v));
            }
        } catch (Throwable ignored) {}
        return def;
    }

    static void putSetting(Context c, String id, String value) {
        try {
            JSONObject o = settings(c);
            o.put(id, value);
            set(c, "settings", o.toString());
        } catch (Throwable ignored) {}
    }

    static void putSettingBool(Context c, String id, boolean value) {
        try {
            JSONObject o = settings(c);
            o.put(id, value);
            set(c, "settings", o.toString());
        } catch (Throwable ignored) {}
    }

    // --------------------------------------------------------------- lists

    private static JSONArray arr(Context c, String key) {
        try {
            String s = get(c, key);
            if (!s.isEmpty()) return new JSONArray(s);
        } catch (Throwable ignored) {}
        return new JSONArray();
    }

    static boolean listHas(Context c, String key, String url) {
        JSONArray a = arr(c, key);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && url.equals(o.optString("url"))) return true;
        }
        return false;
    }

    /** returns true when the item is now in the list */
    static boolean listToggle(Context c, String key, VideoItem it) {
        JSONArray a = arr(c, key);
        JSONArray out = new JSONArray();
        boolean had = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (it.url.equals(o.optString("url"))) { had = true; continue; }
            out.put(o);
        }
        if (had) { set(c, key, out.toString()); return false; }
        JSONArray res = new JSONArray();
        res.put(it.toJson());
        for (int i = 0; i < out.length() && res.length() < 200; i++) res.put(out.opt(i));
        set(c, key, res.toString());
        return true;
    }

    static void addHistory(Context c, VideoItem it) {
        if (!settingBool(c, "keepHistory", true)) return;
        JSONArray a = arr(c, "history");
        JSONArray res = new JSONArray();
        res.put(it.toJson());
        for (int i = 0; i < a.length() && res.length() < 60; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !it.url.equals(o.optString("url"))) res.put(o);
        }
        set(c, "history", res.toString());
    }

    // ------------------------------------------------------- subscriptions

    static boolean isSubbed(Context c, String name) {
        JSONArray a = arr(c, "subs");
        for (int i = 0; i < a.length(); i++) if (name.equals(a.optString(i))) return true;
        return false;
    }

    /** returns true when now subscribed */
    static boolean toggleSub(Context c, String name, String url) {
        JSONArray a = arr(c, "subs");
        JSONArray out = new JSONArray();
        boolean had = false;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i);
            if (name.equals(s)) { had = true; continue; }
            out.put(s);
        }
        if (had) { set(c, "subs", out.toString()); return false; }
        JSONArray res = new JSONArray();
        res.put(name);
        for (int i = 0; i < out.length(); i++) res.put(out.opt(i));
        set(c, "subs", res.toString());
        try {
            if (url != null && !url.isEmpty()) {
                String s = get(c, "suburl");
                JSONObject m = s.isEmpty() ? new JSONObject() : new JSONObject(s);
                m.put(name, url);
                set(c, "suburl", m.toString());
            }
        } catch (Throwable ignored) {}
        return true;
    }

    // ------------------------------------------------------ resume position

    static String vidKey(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]v=([\\w-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("youtu\\.be/([\\w-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        return url;
    }

    static long getPos(Context c, String url) {
        try {
            String s = get(c, "pos");
            if (s.isEmpty()) return 0;
            return new JSONObject(s).optLong(vidKey(url), 0);
        } catch (Throwable t) { return 0; }
    }

    static void putPos(Context c, String url, long sec) {
        try {
            String s = get(c, "pos");
            JSONObject m = s.isEmpty() ? new JSONObject() : new JSONObject(s);
            if (sec <= 0) m.remove(vidKey(url)); else m.put(vidKey(url), sec);
            if (m.length() > 150) {
                java.util.Iterator<String> it = m.keys();
                if (it.hasNext()) m.remove(it.next());
            }
            set(c, "pos", m.toString());
        } catch (Throwable ignored) {}
    }
}
