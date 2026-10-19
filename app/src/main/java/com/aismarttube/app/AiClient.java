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
            if ("gemini".equals(provider)) {
                text = gemini(model.trim(), key.trim(), system, user, videoUrl, startSec, endSec);
            } else {
                text = openAiCompatible(baseUrl, model.trim(), key.trim(), system, user);
            }
            if (text == null || text.trim().isEmpty()) {
                return err("AI খালি উত্তর দিয়েছে");
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("text", text.trim());
            return o.toString();
        } catch (Throwable e) {
            return err(String.valueOf(e.getMessage()));
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
            c.setReadTimeout(150000); // video understanding can take a while
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
