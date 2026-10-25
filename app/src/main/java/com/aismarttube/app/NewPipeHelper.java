package com.aismarttube.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Two-source wrapper for the WebView UI.
 *   1) NewPipe Extractor (runs on the phone, uses the phone's own IP)
 *   2) Piped public servers   3) Invidious public servers  (automatic fallbacks, order changeable)
 * Every method returns a JSON string: {ok:true,...,source:"newpipe"|"piped"} or {ok:false,error}.
 */
public class NewPipeHelper {

    private static volatile boolean inited = false;

    public static synchronized void ensureInit() {
        if (inited) return;
        try {
            NewPipe.init(new RealDownloader(), new Localization("bn", "IN"));
            inited = true;
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static class RealDownloader extends Downloader {
        static final String USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(request.url());
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(request.httpMethod());
                connection.setConnectTimeout(25000);
                connection.setReadTimeout(25000);
                connection.setInstanceFollowRedirects(true);

                // defaults first; NewPipe's own headers then REPLACE them
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,bn;q=0.8");

                Map<String, List<String>> headers = request.headers();
                if (headers != null) {
                    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                        if (entry.getKey() == null) continue;
                        List<String> values = entry.getValue();
                        if (values == null || values.isEmpty()) continue;
                        connection.setRequestProperty(entry.getKey(), values.get(0));
                        for (int i = 1; i < values.size(); i++) {
                            connection.addRequestProperty(entry.getKey(), values.get(i));
                        }
                    }
                }

                // POST body — YouTube search/player calls need this
                byte[] body = request.dataToSend();
                if (body != null && body.length > 0) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    connection.getOutputStream().write(body);
                    connection.getOutputStream().flush();
                }

                int responseCode = connection.getResponseCode();
                String responseMessage = connection.getResponseMessage();

                if (responseCode == 429) {
                    throw new ReCaptchaException("YouTube থেকে ব্লক (429)", request.url());
                }

                InputStream inputStream = (responseCode >= 400)
                        ? connection.getErrorStream()
                        : connection.getInputStream();
                if (inputStream == null) {
                    return new Response(responseCode, responseMessage,
                            connection.getHeaderFields(), "", request.url());
                }

                String contentEncoding = connection.getContentEncoding();
                if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
                    inputStream = new GZIPInputStream(inputStream);
                }

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = inputStream.read(buf)) != -1) bos.write(buf, 0, r);
                inputStream.close();

                String finalUrl = connection.getURL() != null
                        ? connection.getURL().toString() : request.url();

                return new Response(
                        responseCode,
                        responseMessage,
                        connection.getHeaderFields(),
                        bos.toString("UTF-8"),
                        finalUrl
                );
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ search

    private static volatile String primary = "newpipe";

    /** Which source is tried first: newpipe | piped | invidious (the others follow as fallbacks). */
    public static void setPrimary(String p) {
        if ("newpipe".equals(p) || "piped".equals(p) || "invidious".equals(p)) primary = p;
    }

    private static volatile boolean fallbackOn = true;

    /** false = only the primary source is used (no automatic fallback). */
    public static void setFallback(boolean on) {
        fallbackOn = on;
    }

    private static List<String> order() {
        List<String> l = new ArrayList<>();
        l.add(primary);
        if (!fallbackOn) return l;
        for (String s : new String[]{"newpipe", "piped", "invidious"}) {
            if (!l.contains(s)) l.add(s);
        }
        return l;
    }

    /** Search videos: tries every source in order until one gives results. */
    public static String search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return err("খালি সার্চ");
        }
        final String q = query.trim();
        StringBuilder errs = new StringBuilder();
        for (String src : order()) {
            try {
                JSONObject r;
                if ("newpipe".equals(src)) r = searchNewPipe(q);
                else if ("piped".equals(src)) r = PipedClient.search(q);
                else r = InvidiousClient.search(q);
                if (r.getJSONArray("items").length() > 0) return r.toString();
                errs.append(src).append(": কোনো ফল নেই; ");
            } catch (Throwable e) {
                e.printStackTrace();
                errs.append(src).append(": ").append(e.getMessage()).append("; ");
            }
        }
        return err("সার্চ ব্যর্থ — " + errs);
    }

    private static JSONObject searchNewPipe(String q) throws Exception {
        ensureInit();
        SearchInfo info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.getSearchQHFactory().fromQuery(
                        q, Collections.singletonList("videos"), "")
        );
        JSONArray items = new JSONArray();
        int n = 0;
        for (InfoItem item : info.getRelatedItems()) {
            if (!(item instanceof StreamInfoItem)) continue;
            StreamInfoItem s = (StreamInfoItem) item;
            JSONObject o = new JSONObject();
            o.put("title", s.getName() != null ? s.getName() : "");
            o.put("url", s.getUrl() != null ? s.getUrl() : "");
            o.put("uploader", s.getUploaderName() != null ? s.getUploaderName() : "");
            o.put("uploaderUrl", s.getUploaderUrl() != null ? s.getUploaderUrl() : "");
            putStreamMeta(o, s);
            String thumb = "";
            try {
                if (s.getThumbnails() != null && !s.getThumbnails().isEmpty()) {
                    thumb = s.getThumbnails().get(s.getThumbnails().size() - 1).getUrl();
                }
            } catch (Throwable ignored) {}
            o.put("thumb", thumb);
            items.put(o);
            if (++n >= 30) break;
        }
        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("query", q);
        root.put("items", items);
        root.put("source", "newpipe");
        return root;
    }

    // ----------------------------------------------------------------- streams

    public static String getStreams(String videoUrl) {
        return getStreams(videoUrl, "auto");
    }

    /** only = "auto" (all sources in order) or one of newpipe | piped | invidious. */
    public static String getStreams(String videoUrl, String only) {
        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            return err("লিংক নেই");
        }
        final String u = videoUrl.trim();
        List<String> srcs = (only == null || "auto".equals(only))
                ? order() : Collections.singletonList(only);
        StringBuilder errs = new StringBuilder();
        for (String src : srcs) {
            try {
                JSONObject r;
                if ("newpipe".equals(src)) r = streamsNewPipe(u);
                else if ("piped".equals(src)) r = PipedClient.getStreams(u);
                else if ("invidious".equals(src)) r = InvidiousClient.getStreams(u);
                else continue;
                if (!r.optString("playUrl", "").isEmpty()) return r.toString();
                errs.append(src).append(": প্লে URL নেই; ");
            } catch (Throwable e) {
                e.printStackTrace();
                errs.append(src).append(": ").append(e.getMessage()).append("; ");
            }
        }
        return err("স্ট্রিম আনা যায়নি — " + errs);
    }

    private static JSONObject streamsNewPipe(String videoUrl) throws Exception {
        ensureInit();
        StreamInfo streamInfo = cachedInfo(videoUrl);
        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("source", "newpipe");
        root.put("title", streamInfo.getName());
        root.put("uploader", streamInfo.getUploaderName());
        try { root.put("uploaderUrl", streamInfo.getUploaderUrl() != null ? streamInfo.getUploaderUrl() : ""); } catch (Throwable ignored) {}
        root.put("url", streamInfo.getUrl());
        root.put("views", streamInfo.getViewCount());
        root.put("duration", streamInfo.getDuration());
        try {
            root.put("description", streamInfo.getDescription() != null
                    ? streamInfo.getDescription().getContent() : "");
        } catch (Throwable t) {
            root.put("description", "");
        }

        // progressive = video + audio together (plays directly in <video>)
        JSONArray videos = new JSONArray();
        List<VideoStream> vstreams = streamInfo.getVideoStreams();
        if (vstreams != null) {
            for (VideoStream vs : vstreams) {
                JSONObject o = new JSONObject();
                o.put("url", vs.getContent());
                o.put("quality", vs.getResolution());
                o.put("format", vs.getFormat() != null ? vs.getFormat().getName() : "");
                o.put("hasVideo", true);
                o.put("hasAudio", true);
                videos.put(o);
            }
        }
        List<VideoStream> vonly = streamInfo.getVideoOnlyStreams();
        if (vonly != null) {
            for (VideoStream vs : vonly) {
                JSONObject o = new JSONObject();
                o.put("url", vs.getContent());
                o.put("quality", vs.getResolution());
                o.put("format", vs.getFormat() != null ? vs.getFormat().getName() : "");
                o.put("hasVideo", true);
                o.put("hasAudio", false);
                videos.put(o);
            }
        }
        root.put("videoStreams", videos);

        JSONArray audios = new JSONArray();
        List<AudioStream> astreams = streamInfo.getAudioStreams();
        if (astreams != null) {
            for (AudioStream as : astreams) {
                JSONObject o = new JSONObject();
                o.put("url", as.getContent());
                o.put("bitrate", as.getAverageBitrate());
                o.put("format", as.getFormat() != null ? as.getFormat().getName() : "");
                audios.put(o);
            }
        }
        root.put("audioStreams", audios);

        // 1) progressive video+audio  2) HLS  3) audio only
        String playUrl = "";
        boolean audioOnly = false;
        if (vstreams != null) {
            for (VideoStream vs : vstreams) {
                if (vs.getContent() != null && !vs.getContent().isEmpty()) {
                    playUrl = vs.getContent();
                    break;
                }
            }
        }
        if (playUrl.isEmpty()) {
            try {
                String hls = streamInfo.getHlsUrl();
                if (hls != null && !hls.isEmpty()) playUrl = hls;
            } catch (Throwable ignored) {}
        }
        if (playUrl.isEmpty() && astreams != null && !astreams.isEmpty()) {
            playUrl = astreams.get(0).getContent();
            audioOnly = true;
        }
        root.put("playUrl", playUrl);
        root.put("audioOnly", audioOnly);
        return root;
    }

    /** the video page is fetched once; subtitles / re-open within 5 minutes reuse it (faster, fewer requests) */
    private static final Map<String, Object[]> INFO_CACHE = new HashMap<>();

    /** warm the cache so the player opens faster (called when a card is focused / tapped) */
    public static void prefetch(String url) {
        try { if (url != null && !url.isEmpty()) cachedInfo(url.trim()); } catch (Throwable ignored) {}
    }

    private static synchronized StreamInfo cachedInfo(String url) throws Exception {
        Object[] hit = INFO_CACHE.get(url);
        long now = System.currentTimeMillis();
        if (hit != null && now - (Long) hit[0] < 5 * 60 * 1000L) return (StreamInfo) hit[1];
        StreamInfo si = StreamInfo.getInfo(url);
        if (INFO_CACHE.size() > 12) INFO_CACHE.clear();
        INFO_CACHE.put(url, new Object[]{now, si});
        return si;
    }

    // ----------------------------------------------------------------- channel

    private static String lastImage(List<Image> imgs) {
        try {
            if (imgs != null && !imgs.isEmpty()) {
                String u = imgs.get(imgs.size() - 1).getUrl();
                return u != null ? u : "";
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** Channel header + the list of its tabs (videos / shorts / livestreams / playlists ...). */
    public static String getChannel(String channelUrl) {
        try {
            if (channelUrl == null || channelUrl.trim().isEmpty()) return err("চ্যানেলের লিংক নেই");
            ensureInit();
            ChannelInfo ci = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl.trim());
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("name", ci.getName() != null ? ci.getName() : "");
            o.put("url", ci.getUrl() != null ? ci.getUrl() : channelUrl);
            o.put("avatar", lastImage(ci.getAvatars()));
            o.put("banner", lastImage(ci.getBanners()));
            o.put("subscribers", ci.getSubscriberCount());
            o.put("description", ci.getDescription() != null ? ci.getDescription() : "");
            JSONArray tabs = new JSONArray();
            List<ListLinkHandler> hs = ci.getTabs();
            if (hs != null) {
                for (ListLinkHandler h : hs) {
                    List<String> f = h.getContentFilters();
                    String name = (f != null && !f.isEmpty()) ? f.get(0) : "";
                    JSONObject t = new JSONObject();
                    t.put("name", name);
                    t.put("id", h.getUrl());
                    tabs.put(t);
                    synchronized (TAB_HANDLERS) { TAB_HANDLERS.put(h.getUrl(), h); }
                }
            }
            o.put("tabs", tabs);
            return o.toString();
        } catch (Throwable e) {
            e.printStackTrace();
            return err("চ্যানেল আনা যায়নি: " + e.getMessage());
        }
    }

    private static final Map<String, ListLinkHandler> TAB_HANDLERS = new HashMap<>();
    private static final Map<String, Page> NEXT_PAGES = new HashMap<>();
    private static int tokenSeq = 0;


    private static void putStreamMeta(JSONObject o, StreamInfoItem s) throws Exception {
        long dur = s.getDuration();
        boolean live = false;
        try {
            StreamType st = s.getStreamType();
            live = st == StreamType.LIVE_STREAM
                    || (st != null && st.name() != null && st.name().contains("LIVE"));
        } catch (Throwable ignored) {}
        // Some list items mis-report duration for long lives; UI shows লাইভ instead.
        o.put("isLive", live);
        o.put("duration", live ? 0L : Math.max(0L, dur));
        try {
            o.put("views", Math.max(0L, s.getViewCount()));
        } catch (Throwable e) {
            o.put("views", 0L);
        }
        String uploaded = "";
        try {
            if (s.getTextualUploadDate() != null) {
                uploaded = translateUploadEn(s.getTextualUploadDate().trim());
            }
        } catch (Throwable ignored) {}
        if (uploaded.isEmpty()) {
            try {
                Object ud = s.getUploadDate();
                if (ud != null) {
                    // DateWrapper: try getInstant / date methods across extractor versions
                    java.time.Instant inst = null;
                    try {
                        java.lang.reflect.Method m = ud.getClass().getMethod("offsetDateTime");
                        Object odt = m.invoke(ud);
                        if (odt instanceof java.time.OffsetDateTime)
                            inst = ((java.time.OffsetDateTime) odt).toInstant();
                    } catch (Throwable ignored) {}
                    if (inst == null) {
                        try {
                            java.lang.reflect.Method m = ud.getClass().getMethod("instant");
                            Object o2 = m.invoke(ud);
                            if (o2 instanceof java.time.Instant) inst = (java.time.Instant) o2;
                        } catch (Throwable ignored) {}
                    }
                    if (inst != null) {
                        long sec = java.time.Duration.between(inst, java.time.Instant.now()).getSeconds();
                        uploaded = relativeBn(sec);
                    }
                }
            } catch (Throwable ignored) {}
        }
        o.put("uploaded", uploaded);
    }

    /** Best-effort English "2 days ago" → বাংলা; unknown strings kept as-is. */
    private static String translateUploadEn(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(java.util.Locale.US);
        try {
            if (x.contains("second")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(x);
                if (m.find()) return m.group(1) + " সেকেন্ড আগে";
            }
            if (x.contains("minute")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(x);
                if (m.find()) return m.group(1) + " মিনিট আগে";
            }
            if (x.contains("hour")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(x);
                if (m.find()) return m.group(1) + " ঘণ্টা আগে";
            }
            if (x.contains("day")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(x);
                if (m.find()) return m.group(1) + " দিন আগে";
            }
            if (x.contains("week")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(x);
                if (m.find()) return m.group(1) + " সপ্তাহ আগে";
            }
            if (x.contains("month")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(x);
                if (m.find()) return m.group(1) + " মাস আগে";
            }
            if (x.contains("year")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(x);
                if (m.find()) return m.group(1) + " বছর আগে";
            }
            if (x.contains("streamed") || x.equals("live") || x.contains("premiere")) return "লাইভ";
        } catch (Throwable ignored) {}
        return s.trim();
    }

    private static String relativeBn(long sec) {
        if (sec < 0) sec = 0;
        if (sec < 60) return sec + " সেকেন্ড আগে";
        long m = sec / 60;
        if (m < 60) return m + " মিনিট আগে";
        long h = m / 60;
        if (h < 24) return h + " ঘণ্টা আগে";
        long d = h / 24;
        if (d < 7) return d + " দিন আগে";
        if (d < 30) return (d / 7) + " সপ্তাহ আগে";
        if (d < 365) return (d / 30) + " মাস আগে";
        return (d / 365) + " বছর আগে";
    }

    private static JSONObject itemJson(InfoItem item) throws Exception {
        JSONObject o = new JSONObject();
        o.put("title", item.getName() != null ? item.getName() : "");
        o.put("url", item.getUrl() != null ? item.getUrl() : "");
        o.put("thumb", lastImage(item.getThumbnails()));
        if (item instanceof StreamInfoItem) {
            StreamInfoItem s = (StreamInfoItem) item;
            o.put("kind", "video");
            o.put("uploader", s.getUploaderName() != null ? s.getUploaderName() : "");
            o.put("uploaderUrl", s.getUploaderUrl() != null ? s.getUploaderUrl() : "");
            putStreamMeta(o, s);
        } else if (item instanceof PlaylistInfoItem) {
            o.put("kind", "playlist");
            o.put("count", ((PlaylistInfoItem) item).getStreamCount());
        } else {
            o.put("kind", "other");
        }
        return o;
    }

    private static JSONArray itemsJson(List<? extends InfoItem> list) throws Exception {
        JSONArray a = new JSONArray();
        if (list != null) {
            for (InfoItem it : list) {
                JSONObject j = itemJson(it);
                if (!"other".equals(j.getString("kind"))) a.put(j);
            }
        }
        return a;
    }

    private static String remember(Page next) {
        if (next == null) return "";
        synchronized (NEXT_PAGES) {
            if (NEXT_PAGES.size() > 40) NEXT_PAGES.clear();
            String tok = "p" + (++tokenSeq);
            NEXT_PAGES.put(tok, next);
            return tok;
        }
    }

    /** First page of one channel tab. tabId comes from getChannel(). */
    public static String getChannelTab(String tabId) {
        try {
            ListLinkHandler h;
            synchronized (TAB_HANDLERS) { h = TAB_HANDLERS.get(tabId); }
            if (h == null) return err("ট্যাব পাওয়া যায়নি, চ্যানেলটা আবার খুলুন");
            ensureInit();
            ChannelTabInfo ti = ChannelTabInfo.getInfo(ServiceList.YouTube, h);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("items", itemsJson(ti.getRelatedItems()));
            o.put("next", ti.hasNextPage() ? remember(ti.getNextPage()) : "");
            return o.toString();
        } catch (Throwable e) {
            e.printStackTrace();
            return err("এই ট্যাব আনা যায়নি: " + e.getMessage());
        }
    }

    /** Next page of a channel tab (token from a previous result). */
    public static String getChannelMore(String tabId, String token) {
        try {
            ListLinkHandler h;
            Page pg;
            synchronized (TAB_HANDLERS) { h = TAB_HANDLERS.get(tabId); }
            synchronized (NEXT_PAGES) { pg = NEXT_PAGES.get(token); }
            if (h == null || pg == null) return err("আর পাওয়া যায়নি");
            ensureInit();
            ListExtractor.InfoItemsPage<InfoItem> page = ChannelTabInfo.getMoreItems(ServiceList.YouTube, h, pg);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("items", itemsJson(page.getItems()));
            o.put("next", page.hasNextPage() ? remember(page.getNextPage()) : "");
            return o.toString();
        } catch (Throwable e) {
            e.printStackTrace();
            return err("আরও আনা যায়নি: " + e.getMessage());
        }
    }

    /** Videos of a playlist (first page). */
    public static String getPlaylist(String playlistUrl) {
        try {
            ensureInit();
            PlaylistInfo pi = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("name", pi.getName() != null ? pi.getName() : "");
            o.put("items", itemsJson(pi.getRelatedItems()));
            return o.toString();
        } catch (Throwable e) {
            e.printStackTrace();
            return err("প্লেলিস্ট আনা যায়নি: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- transcript

    /** Subtitle text of a video (so Grok / other AIs can "read" what is said). Bangla > English > Hindi > any. */
    public static String getTranscript(String videoUrl) {
        try {
            if (videoUrl == null || videoUrl.trim().isEmpty()) return err("লিংক নেই");
            ensureInit();
            StreamInfo info = cachedInfo(videoUrl.trim());
            List<SubtitlesStream> subs = info.getSubtitles();
            if (subs == null || subs.isEmpty()) return err("এই ভিডিওতে সাবটাইটেল নেই");
            SubtitlesStream pick = null;
            for (String want : new String[]{"bn", "en", "hi"}) {
                for (SubtitlesStream s : subs) {
                    String tag = s.getLanguageTag();
                    if (tag != null && tag.toLowerCase().startsWith(want)) { pick = s; break; }
                }
                if (pick != null) break;
            }
            if (pick == null) pick = subs.get(0);
            String raw = fetchText(pick.getContent());
            String text = TextClean.fromSubtitle(raw, 14000);
            if (text.isEmpty()) return err("সাবটাইটেল পড়া যায়নি");
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("text", text);
            o.put("lang", pick.getLanguageTag());
            return o.toString();
        } catch (Throwable e) {
            e.printStackTrace();
            return err("সাবটাইটেল আনা যায়নি: " + e.getMessage());
        }
    }

    private static String fetchText(String u) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", RealDownloader.USER_AGENT);
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            String enc = c.getContentEncoding();
            if (enc != null && enc.equalsIgnoreCase("gzip")) in = new GZIPInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            in.close();
            return bos.toString("UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String getVideoInfo(String videoUrl) {
        ensureInit();
        try {
            StreamInfo streamInfo = StreamInfo.getInfo(videoUrl);
            StringBuilder info = new StringBuilder();
            info.append("শিরোনাম: ").append(streamInfo.getName()).append("\n\n");
            info.append("আপলোডার: ").append(streamInfo.getUploaderName()).append("\n\n");
            info.append("ভিউ: ").append(streamInfo.getViewCount()).append("\n");
            return info.toString();
        } catch (Exception e) {
            return "ত্রুটি হয়েছে: " + e.getMessage();
        }
    }

    private static String err(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"error\"}";
        }
    }
}
