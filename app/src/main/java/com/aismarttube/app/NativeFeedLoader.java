package com.aismarttube.app;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/**
 * Loads video lists on a background thread via NewPipeHelper.search.
 * Keeps UI thread free so touch / TalkBack stay responsive.
 */
public final class NativeFeedLoader {

    public interface Callback {
        void onResult(List<VideoItem> items, String error);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private NativeFeedLoader() {}

    public static void search(String query, Callback cb) {
        final String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            cb.onResult(new ArrayList<>(), "খালি সার্চ");
            return;
        }
        POOL.execute(() -> {
            try {
                NewPipeHelper.ensureInit();
                String raw = NewPipeHelper.search(q);
                JSONObject root = new JSONObject(raw);
                if (!root.optBoolean("ok", false)) {
                    cb.onResult(new ArrayList<>(), root.optString("error", "সার্চ ব্যর্থ"));
                    return;
                }
                cb.onResult(parseItems(root.optJSONArray("items")), null);
            } catch (Throwable t) {
                cb.onResult(new ArrayList<>(), t.getMessage() != null ? t.getMessage() : "ত্রুটি");
            }
        });
    }

    // ---- home: parallel queries + in-memory cache ----
    private static final ExecutorService QPOOL = Executors.newFixedThreadPool(4);
    private static final String[] BASE_QUERIES = {"বাংলা জনপ্রিয় ভিডিও", "বাংলা খবর"};
    private static volatile List<VideoItem> homeCache = null;
    private static volatile long homeCacheAt = 0;
    private static final long HOME_TTL = 10 * 60 * 1000L;

    /** cached home list (may be stale), or null */
    public static List<VideoItem> cachedHome() {
        List<VideoItem> c = homeCache;
        return c == null ? null : new ArrayList<>(c);
    }

    public static boolean homeFresh() {
        return homeCache != null && System.currentTimeMillis() - homeCacheAt < HOME_TTL;
    }

    /** Home: the queries run at the same time, results merged in order and de-duplicated. */
    public static void loadHome(Callback cb) { loadHome(null, cb); }

    /** Home made for this user: their subscriptions and recent watching first, then general Bangla topics. */
    public static void loadHome(Context ctx, Callback cb) {
        final Context app = ctx == null ? null : ctx.getApplicationContext();
        POOL.execute(() -> {
            try {
                NewPipeHelper.ensureInit();
                String[] errBox = new String[1];
                List<String> queries = homeQueries(app);
                java.util.Set<String> seen = watchedUrls(app);
                List<VideoItem> out = fetchHome(errBox, queries, seen);
                if (out.isEmpty()) {            // one quiet retry
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                    out = fetchHome(errBox, queries, seen);
                }
                if (out.isEmpty()) {
                    cb.onResult(out, errBox[0] != null ? errBox[0] : "কিছু পাওয়া যায়নি");
                } else {
                    homeCache = new ArrayList<>(out);
                    homeCacheAt = System.currentTimeMillis();
                    cb.onResult(out, null);
                }
            } catch (Throwable t) {
                cb.onResult(new ArrayList<>(), t.getMessage() != null ? t.getMessage() : "ত্রুটি");
            }
        });
    }

    /** searches: 2 recent subscriptions + 2 recently watched channels + 2 general topics (at most 6) */
    private static List<String> homeQueries(Context c) {
        List<String> q = new ArrayList<>();
        if (c != null) {
            try {
                String sv = Store.get(c, "subs");
                JSONArray subs = sv.isEmpty() ? new JSONArray() : new JSONArray(sv);
                for (int i = 0; i < subs.length() && q.size() < 2; i++) {
                    String n = subs.optString(i, "").trim();
                    if (!n.isEmpty() && !q.contains(n)) q.add(n);
                }
            } catch (Throwable ignored) { }
            try {
                String hv = Store.get(c, "history");
                JSONArray h = hv.isEmpty() ? new JSONArray() : new JSONArray(hv);
                int added = 0;
                for (int i = 0; i < h.length() && added < 2; i++) {
                    JSONObject o = h.optJSONObject(i);
                    String n = o == null ? "" : o.optString("uploader", "").trim();
                    if (!n.isEmpty() && !q.contains(n)) { q.add(n); added++; }
                }
            } catch (Throwable ignored) { }
        }
        for (String b : BASE_QUERIES) q.add(b);
        return q;
    }

    private static java.util.Set<String> watchedUrls(Context c) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (c == null) return seen;
        try {
            String hv = Store.get(c, "history");
            JSONArray h = hv.isEmpty() ? new JSONArray() : new JSONArray(hv);
            for (int i = 0; i < h.length() && i < 60; i++) {
                JSONObject o = h.optJSONObject(i);
                if (o != null) seen.add(o.optString("url", ""));
            }
        } catch (Throwable ignored) { }
        return seen;
    }

    private static List<VideoItem> fetchHome(String[] errBox, final List<String> queries, java.util.Set<String> seen) {
        final int n = queries.size();
        final List<VideoItem>[] parts = new List[n];
        final CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            QPOOL.execute(() -> {
                try {
                    JSONObject root = new JSONObject(NewPipeHelper.search(queries.get(idx)));
                    if (root.optBoolean("ok", false)) parts[idx] = parseItems(root.optJSONArray("items"));
                    else errBox[0] = root.optString("error", errBox[0]);
                } catch (Throwable e) {
                    if (e.getMessage() != null) errBox[0] = e.getMessage();
                } finally {
                    latch.countDown();
                }
            });
        }
        try { latch.await(25, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        // interleave so the feed mixes topics
        Map<String, VideoItem> map = new LinkedHashMap<>();
        for (int k = 0; k < 12; k++) {
            for (int i = 0; i < n; i++) {
                List<VideoItem> p = parts[i];
                if (p != null && k < p.size() && !map.containsKey(p.get(k).url)) map.put(p.get(k).url, p.get(k));
            }
        }
        List<VideoItem> all = new ArrayList<>(map.values());
        // already watched videos go to the end, so the top of the home is fresh
        List<VideoItem> fresh = new ArrayList<>(), old = new ArrayList<>();
        for (VideoItem v : all) (seen.contains(v.url) ? old : fresh).add(v);
        fresh.addAll(old);
        return fresh;
    }

    // ---- prefetch: warm the stream cache so the player starts faster ----
    private static final ExecutorService PRE = Executors.newSingleThreadExecutor();
    private static volatile String latestPrefetch = "";

    /** latest-wins: if the user moved on to another card, the older request is skipped */
    public static void prefetch(String url) {
        if (url == null || url.isEmpty()) return;
        latestPrefetch = url;
        PRE.execute(() -> {
            if (!url.equals(latestPrefetch)) return;
            NewPipeHelper.prefetch(url);
        });
    }

    private static List<VideoItem> parseItems(JSONArray arr) {
        List<VideoItem> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            VideoItem it = VideoItem.fromJson(arr.optJSONObject(i));
            if (it != null && !it.url.isEmpty()) list.add(it);
        }
        return list;
    }
}
