package com.aismarttube.app;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Native settings screen (no WebView, no heavy HTML page). Reads and writes the same "settings"
 * JSON in Store that the web screens use, so both always agree.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int SW = 0, SEL = 1, TXT = 2, KEY = 3;

    private static final class Item {
        final int type; final String id, title, hint; final String[] labels, values; final boolean defB; final String defS;
        Item(int type, String id, String title, String hint, String[] labels, String[] values, boolean defB, String defS) {
            this.type = type; this.id = id; this.title = title; this.hint = hint;
            this.labels = labels; this.values = values; this.defB = defB; this.defS = defS;
        }
    }

    private final List<Object> rows = new ArrayList<>(); // String = group heading, Item = setting
    private LinearLayout box;

    private void head(String t) { rows.add(t); }
    private void sw(String id, String title, String hint, boolean def) { rows.add(new Item(SW, id, title, hint, null, null, def, "")); }
    private void sel(String id, String title, String hint, String def, String... lv) {
        String[] labels = new String[lv.length / 2], values = new String[lv.length / 2];
        for (int i = 0; i < labels.length; i++) { values[i] = lv[2 * i]; labels[i] = lv[2 * i + 1]; }
        rows.add(new Item(SEL, id, title, hint, labels, values, false, def));
    }
    private void txt(String id, String title, String hint, String def, boolean secret) {
        rows.add(new Item(secret ? KEY : TXT, id, title, hint, null, null, false, def));
    }

    private void spec() {
        head("ভিডিওর সোর্স");
        sel("primary", "প্রথমে কোনটা চেষ্টা হবে", "", "newpipe", "newpipe", "NewPipe", "piped", "Piped", "invidious", "Invidious");
        sw("fallback", "ব্যর্থ হলে অন্যটা চেষ্টা করুক", "বন্ধ করলে শুধু প্রথম সোর্সই ব্যবহার হবে", true);

        head("প্লেব্যাক");
        sel("quality", "ভিডিওর মান", "অটো মানে ৭২০p পর্যন্ত", "auto", "auto", "অটো", "max", "সর্বোচ্চ", "720", "৭২০p", "360", "৩৬০p", "low", "সর্বনিম্ন");
        sw("bgPlay", "ব্যাকগ্রাউন্ডে চালান", "স্ক্রিন বন্ধ করলেও চলবে", true);
        sw("quietPlay", "বাজার সময় ঘোষণা কমান", "", true);
        sw("audioOnly", "শুধু অডিও মোড", "ছবি ছাড়া শুধু শব্দ, ডেটা কম লাগে", false);
        sw("lowData", "কম ডাটা অডিও", "শুধু অডিও চালালে সবচেয়ে ছোট মানের অডিও নেয়", false);
        sw("resume", "আগের জায়গা থেকে চালু", "", true);
        sw("autoplay", "শেষ হলে পরেরটা চালান", "", true);
        sel("skip", "এগিয়ে / পিছিয়ে যাওয়ার সময়", "", "10",
                "5", "৫ সেকেন্ড", "10", "১০ সেকেন্ড", "15", "১৫ সেকেন্ড", "30", "৩০ সেকেন্ড", "60", "১ মিনিট",
                "120", "২ মিনিট", "300", "৫ মিনিট", "600", "১০ মিনিট");
        sel("speed", "গতি", "", "1", "0.75", "০.৭৫×", "1", "১×", "1.25", "১.২৫×", "1.5", "১.৫×", "2", "২×");

        head("সার্চ ও ভয়েস");
        sel("voiceLang", "ভয়েস সার্চের ভাষা", "", "bn-IN", "bn-IN", "বাংলা (ভারত)", "bn-BD", "বাংলা (বাংলাদেশ)", "en-IN", "English (India)", "hi-IN", "हिन्दी");

        head("ডাউনলোড");
        sw("wifiOnly", "শুধু ওয়াইফাইয়ে ডাউনলোড", "বন্ধ থাকলে মোবাইল ডেটাতেও ডাউনলোড হবে", false);

        head("অ্যাক্সেসিবিলিটি");
        sel("textSize", "লেখার আকার", "", "1", "0.9", "ছোট", "1", "স্বাভাবিক", "1.15", "বড়", "1.3", "অনেক বড়");
        sw("contrast", "উচ্চ কনট্রাস্ট", "", false);
        sw("reduceMotion", "কম অ্যানিমেশন", "", false);
        sw("focusHeading", "পাতা বদলালে শিরোনামে ফোকাস", "", true);

        head("থিম");
        sel("theme", "রঙের থিম", "", "auto", "auto", "ফোনের মতো", "dark", "গাঢ়", "light", "হালকা");

        head("গোপনীয়তা");
        sw("keepHistory", "দেখার ইতিহাস রাখুন", "", true);
        sw("keepSearch", "সার্চের ইতিহাস রাখুন", "", true);

        head("AI — key ও মডেল");
        txt("gemKey", "Gemini API key", "Google AI Studio থেকে নেওয়া", "", true);
        txt("gemModel", "Gemini মডেল", "ব্যর্থ হলে অ্যাপ নিজেই অন্য মডেল চেষ্টা করবে", "gemini-3.8-flash", false);
        txt("grokKey", "Grok (xAI) API key", "", "", true);
        txt("grokModel", "Grok মডেল", "", "grok-4.7", false);
        txt("cusBase", "অন্য AI-এর Base URL", "https:// দিয়ে শুরু, যেমন https://openrouter.ai/api/v1", "", false);
        txt("cusModel", "অন্য AI-এর মডেল", "", "", false);
        txt("cusKey", "অন্য AI-এর API key", "", "", true);

        head("AI — কে কী করবে");
        sel("provSum", "সারাংশ কে করবে", "", "gemini", "gemini", "Gemini", "grok", "Grok", "custom", "অন্য AI");
        sel("provFact", "ক্লিকবেইট পরীক্ষা কে করবে", "", "grok", "grok", "Grok", "gemini", "Gemini", "custom", "অন্য AI");
        sel("provTr", "অনুবাদ কে করবে", "", "gemini", "gemini", "Gemini", "grok", "Grok", "custom", "অন্য AI");
        sel("provAsk", "প্রশ্নের উত্তর কে দেবে", "", "gemini", "gemini", "Gemini", "grok", "Grok", "custom", "অন্য AI");
        sel("aiLang", "উত্তরের ভাষা", "অনুবাদ কোন ভাষায় হবে, সেটাও এটাই", "বাংলা", "বাংলা", "বাংলা", "English", "English", "हिन्दी", "हिन्दी");
        sel("sumLen", "সারাংশের দৈর্ঘ্য", "", "5", "3", "ছোট (৩ পয়েন্ট)", "5", "মাঝারি (৫ পয়েন্ট)", "8", "বিস্তারিত (৮ পয়েন্ট)");
        sw("aiWatch", "Gemini সরাসরি ভিডিও দেখুক", "", true);
        sw("useTranscript", "সাবটাইটেল পড়ে AI-কে দিন", "", true);
        sel("sceneLen", "দৃশ্য বর্ণনার সময়", "", "5", "1", "১ মিনিট", "2", "২ মিনিট", "5", "৫ মিনিট", "10", "১০ মিনিট");
        sw("aiSpeak", "উত্তর নিজে থেকে জোরে পড়ুক", "টকব্যাক চালু থাকলে বন্ধ রাখুন", false);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("সেটিংস");
        spec();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0B1A1F);
        sv.setFillViewport(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(24));
        sv.addView(box);
        setContentView(sv);
        build();
    }

    private void build() {
        box.removeAllViews();
        TextView top = new TextView(this);
        top.setText("সেটিংস");
        top.setTextSize(24);
        top.setTextColor(0xFFEAF5F3);
        top.setTypeface(null, android.graphics.Typeface.BOLD);
        top.setPadding(dp(20), dp(16), dp(20), dp(8));
        ViewCompat.setAccessibilityHeading(top, true);
        box.addView(top);
        for (Object o : rows) {
            if (o instanceof String) {
                TextView h = new TextView(this);
                h.setText((String) o);
                h.setTextSize(15);
                h.setTextColor(0xFFFFB84A);
                h.setTypeface(null, android.graphics.Typeface.BOLD);
                h.setPadding(dp(20), dp(20), dp(20), dp(4));
                ViewCompat.setAccessibilityHeading(h, true);
                box.addView(h);
            } else {
                box.addView(row((Item) o));
            }
        }
        box.addView(button("দেখার ইতিহাস মুছুন", () -> confirmClear("history", "দেখার ইতিহাস মুছবেন?")));
        box.addView(button("সার্চের ইতিহাস মুছুন", () -> confirmClear("searches", "সার্চের ইতিহাস মুছবেন?")));
    }

    private View button(String text, Runnable r) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(17);
        v.setTextColor(0xFFFF7A7A);
        v.setMinHeight(dp(56));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), dp(8));
        v.setClickable(true);
        v.setFocusable(true);
        v.setBackgroundResource(android.R.drawable.list_selector_background);
        v.setOnClickListener(x -> r.run());
        return v;
    }

    private void confirmClear(String key, String q) {
        new AlertDialog.Builder(this).setTitle(q)
                .setPositiveButton("মুছুন", (d, w) -> {
                    Store.set(this, key, "[]");
                    Toast.makeText(this, "মোছা হয়েছে", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("থাক", null).show();
    }

    private String curStr(Item it) { return Store.settingStr(this, it.id, it.defS); }

    private View row(final Item it) {
        if (it.type == SW) {
            SwitchCompat s = new SwitchCompat(this);
            s.setText(it.hint.isEmpty() ? it.title : it.title + "\n" + it.hint);
            s.setTextSize(17);
            s.setTextColor(0xFFEAF5F3);
            s.setMinHeight(dp(60));
            s.setPadding(dp(20), dp(8), dp(20), dp(8));
            s.setChecked(Store.settingBool(this, it.id, it.defB));
            s.setOnCheckedChangeListener((v, on) -> Store.putSettingBool(this, it.id, on));
            return s;
        }
        final TextView v = new TextView(this);
        v.setTextSize(17);
        v.setTextColor(0xFFEAF5F3);
        v.setMinHeight(dp(60));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(20), dp(10), dp(20), dp(10));
        v.setClickable(true);
        v.setFocusable(true);
        v.setBackgroundResource(android.R.drawable.list_selector_background);
        refreshRow(v, it);
        v.setOnClickListener(x -> {
            if (it.type == SEL) pick(it, v); else edit(it, v);
        });
        return v;
    }

    private String shown(Item it) {
        String cur = curStr(it);
        if (it.type == SEL) {
            for (int i = 0; i < it.values.length; i++) if (it.values[i].equals(cur)) return it.labels[i];
            return cur;
        }
        if (it.type == KEY) return cur.isEmpty() ? "দেওয়া হয়নি" : "দেওয়া আছে (লুকানো)";
        return cur.isEmpty() ? "ফাঁকা" : cur;
    }

    private void refreshRow(TextView v, Item it) {
        String line2 = shown(it);
        String t = it.title + "\n" + line2 + (it.hint.isEmpty() ? "" : "\n" + it.hint);
        android.text.SpannableString sp = new android.text.SpannableString(t);
        int a = it.title.length() + 1;
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF7FD6C8), a, a + line2.length(), 0);
        if (!it.hint.isEmpty()) {
            int h = a + line2.length() + 1;
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFA4BCC0), h, t.length(), 0);
            sp.setSpan(new android.text.style.RelativeSizeSpan(0.8f), h, t.length(), 0);
        }
        v.setText(sp);
        v.setContentDescription(it.title + ": " + line2 + (it.hint.isEmpty() ? "" : ". " + it.hint));
    }

    private void pick(final Item it, final TextView v) {
        String cur = curStr(it);
        int sel = -1;
        for (int i = 0; i < it.values.length; i++) if (it.values[i].equals(cur)) sel = i;
        new AlertDialog.Builder(this).setTitle(it.title)
                .setSingleChoiceItems(it.labels, sel, (d, which) -> {
                    Store.putSetting(this, it.id, it.values[which]);
                    refreshRow(v, it);
                    d.dismiss();
                })
                .setNegativeButton("বন্ধ", null).show();
    }

    private void edit(final Item it, final TextView v) {
        final EditText e = new EditText(this);
        e.setText(it.type == KEY ? "" : curStr(it));
        e.setHint(it.type == KEY ? "নতুন key বসান (ফাঁকা রাখলে বদলাবে না)" : it.title);
        e.setSingleLine(true);
        e.setInputType(it.type == KEY
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout w = new LinearLayout(this);
        w.setPadding(dp(20), dp(8), dp(20), 0);
        w.addView(e, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder bd = new AlertDialog.Builder(this).setTitle(it.title).setView(w)
                .setPositiveButton("সেভ", (d, wh) -> {
                    String s = e.getText().toString().trim();
                    if (it.type == KEY && s.isEmpty()) return;
                    Store.putSetting(this, it.id, s);
                    refreshRow(v, it);
                })
                .setNegativeButton("বাতিল", null);
        if (it.type == KEY) bd.setNeutralButton("মুছুন", (d, wh) -> { Store.putSetting(this, it.id, ""); refreshRow(v, it); });
        bd.show();
    }
}
