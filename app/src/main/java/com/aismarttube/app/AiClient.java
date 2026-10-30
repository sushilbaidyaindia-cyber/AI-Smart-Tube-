package com.aismarttube.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Calls AI providers from the phone (no browser CORS problems).
 *  - provider "gemini": Google Gemini generateContent API (can also watch a public YouTube video)
 *  - anything else    : OpenAI-compatible /chat/completions (Grok/xAI, OpenRouter, Groq, DeepSeek ...)
 * API keys come from the app settings on every call; they are never logged.
 */
public class AiClient {

    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    /** IOException that remembers the HTTP status, so callers can retry with different parameters. */
    static class HttpError extends IOException {
        final int code;
        HttpError(int code, String msg) { super(msg); this.code = code; }
    }

    /**
     * Returns {ok:true,text:"..."} or {ok:false,error:"..."}.
     * videoUrl: when not empty and provider is gemini, Gemini watches that public YouTube video itself.
     */
    public static String call(String provider, String baseUrl, String model, String key,
                              String system, String user, String videoUrl) {
        return call(provider, baseUrl, model, key, system, user, videoUrl, -1, -1);
    }

    /** startSec/endSec (>=0): Gemini looks at only that part of the video — saves the free quota. */
    public static String call(String provider, String baseUrl, String model, String key,
                              String system, String user, String videoUrl, int startSec, int endSec) {
        try {
            if (key == null || key.trim().isEmpty()) {
                return err("API key দেওয়া হয়নি — সেটিংসে AI অংশে দিন");
            }
            if (model == null || model.trim().isEmpty()) {
                return err("মডেলের নাম দেওয়া হয়নি — সেটিংসে দিন");
            }
            String text;
            String usedModel = model.trim();
            int keyUsed = 0, keyTotal = 1;
            if ("gemini".equals(provider)) {
                String[] chain = geminiChain(model.trim());
                String[] keys = splitKeys(key);
                if (keys.length == 0) return err("API key দেওয়া হয়নি — সেটিংসে AI অংশে দিন");
                keyTotal = keys.length;
                GState st = new GState();
                st.video = videoUrl != null && videoUrl.startsWith("https://");
                st.multi = keys.length > 1;
                final long t0 = System.currentTimeMillis();
                text = null;
                for (int i = 0; i < chain.length && text == null; i++) {
                    if (System.currentTimeMillis() - t0 > 200000) break; // do not make the user wait forever
                    text = tryModel(chain[i], keys, system, user, videoUrl, startSec, endSec, st);
                    if (text != null) { usedModel = chain[i]; keyUsed = st.keyIdx; }
                }
                if (text == null && st.last == null && st.lastNet != null) throw st.lastNet;
                if (text == null) {
                    String m = st.firstErr != null ? st.firstErr : st.last == null ? "AI সার্ভার সাড়া দেয়নি" : st.last.getMessage();
                    return err(m + "\n\nপ্রতিটি চেষ্টার ফল:" + st.detail);
                }
            } else {
                text = openAiCompatible(baseUrl, model.trim(), key.trim(), system, user);
            }
            if (text == null || text.trim().isEmpty()) {
                return err("AI খালি উত্তর দিয়েছে");
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("text", text.trim());
            o.put("model", usedModel);
            o.put("key", keyUsed);
            o.put("keys", keyTotal);
            return o.toString();
        } catch (Throwable e) {
            return err(friendly(e));
        }
    }

    // ------------------------------------------------------------ several Gemini keys

    private static final class GState {
        final StringBuilder detail = new StringBuilder();
        String firstErr;
        HttpError last;
        IOException lastNet;
        int keyIdx = 1;
        boolean video, multi;
    }

    private static final java.util.Map<String, Long> COOL = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String stickyKey = "";

    /** one key per line (or separated by spaces / commas) */
    static String[] splitKeys(String raw) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (raw != null) for (String p : raw.split("[\\s,;]+")) if (p.length() > 8) set.add(p.trim());
        String[] out = set.toArray(new String[0]);
        return out.length > 10 ? java.util.Arrays.copyOf(out, 10) : out;
    }

    /** the key that worked last goes first; keys that just ran out of quota go last */
    private static String[] orderKeys(String[] keys, String model) {
        java.util.List<String> ok = new java.util.ArrayList<>(), cool = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (String k : keys) {
            Long until = COOL.get(model + "|" + k);
            (until != null && until > now ? cool : ok).add(k);
        }
        int si = ok.indexOf(stickyKey);
        if (si > 0) ok.add(0, ok.remove(si));
        ok.addAll(cool);
        return ok.toArray(new String[0]);
    }

    private static void cool(String key, String model, String msg) {
        String m = msg == null ? "" : msg.toLowerCase(java.util.Locale.ROOT);
        boolean daily = m.contains("per day") || m.contains("perday") || m.contains("daily");
        COOL.put(model + "|" + key, System.currentTimeMillis() + (daily ? 30 * 60 * 1000L : 75 * 1000L));
    }

    private static int indexOf(String[] a, String k) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(k)) return i;
        return 0;
    }

    private static void pause(long ms) throws IOException {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("বাতিল"); }
    }

    /**
     * One model, all keys. Busy server (503): other keys will not help, so give up this model.
     * Quota used up (429) or key refused: the next key. Returns null = try the next model.
     */
    private static String tryModel(String model, String[] keys, String system, String user, String videoUrl,
                                   int startSec, int endSec, GState st) throws Exception {
        for (String k : orderKeys(keys, model)) {
            int ki = indexOf(keys, k) + 1;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String t = gemini(model, k, system, user, videoUrl, startSec, endSec);
                    stickyKey = k;
                    st.keyIdx = ki;
                    return t;
                } catch (HttpError e) {
                    st.last = e;
                    int c = e.code;
                    boolean busy = c == 503 || c == 500 || c == 502 || c == 504;
                    if (attempt == 1 || !busy) {
                        String em = String.valueOf(e.getMessage());
                        int q = em.indexOf(": ");
                        String shortMsg = q >= 0 ? em.substring(q + 2) : em;
                        if (shortMsg.length() > 90) shortMsg = shortMsg.substring(0, 90) + "…";
                        st.detail.append("\n• ").append(model).append(st.multi ? " [key " + ki + "]" : "").append(" → ").append(c).append(": ").append(shortMsg);
                    }
                    if (c != 404 || st.firstErr == null) {
                        if (c != 404 || attempt == 0) st.firstErr = pickErr(st.firstErr, e, model + (st.multi ? " key " + ki : ""));
                    }
                    if (busy) {
                        if (attempt == 0) { pause(1500); continue; }
                        return null;
                    }
                    if (c == 404) return null;
                    if (c == 429) { cool(k, model, e.getMessage()); break; }          // next key
                    if (c == 400 || c == 401 || c == 403) {
                        String em = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                        boolean keyProblem = c != 400 || em.contains("api key");
                        if (keyProblem && keys.length > 1) break;                      // next key
                    }
                    throw e;
                } catch (java.net.SocketTimeoutException e) {
                    st.lastNet = e;
                    if (st.video) throw new IOException("TIMEOUT_VIDEO");   // another model will not be faster
                    return null;
                } catch (IOException e) {
                    st.lastNet = e;
                    if (e.getMessage() != null && e.getMessage().startsWith("Gemini")) throw e;
                    if (attempt == 0) { pause(1200); continue; }
                    return null;
                }
            }
        }
        return null;
    }

    /** keeps the most useful error: a real error (busy, quota, key) beats "model not found" */
    private static String pickErr(String old, HttpError e, String model) {
        String now = "(" + model + ") " + e.getMessage();
        if (old == null) return now;
        if (old.contains("HTTP 404") && e.code != 404) return now;
        return old;
    }

    private static String friendly(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if ("TIMEOUT_VIDEO".equals(m)) {
            return "Gemini ভিডিও দেখতে অনেক সময় নিচ্ছে। সেটিংসে \"দৃশ্য বর্ণনার সময়\" ছোট করুন (১ মিনিট) বা পরে আবার চেষ্টা করুন";
        }
        if (e instanceof java.net.SocketTimeoutException || m.toLowerCase().contains("timed out") || m.equalsIgnoreCase("timeout")) {
            return "সার্ভার সময়মতো উত্তর দেয়নি। ইন্টারনেট ঠিক আছে কিনা দেখে আবার চেষ্টা করুন";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "ইন্টারনেট সংযোগ পাওয়া যায়নি (DNS)। নেট বা VPN দেখুন";
        }
        if (m.contains("connection abort") || m.contains("Connection reset") || m.contains("unexpected end of stream")
                || m.contains("Broken pipe") || e instanceof javax.net.ssl.SSLException) {
            return "নেটওয়ার্ক সংযোগ মাঝপথে কেটে গেছে (" + m + ")। ওয়াইফাই/ডেটা বদলে বা VPN, ডেটা-সেভার বন্ধ করে আবার চেষ্টা করুন";
        }
        return m;
    }

    /** The model chosen in settings first, then other Gemini models as backup (no duplicates). */
    private static String[] geminiChain(String chosen) {
        // "-latest" aliases never get retired; the named ones are tried first
        String[] backups = {
                "gemini-3.8-flash", "gemini-flash-latest", "gemini-4-argon", "gemini-flash-lite-latest"
        };
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(chosen);
        for (String b : backups) set.add(b);
        return set.toArray(new String[0]);
    }

    /** Asks Google which models this key can use. Returns {ok:true,models:["gemini-...",...]} or {ok:false,error:"..."} */
    public static String listGeminiModels(String key) {
        HttpURLConnection c = null;
        try {
            String[] ks = splitKeys(key);
            if (ks.length == 0) return err("আগে Gemini API key দিন");
            key = ks[0];
            JSONArray out = new JSONArray();
            String token = "";
            for (int page = 0; page < 6; page++) {
                String u = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
                        + (token.isEmpty() ? "" : "&pageToken=" + java.net.URLEncoder.encode(token, "UTF-8"));
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setUseCaches(false);
                c.setRequestProperty("x-goog-api-key", key.trim());
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = in == null ? "" : readAll(in);
                if (code >= 400) return err(describeError(code, body));
                JSONObject root = new JSONObject(body);
                JSONArray ms = root.optJSONArray("models");
                if (ms != null) {
                    for (int i = 0; i < ms.length(); i++) {
                        JSONObject m = ms.getJSONObject(i);
                        JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                        boolean gen = false;
                        if (methods != null) for (int k = 0; k < methods.length(); k++) if ("generateContent".equals(methods.optString(k, ""))) gen = true;
                        if (!gen) continue;
                        String name = m.optString("name", "");
                        if (name.startsWith("models/")) name = name.substring(7);
                        if (name.isEmpty()) continue;
                        out.put(name);
                    }
                }
                token = root.optString("nextPageToken", "");
                c.disconnect();
                c = null;
                if (token.isEmpty()) break;
            }
            return new JSONObject().put("ok", true).put("models", out).toString();
        } catch (Throwable e) {
            return err(friendly(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String gemini(String model, String key, String system, String user,
                                 String videoUrl, int startSec, int endSec) throws Exception {
        JSONObject body = new JSONObject();
        if (system != null && !system.isEmpty()) {
            body.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", system))));
        }
        JSONArray parts = new JSONArray();
        if (videoUrl != null && videoUrl.startsWith("https://")) {
            // Gemini fetches and watches the public YouTube video itself (picture + sound)
            JSONObject vp = new JSONObject().put("file_data",
                    new JSONObject().put("file_uri", videoUrl));
            if (startSec >= 0 && endSec > startSec) {
                vp.put("video_metadata", new JSONObject()
                        .put("start_offset", startSec + "s")
                        .put("end_offset", endSec + "s"));
            }
            parts.put(vp);
        }
        parts.put(new JSONObject().put("text", user));
        body.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", parts)));

        String resp = post(GEMINI_BASE + model + ":generateContent", body.toString(),
                "x-goog-api-key", key);
        JSONObject root = new JSONObject(resp);
        JSONArray cands = root.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            JSONObject fb = root.optJSONObject("promptFeedback");
            if (fb != null && fb.has("blockReason")) {
                throw new IOException("Gemini উত্তর আটকে দিয়েছে: " + fb.optString("blockReason"));
            }
            throw new IOException("Gemini কোনো উত্তর দেয়নি");
        }
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        if (content == null) throw new IOException("Gemini উত্তরে লেখা নেই");
        JSONArray outParts = content.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        if (outParts != null) {
            for (int i = 0; i < outParts.length(); i++) {
                sb.append(outParts.getJSONObject(i).optString("text", ""));
            }
        }
        return sb.toString();
    }

    private static String openAiCompatible(String baseUrl, String model, String key,
                                           String system, String user) throws Exception {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (!base.startsWith("https://")) {
            throw new IOException("Base URL অবশ্যই https:// দিয়ে শুরু হতে হবে");
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";

        JSONArray msgs = new JSONArray();
        if (system != null && !system.isEmpty()) {
            msgs.put(new JSONObject().put("role", "system").put("content", system));
        }
        msgs.put(new JSONObject().put("role", "user").put("content", user));

        // Grok reasons a lot by default (slow). Ask for low effort; if the server rejects the
        // parameter, silently retry without it.
        boolean xai = base.contains("api.x.ai");
        String resp;
        try {
            JSONObject body = new JSONObject().put("model", model).put("messages", msgs);
            if (xai) body.put("reasoning_effort", "low");
            resp = post(url, body.toString(), "Authorization", "Bearer " + key);
        } catch (HttpError e) {
            if (xai && (e.code == 400 || e.code == 422)) {
                JSONObject body = new JSONObject().put("model", model).put("messages", msgs);
                resp = post(url, body.toString(), "Authorization", "Bearer " + key);
            } else {
                throw e;
            }
        }
        JSONObject root = new JSONObject(resp);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("AI কোনো উত্তর দেয়নি");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return msg == null ? "" : msg.optString("content", "");
    }

    private static String post(String url, String json, String authHeader, String authValue) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(json.contains("file_uri") ? 180000 : 70000); // video understanding can take a while
            c.setUseCaches(false);
            c.setRequestProperty("Connection", "close"); // fresh connection each time: no stale keep-alive reuse
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty(authHeader, authValue);
            byte[] data = json.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code >= 400) throw new HttpError(code, describeError(code, body));
            return body;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String describeError(int code, String body) {
        String msg = "";
        try {
            JSONObject o = new JSONObject(body);
            Object e = o.opt("error");
            if (e instanceof JSONObject) msg = ((JSONObject) e).optString("message", "");
            else if (e != null) msg = String.valueOf(e);
        } catch (Throwable ignored) {}
        if (msg.isEmpty()) msg = body.length() > 200 ? body.substring(0, 200) : body;
        String hint = "";
        if (code == 400 || code == 404) hint = " (মডেলের নাম বা অনুরোধ ঠিক আছে কিনা দেখুন)";
        else if (code == 401 || code == 403) hint = " (API key ঠিক আছে কিনা দেখুন)";
        else if (code == 429) hint = " (লিমিট শেষ, একটু পরে চেষ্টা করুন)";
        return "AI সার্ভার HTTP " + code + ": " + msg + hint;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        in.close();
        return bos.toString("UTF-8");
    }

    private static String err(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"error\"}";
        }
    }
}
