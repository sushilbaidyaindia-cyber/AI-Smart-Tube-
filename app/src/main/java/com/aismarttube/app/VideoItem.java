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
    /** upload (or live start) time in epoch milliseconds, 0 = unknown */
    public long when = 0;
    /** true while it is a live broadcast (views then means "watching now") */
    public boolean live = false;
    /** ready-made Bangla text from the extractor, e.g. "২ দিন আগে" (used when {@link #when} is unknown) */
    public String uploaded = "";

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
            if (when > 0) o.put("when", when);
            if (live) { o.put("live", true); o.put("isLive", true); }
            if (!uploaded.isEmpty()) o.put("uploaded", uploaded);
        } catch (Exception ignored) {}
        return o;
    }

    public static VideoItem fromJson(JSONObject o) {
        if (o == null) return null;
        VideoItem v = build(o);
        v.kind = o.optString("kind", "video");
        v.count = o.optLong("count", 0);
        v.when = o.optLong("when", 0);
        v.live = o.optBoolean("live", false) || o.optBoolean("isLive", false);
        v.uploaded = o.optString("uploaded", "");
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
        if (live) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("লাইভ");
        }
        if (views > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(live ? formatCountBn(views) + " জন দেখছেন" : formatViewsBn(views));
        }
        String ag = agoBn();
        if (!ag.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(live ? "শুরু হয়েছে " + ag : ag);
        }
        return sb.length() > 0 ? sb.toString() : "ভিডিও";
    }

    /** "৫ মিনিট আগে", "২ দিন আগে" … ; empty when the time is unknown */
    public String agoBn() {
        if (when <= 0) return uploaded == null ? "" : formatCountBn(uploaded);
        long sec = Math.max(0, (System.currentTimeMillis() - when) / 1000);
        String n;
        if (sec < 60) return "এইমাত্র";
        if (sec < 3600) n = formatCountBn(sec / 60) + " মিনিট";
        else if (sec < 86400) n = formatCountBn(sec / 3600) + " ঘণ্টা";
        else if (sec < 86400L * 30) n = formatCountBn(sec / 86400) + " দিন";
        else if (sec < 86400L * 365) n = formatCountBn(sec / (86400L * 30)) + " মাস";
        else n = formatCountBn(sec / (86400L * 365)) + " বছর";
        return n + " আগে";
    }

    /** short line shown under the title: views · age */
    public String statsBn() {
        StringBuilder b = new StringBuilder();
        if (live) b.append("লাইভ");
        if (views > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(live ? formatCountBn(views) + " জন দেখছেন" : formatViewsBn(views));
        }
        String ag = agoBn();
        if (!ag.isEmpty()) {
            if (b.length() > 0) b.append(" · ");
            b.append(live ? "শুরু " + ag : ag);
        }
        return b.toString();
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

    /** turns any 0-9 digits inside a text into Bangla digits */
    public static String formatCountBn(String t) {
        String bn = "০১২৩৪৫৬৭৮৯";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            b.append(c >= '0' && c <= '9' ? bn.charAt(c - '0') : c);
        }
        return b.toString();
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
