package com.aismarttube.app;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final String[] HOME_QUERIES = {"বাংলা জনপ্রিয় ভিডিও", "বাংলা গান", "বাংলা খবর"};
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
    public static void loadHome(Callback cb) {
        POOL.execute(() -> {
            try {
                NewPipeHelper.ensureInit();
                String[] errBox = new String[1];
                List<VideoItem> out = fetchHome(errBox);
                if (out.isEmpty()) {            // one quiet retry
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                    out = fetchHome(errBox);
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

    private static List<VideoItem> fetchHome(String[] errBox) {
        final int n = HOME_QUERIES.length;
        final List<VideoItem>[] parts = new List[n];
        final CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            QPOOL.execute(() -> {
                try {
                    JSONObject root = new JSONObject(NewPipeHelper.search(HOME_QUERIES[idx]));
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
        return new ArrayList<>(map.values());
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
