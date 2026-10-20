package com.aismarttube.app;

import org.json.JSONObject;

/** One video row for native Home / Search lists. */
public class VideoItem {
    public final String title;
    public final String url;
    public final String uploader;
    public final String uploaderUrl;
    public final long duration; // seconds, may be 0
    public final long views;
    public final String thumb;
    /** "video" or "playlist" (channel pages) */
    public String kind = "video";
    public long count = 0;

    public VideoItem(String title, String url, String uploader, String uploaderUrl,
                     long duration, long views, String thumb) {
        this.title = title != null ? title : "";
        this.url = url != null ? url : "";
        this.uploader = uploader != null ? uploader : "";
        this.uploaderUrl = uploaderUrl != null ? uploaderUrl : "";
        this.duration = duration;
        this.views = views;
        this.thumb = thumb != null ? thumb : "";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("title", title);
            o.put("url", url);
            o.put("uploader", uploader);
            o.put("uploaderUrl", uploaderUrl);
            o.put("duration", duration);
            o.put("views", views);
            o.put("thumb", thumb);
        } catch (Exception ignored) {}
        return o;
    }

    public static VideoItem fromJson(JSONObject o) {
        if (o == null) return null;
        VideoItem v = build(o);
        v.kind = o.optString("kind", "video");
        v.count = o.optLong("count", 0);
        return v;
    }

    private static VideoItem build(JSONObject o) {
        return new VideoItem(
                o.optString("title", ""),
                o.optString("url", ""),
                o.optString("uploader", ""),
                o.optString("uploaderUrl", ""),
                o.optLong("duration", 0),
                o.optLong("views", 0),
                o.optString("thumb", "")
        );
    }

    /** Spoken / content-description for TalkBack — one clear label per row. */
    public String talkBackLabel() {
        if ("playlist".equals(kind)) {
            return "প্লেলিস্ট: " + title + (count > 0 ? ", " + formatCountBn(count) + "টি ভিডিও" : "");
        }
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) sb.append(title);
        if (!uploader.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(uploader);
        }
        if (duration > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("সময় ").append(formatDurationBn(duration));
        }
        if (views > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(formatViewsBn(views));
        }
        return sb.length() > 0 ? sb.toString() : "ভিডিও";
    }

    public static String formatDurationBn(long sec) {
        if (sec <= 0) return "";
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        String bn = "০১২৩৪৫৬৭৮৯";
        java.util.function.Function<Long, String> toBn = n -> {
            String t = String.valueOf(n);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                b.append(c >= '0' && c <= '9' ? bn.charAt(c - '0') : c);
            }
            return b.toString();
        };
        if (h > 0) return toBn.apply(h) + " ঘণ্টা " + toBn.apply(m) + " মিনিট";
        if (m > 0) return toBn.apply(m) + " মিনিট" + (s > 0 ? " " + toBn.apply(s) + " সেকেন্ড" : "");
        return toBn.apply(s) + " সেকেন্ড";
    }

    public static String formatViewsBn(long n) {
        if (n <= 0) return "";
        String bn = "০১২৩৪৫৬৭৮৯";
        java.util.function.Function<String, String> toBn = t -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                b.append(c >= '0' && c <= '9' ? bn.charAt(c - '0') : c);
            }
            return b.toString();
        };
        if (n >= 10_000_000) return toBn.apply(String.format("%.1f", n / 10_000_000.0)) + " কোটি ভিউ";
        if (n >= 100_000) return toBn.apply(String.format("%.1f", n / 100_000.0)) + " লাখ ভিউ";
        if (n >= 1000) return toBn.apply(String.format("%.1f", n / 1000.0)) + " হাজার ভিউ";
        return toBn.apply(String.valueOf(n)) + " ভিউ";
    }

    public static String formatCountBn(long n) {
        String bn = "০১২৩৪৫৬৭৮৯";
        String t = String.valueOf(n);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            b.append(c >= '0' && c <= '9' ? bn.charAt(c - '0') : c);
        }
        return b.toString();
    }
}
