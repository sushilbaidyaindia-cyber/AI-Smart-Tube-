package com.aismarttube.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Past searches kept on the phone (newest first), each with the time it was made. */
final class SearchHistory {

    private SearchHistory() {}

    static final class Entry {
        final String q;
        final long t;
        Entry(String q, long t) { this.q = q; this.t = t; }
    }

    private static JSONArray raw(Context c) {
        try {
            String s = Store.get(c, "searches");
            if (!s.isEmpty()) return new JSONArray(s);
        } catch (Throwable ignored) {}
        return new JSONArray();
    }

    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        JSONArray a = raw(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String q = o.optString("q", "");
            if (!q.isEmpty()) out.add(new Entry(q, o.optLong("t", 0)));
        }
        return out;
    }

    private static void save(Context c, List<Entry> l) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : l) a.put(new JSONObject().put("q", e.q).put("t", e.t));
        } catch (Throwable ignored) {}
        Store.set(c, "searches", a.toString());
    }

    static void add(Context c, String q) {
        if (q == null || q.trim().isEmpty()) return;
        q = q.trim();
        List<Entry> old = list(c);
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(q, System.currentTimeMillis()));
        for (Entry e : old) {
            if (!e.q.equalsIgnoreCase(q) && out.size() < 300) out.add(e);
        }
        save(c, out);
    }

    static void remove(Context c, String q) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list(c)) if (!e.q.equals(q)) out.add(e);
        save(c, out);
    }

    static void deleteDay(Context c, long dayKey) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list(c)) if (Dates.dayKey(e.t) != dayKey) out.add(e);
        save(c, out);
    }

    static void clear(Context c) {
        Store.set(c, "searches", "[]");
    }
}
