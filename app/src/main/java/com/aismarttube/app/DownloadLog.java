package com.aismarttube.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** The app's own list of what it downloaded: {title, type: m4a|mp3, uri, dm (DownloadManager id), t}. Newest first. */
final class DownloadLog {
    private static final String KEY = "downloads";

    private DownloadLog() { }

    static synchronized JSONArray all(Context c) {
        try {
            String s = Store.get(c, KEY);
            return s.isEmpty() ? new JSONArray() : new JSONArray(s);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    static synchronized void add(Context c, String title, String type, String uri, long dm) {
        try {
            JSONArray old = all(c);
            JSONArray out = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("type", type);
            o.put("uri", uri == null ? "" : uri);
            o.put("dm", dm);
            o.put("t", System.currentTimeMillis());
            out.put(o);
            for (int i = 0; i < old.length() && out.length() < 300; i++) out.put(old.opt(i));
            Store.set(c, KEY, out.toString());
        } catch (Throwable ignored) { }
    }

    static synchronized void removeAt(Context c, int index) {
        try {
            JSONArray old = all(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < old.length(); i++) if (i != index) out.put(old.opt(i));
            Store.set(c, KEY, out.toString());
        } catch (Throwable ignored) { }
    }
}
