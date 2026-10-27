package com.aismarttube.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native AI page for one video: summary, clickbait check, translation, scene description, questions. */
public class AiActivity extends AppCompatActivity {

    static final String EXTRA_URL = "ai_url", EXTRA_TITLE = "ai_title", EXTRA_UPLOADER = "ai_uploader",
            EXTRA_DUR = "ai_dur", EXTRA_POS = "ai_pos";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, String> cache = new HashMap<>();

    private String vUrl = "", vTitle = "", vUploader = "", vDesc = null, vTr = null;
    private long vDur = 0;
    private int vPos = 0;
    private boolean busy = false;
    private String lastText = "";
    private int token = 0;

    private TextView outHead, outText, nextBtnHolder;
    private LinearLayout actions;
    private EditText askBox;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    static void start(Context c, String url, String title, String uploader, long dur, int posSec) {
        c.startActivity(new Intent(c, AiActivity.class)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_UPLOADER, uploader)
                .putExtra(EXTRA_DUR, dur).putExtra(EXTRA_POS, posSec));
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        vUrl = nz(in.getStringExtra(EXTRA_URL));
        vTitle = nz(in.getStringExtra(EXTRA_TITLE));
        vUploader = nz(in.getStringExtra(EXTRA_UPLOADER));
        vDur = in.getLongExtra(EXTRA_DUR, 0);
        vPos = in.getIntExtra(EXTRA_POS, 0);
        setTitle("AI সহায়ক");

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0B1A1F);
        sv.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(box);
        setContentView(sv);

        TextView h = text("AI সহায়ক", 24, 0xFFEAF5F3, true);
        ViewCompat.setAccessibilityHeading(h, true);
        box.addView(h);
        box.addView(text(vTitle.isEmpty() ? "ভিডিও" : vTitle, 16, 0xFFA4BCC0, false));

        box.addView(btn("সারাংশ", () -> run("sum", null)));
        box.addView(btn("ক্লিকবেইট পরীক্ষা", () -> run("fact", null)));
        box.addView(btn("অনুবাদ", () -> run("tr", null)));
        box.addView(btn("দৃশ্যের বর্ণনা (এখন যেখানে আছেন সেখান থেকে)", () -> run("scene", null)));

        askBox = new EditText(this);
        askBox.setHint("ভিডিও নিয়ে প্রশ্ন লিখুন");
        askBox.setTextColor(0xFFEAF5F3);
        askBox.setHintTextColor(0xFF7F9A9E);
        askBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        askBox.setMinHeight(dp(56));
        box.addView(askBox);
        box.addView(btn("প্রশ্ন করুন", () -> run("ask", null)));

        outHead = text("", 20, 0xFFFFB84A, true);
        ViewCompat.setAccessibilityHeading(outHead, true);
        outHead.setFocusable(true);
        outHead.setPadding(0, dp(20), 0, dp(4));
        outHead.setVisibility(View.GONE);
        box.addView(outHead);
        outText = text("", 17, 0xFFEAF5F3, false);
        outText.setTextIsSelectable(true);
        box.addView(outText);
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        box.addView(actions);

        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                try { tts.setLanguage(new Locale("bn", "IN")); } catch (Throwable ignored) { }
                ttsReady = true;
            }
        });
    }

    @Override
    protected void onDestroy() {
        token++;
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) { }
        io.shutdownNow();
        super.onDestroy();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private Button btn(String t, Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setMinHeight(dp(52));
        b.setBackgroundResource(R.drawable.pill_bg);
        b.setTextColor(0xFFEAF5F3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(x -> r.run());
        return b;
    }

    // ------------------------------------------------------------------ provider settings

    private static final String[] LABEL = {"sum", "fact", "tr", "scene", "ask"};

    private static String kindLabel(String k) {
        switch (k) {
            case "sum": return "সারাংশ";
            case "fact": return "ক্লিকবেইট পরীক্ষা";
            case "tr": return "অনুবাদ";
            case "scene": return "দৃশ্যের বর্ণনা";
            default: return "উত্তর";
        }
    }

    static String provLabel(String p) {
        return "gemini".equals(p) ? "Gemini" : "grok".equals(p) ? "Grok" : "অন্য AI";
    }

    /** returns {provider, baseUrl, model, key} for a name: gemini | grok | custom */
    static String[] provCfg(Context c, String name) {
        if ("gemini".equals(name)) {
            return new String[]{"gemini", "", Store.settingStr(c, "gemModel", "gemini-3.8-flash"), Store.settingStr(c, "gemKey", "")};
        }
        if ("grok".equals(name)) {
            return new String[]{"openai", "https://api.x.ai/v1", Store.settingStr(c, "grokModel", "grok-4.7"), Store.settingStr(c, "grokKey", "")};
        }
        return new String[]{"openai", Store.settingStr(c, "cusBase", ""), Store.settingStr(c, "cusModel", ""), Store.settingStr(c, "cusKey", "")};
    }

    private String system() {
        String lang = Store.settingStr(this, "aiLang", "বাংলা");
        return "তুমি AI Smart Tube অ্যাপের সহায়ক। সবসময় " + lang + " ভাষায় উত্তর দাও। "
                + "উত্তর ছোট, স্পষ্ট ও সহজ রাখো। স্ক্রিন-রিডার ব্যবহারকারীরা শুনে পড়বেন, তাই টেবিল, ইমোজি ও অপ্রয়োজনীয় চিহ্ন এড়াও, পয়েন্ট লিখলে প্রতিটি আলাদা লাইনে \"- \" দিয়ে লেখো। "
                + "যা জানো না তা বানিয়ে বোলো না।";
    }

    private static String bn(Object n) {
        String d = "০১২৩৪৫৬৭৮৯", t = String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        for (char ch : t.toCharArray()) sb.append(ch >= '0' && ch <= '9' ? d.charAt(ch - '0') : ch);
        return sb.toString();
    }

    private static String fmt(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        String r = h > 0 ? h + ":" + two(m) + ":" + two(s) : two(m) + ":" + two(s);
        return bn(r);
    }

    private static String two(long n) { return n < 10 ? "0" + n : String.valueOf(n); }

    private String context(String transcript) {
        String d = vDesc == null ? "" : vDesc;
        if (d.length() > 3000) d = d.substring(0, 3000);
        String s = "শিরোনাম: " + vTitle + "\nচ্যানেল: " + vUploader + "\nসময়কাল: " + (vDur > 0 ? fmt(vDur) : "অজানা")
                + "\nবিবরণ:\n" + (d.isEmpty() ? "(বিবরণ পাওয়া যায়নি)" : d);
        if (transcript != null && !transcript.isEmpty()) s += "\n\nসাবটাইটেল (ভিডিওতে যা বলা হয়েছে):\n" + transcript;
        return s;
    }

    private String prompt(String kind, String q, String transcript, boolean watch, int ws, int we) {
        String ctx = context(transcript);
        boolean hasTr = transcript != null && !transcript.isEmpty();
        String note = watch ? "তুমি ভিডিওটি নিজে দেখতে ও শুনতে পাচ্ছ। "
                : ("তুমি ভিডিওটি দেখোনি; শুধু নিচের তথ্য জানো" + (hasTr ? " (সাবটাইটেলসহ)" : "") + "। ");
        String lang = Store.settingStr(this, "aiLang", "বাংলা");
        switch (kind) {
            case "sum":
                return note + "ভিডিওটির সারাংশ ঠিক " + bn(Store.settingStr(this, "sumLen", "5"))
                        + "টি পয়েন্টে লেখো। প্রথমে এক লাইনে বলো ভিডিওটি কী নিয়ে।\n\n" + ctx;
            case "fact":
                return note + "এই ভিডিওর শিরোনাম কতটা ক্লিকবেইট বা অতিরঞ্জিত, তা যাচাই করো। শিরোনাম ও আসল বিষয়বস্তু মেলে কিনা দেখো। "
                        + "শুধু একটি JSON অবজেক্ট দাও, আর কিছু নয় (কোনো কোডব্লক নয়): {\"score\": ০ থেকে ১০০ পূর্ণসংখ্যা (০ = পুরো বিশ্বাসযোগ্য, ১০০ = পুরো ক্লিকবেইট), "
                        + "\"reasons\": [সর্বোচ্চ ৩টি ছোট কারণ], \"check\": [আলাদা করে যাচাই করা দরকার এমন দাবি, সর্বোচ্চ ৩টি, না থাকলে ফাঁকা], \"better_title\": \"আরও সৎ বিকল্প শিরোনাম\"}. "
                        + "score ইংরেজি সংখ্যায় দাও, বাকি লেখা " + lang + " ভাষায়। তুমি ইন্টারনেটে যাচাই করতে পারছ না, তাই কোনো দাবি নিশ্চিত সত্য বা মিথ্যা বোলো না।\n\n" + ctx;
            case "tr":
                return "নিচের শিরোনাম ও বিবরণ" + (hasTr ? " এবং সাবটাইটেলের মূল অংশ" : "") + " " + lang + " ভাষায় অনুবাদ করো। প্রথমে \"শিরোনাম:\", তারপর \"বিবরণ:\""
                        + (hasTr ? ", তারপর \"সাবটাইটেল:\"" : "") + " শিরোনাম দিয়ে আলাদা করো। শুধু অনুবাদ দাও।\n\n" + ctx;
            case "scene":
                return "তুমি ভিডিওর একটি ছোট অংশ দেখতে ও শুনতে পাচ্ছ। এই অংশটি মূল ভিডিওর " + fmt(ws) + " থেকে " + fmt(we)
                        + " পর্যন্ত। শুধু এই অংশের দৃশ্যগুলো সময় অনুসারে বর্ণনা করো, এমনভাবে যেন যিনি দেখতে পান না তিনি বুঝতে পারেন। "
                        + "প্রতিটি দৃশ্যের শুরুতে এই অংশের শুরু (০০:০০) থেকে হিসাব করে সময় লেখো, যেমন ০০:৪৫। "
                        + "বলো: কে বা কী দেখা যাচ্ছে, জায়গা, পোশাক ও রং, নড়াচড়া, পর্দায় যা লেখা আছে। কথাবার্তা আলাদা করে লেখার দরকার নেই। যা স্পষ্ট বোঝা যায় না, তা অনুমান করে বলবে না।\n\n" + ctx;
            default:
                return note + "ভিডিওটি সম্পর্কে প্রশ্নের উত্তর দাও। উত্তর ভিডিওর তথ্যে না পেলে সেটা পরিষ্কার জানাও।\n\n" + ctx + "\n\nপ্রশ্ন: " + q;
        }
    }

    private boolean isYoutube() {
        return vUrl.contains("youtube.com") || vUrl.contains("youtu.be");
    }

    // ------------------------------------------------------------------ run

    private void run(final String kind, Integer startOverride) {
        if (vUrl.isEmpty()) { toast("ভিডিওর লিংক নেই"); return; }
        String q = "";
        if ("ask".equals(kind)) {
            q = askBox.getText().toString().trim();
            if (q.isEmpty()) { toast("প্রশ্ন লিখুন"); askBox.requestFocus(); return; }
        }
        final String pname = "scene".equals(kind) ? "gemini"
                : Store.settingStr(this, "prov" + ("sum".equals(kind) ? "Sum" : "fact".equals(kind) ? "Fact" : "tr".equals(kind) ? "Tr" : "Ask"), "gemini");
        final String[] cfg = provCfg(this, pname);
        if (cfg[3].trim().isEmpty()) {
            new AlertDialog.Builder(this).setTitle(provLabel(pname) + "-এর API key নেই")
                    .setMessage("সেটিংসে AI অংশে key দিন।")
                    .setPositiveButton("সেটিংস", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("থাক", null).show();
            return;
        }
        final boolean watch = "gemini".equals(pname) && isYoutube()
                && ("scene".equals(kind) || (Store.settingBool(this, "aiWatch", true)
                && ("sum".equals(kind) || "fact".equals(kind) || "ask".equals(kind))));
        if ("scene".equals(kind) && !isYoutube()) { toast("দৃশ্যের বর্ণনা শুধু ইউটিউব ভিডিওতে চলে"); return; }
        int ws = -1, we = -1;
        if ("scene".equals(kind)) {
            int s0 = startOverride != null ? startOverride : vPos;
            int len = 60 * Math.max(1, parseInt(Store.settingStr(this, "sceneLen", "5"), 5));
            int dur = (int) vDur;
            if (dur > 0 && s0 >= dur - 3) { toast("ভিডিও প্রায় শেষ, বর্ণনা করার মতো অংশ নেই"); return; }
            ws = s0;
            we = dur > 0 ? Math.min(dur, s0 + len) : s0 + len;
        }
        final int fws = ws, fwe = we;
        final String fq = q;
        final String ck = vUrl + "|" + kind + "|" + q + "|" + cfg[2] + "|" + Store.settingStr(this, "aiLang", "") + "|"
                + Store.settingStr(this, "sumLen", "") + "|" + watch + "|" + ws + "-" + we;
        showStart(kind, ws, we);
        if (cache.containsKey(ck)) { showDone(kind, cache.get(ck), ws, we); return; }
        if (busy) { toast("আগের অনুরোধ চলছে, একটু অপেক্ষা করুন"); return; }
        if (watch && !"scene".equals(kind) && vDur > 1800) {
            new AlertDialog.Builder(this).setTitle("ভিডিওটি বড়")
                    .setMessage("ভিডিও ৩০ মিনিটের বেশি লম্বা। Gemini-র দেখতে সময় ও আপনার ফ্রি সীমা বেশি লাগতে পারে। চালাবেন?")
                    .setPositiveButton("চালান", (d, w) -> go(kind, pname, cfg, watch, fq, fws, fwe, ck))
                    .setNegativeButton("থাক", (d, w) -> setText("বাতিল করা হয়েছে"))
                    .show();
            return;
        }
        go(kind, pname, cfg, watch, fq, fws, fwe, ck);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    private void go(final String kind, final String pname, final String[] cfg, final boolean watch, final String q,
                    final int ws, final int we, final String ck) {
        busy = true;
        final int myToken = ++token;
        setText(provLabel(pname) + (watch ? ("scene".equals(kind) ? " ভিডিওর " + fmt(ws) + " থেকে " + fmt(we) + " অংশ দেখছে…" : " ভিডিও দেখছে…") : " ভাবছে…"));
        io.execute(() -> {
            try {
                ensureInfo();
                String transcript = "";
                if (!watch && Store.settingBool(this, "useTranscript", true) && !"scene".equals(kind)) {
                    post(myToken, () -> setText("সাবটাইটেল খোঁজা হচ্ছে…"));
                    transcript = ensureTranscript();
                    post(myToken, () -> setText(provLabel(pname) + " ভাবছে…"));
                }
                String raw = AiClient.call(cfg[0], cfg[1], cfg[2], cfg[3], system(),
                        prompt(kind, q, transcript, watch, ws, we), watch ? vUrl : "", ws, we);
                JSONObject d = new JSONObject(raw);
                final boolean ok = d.optBoolean("ok", false);
                final String body = ok ? d.optString("text", "") : d.optString("error", "AI উত্তর দেয়নি");
                final String used = d.optString("model", "");
                ui.post(() -> {
                    busy = false;
                    if (myToken != token) return;
                    if (!ok) { setText("ত্রুটি: " + body); return; }
                    cache.put(ck, body);
                    showDone(kind, body, ws, we);
                    if (Store.settingBool(this, "aiSpeak", false)) speak(lastText);
                });
            } catch (Throwable t) {
                ui.post(() -> { busy = false; setText("ত্রুটি: " + t.getMessage()); });
            }
        });
    }

    private void post(int myToken, Runnable r) {
        ui.post(() -> { if (myToken == token) r.run(); });
    }

    private synchronized void ensureInfo() {
        if (vDesc != null) return;
        try {
            JSONObject d = new JSONObject(NewPipeHelper.getStreams(vUrl));
            String s = d.optString("description", "");
            vDesc = s.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                    .replaceAll("&quot;", "\"").replaceAll("&#39;", "'").replaceAll("[ \\t]+", " ").trim();
            if (vDur <= 0) vDur = d.optLong("duration", 0);
        } catch (Throwable t) {
            vDesc = "";
        }
    }

    private String ensureTranscript() {
        if (vTr != null) return vTr;
        try {
            JSONObject d = new JSONObject(NewPipeHelper.getTranscript(vUrl));
            vTr = d.optBoolean("ok", false) ? d.optString("text", "") : "";
        } catch (Throwable t) {
            vTr = "";
        }
        return vTr;
    }

    // ------------------------------------------------------------------ output

    private void showStart(String kind, int ws, int we) {
        actions.removeAllViews();
        outHead.setVisibility(View.VISIBLE);
        outHead.setText(kindLabel(kind) + (ws >= 0 ? " — " + fmt(ws) + " থেকে " + fmt(we) : ""));
        setText("ভাবছে…");
        outHead.requestFocus();
        outHead.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    private void setText(String t) {
        outText.setText(t);
        outText.announceForAccessibility(t);
    }

    private void showDone(final String kind, String text, final int ws, final int we) {
        String shown = text;
        if ("fact".equals(kind)) shown = renderFact(text);
        lastText = shown;
        outText.setText(shown);
        actions.removeAllViews();
        actions.addView(btn("জোরে পড়ুন", () -> speak(lastText)));
        actions.addView(btn("শেয়ার", () -> {
            try {
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, lastText), "শেয়ার"));
            } catch (Throwable ignored) { }
        }));
        if ("scene".equals(kind) && vDur > 0 && we < vDur - 3) {
            actions.addView(btn("পরের অংশ (" + fmt(we) + " থেকে)", () -> run("scene", we)));
        }
        outHead.requestFocus();
        outHead.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    private String renderFact(String text) {
        try {
            int a = text.indexOf('{'), b = text.lastIndexOf('}');
            if (a < 0 || b <= a) return text;
            JSONObject j = new JSONObject(text.substring(a, b + 1));
            int sc = (int) Math.round(j.optDouble("score", Double.NaN));
            sc = Math.max(0, Math.min(100, sc));
            String verdict = sc < 30 ? "বিশ্বাসযোগ্য" : sc < 60 ? "একটু অতিরঞ্জিত" : "ক্লিকবেইট";
            StringBuilder sb = new StringBuilder();
            sb.append("ক্লিকবেইট স্কোর ১০০-র মধ্যে ").append(bn(sc)).append(", রায়: ").append(verdict).append("\n");
            list(sb, "কারণ", j.optJSONArray("reasons"));
            list(sb, "আলাদা করে যাচাই করুন", j.optJSONArray("check"));
            String bt = j.optString("better_title", "");
            if (!bt.isEmpty()) sb.append("\nআরও সৎ শিরোনাম:\n").append(bt).append("\n");
            return sb.toString().trim();
        } catch (Throwable t) {
            return text;
        }
    }

    private static void list(StringBuilder sb, String title, org.json.JSONArray a) {
        if (a == null || a.length() == 0) return;
        sb.append("\n").append(title).append(":\n");
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) sb.append("- ").append(s).append("\n");
        }
    }

    private void speak(String t) {
        try {
            if (!ttsReady) { toast("এই ফোনে জোরে পড়া চালু নেই"); return; }
            tts.stop();
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "ai");
        } catch (Throwable e) { toast("জোরে পড়া যায়নি"); }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
}
