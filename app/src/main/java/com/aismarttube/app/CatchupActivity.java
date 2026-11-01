package com.aismarttube.app;

import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Earlier programmes of one Jio channel (up to 7 days back). Tap a programme to watch it from the start. */
public class CatchupActivity extends AppCompatActivity {

    static final String EXTRA_INDEX = "tv_index";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TvChannels.Ch ch;
    private int idx = 0;
    private String jioId = "";
    private int offset = 0;                       // 0 = today, -1 = yesterday ...
    private final List<JioClient.Prog> progs = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView state;
    private Button dayBtn;
    private int loadGen = 0;
    private ListView list;
    private int pendingMin = -1;                 // time of day asked for by voice / text (minutes), applied after the list loads
    private static final int REQ_VOICE = 92;
    private static final int NONE = 99;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        List<TvChannels.Ch> q = TvChannels.queue;
        idx = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (q == null || idx < 0 || idx >= q.size()) { finish(); return; }
        ch = q.get(idx);
        for (TvChannels.Stream s : ch.streams) {
            String id = JioClient.idOf(s.url);
            if (!id.isEmpty()) { jioId = id; break; }
        }
        if (jioId.isEmpty()) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF09161A);

        TextView title = new TextView(this);
        title.setText(ch.name + " — আগের অনুষ্ঠান");
        title.setTextSize(20);
        title.setTextColor(0xFFEAF5F3);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(16), dp(16), dp(16), dp(8));
        ViewCompat.setAccessibilityHeading(title, true);
        root.addView(title);

        dayBtn = new Button(this);
        dayBtn.setAllCaps(false);
        dayBtn.setOnClickListener(v -> dayMenu());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.setMargins(dp(12), 0, dp(12), 0);
        root.addView(dayBtn, lp);

        LinearLayout findRow = new LinearLayout(this);
        findRow.setOrientation(LinearLayout.HORIZONTAL);
        Button voiceBtn = new Button(this);
        voiceBtn.setAllCaps(false);
        voiceBtn.setText("বলে খুঁজুন");
        voiceBtn.setContentDescription("দিন ও সময় বলে খুঁজুন। যেমন: বৃহস্পতিবার রাত নটা");
        voiceBtn.setOnClickListener(v -> askVoice());
        Button typeBtn = new Button(this);
        typeBtn.setAllCaps(false);
        typeBtn.setText("লিখে খুঁজুন");
        typeBtn.setContentDescription("দিন ও সময় লিখে খুঁজুন");
        typeBtn.setOnClickListener(v -> askText());
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, dp(56), 1f);
        fl.setMargins(dp(4), 0, dp(4), 0);
        findRow.addView(voiceBtn, fl);
        findRow.addView(typeBtn, fl);
        LinearLayout.LayoutParams fr = new LinearLayout.LayoutParams(-1, -2);
        fr.setMargins(dp(8), dp(4), dp(8), 0);
        root.addView(findRow, fr);

        Button live = new Button(this);
        live.setAllCaps(false);
        live.setText("লাইভ দেখুন");
        live.setOnClickListener(v -> openPlayer(null));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(56));
        lp2.setMargins(dp(12), dp(4), dp(12), 0);
        root.addView(live, lp2);

        state = new TextView(this);
        state.setTextSize(15);
        state.setTextColor(0xFFA4BCC0);
        state.setPadding(dp(16), dp(8), dp(16), dp(8));
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(state);

        list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int pos, View cv, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(0xFFEAF5F3);
                t.setTextSize(17);
                t.setMinHeight(dp(60));
                t.setPadding(dp(16), dp(8), dp(16), dp(8));
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < progs.size()) openPlayer(progs.get(pos));
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        load();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String dayName(int off) {
        if (off == 0) return "আজ";
        if (off == -1) return "গতকাল";
        SimpleDateFormat f = new SimpleDateFormat("EEEE, d MMMM", new Locale("bn", "IN"));
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new Date(System.currentTimeMillis() + off * 86400000L));
    }

    private void dayMenu() {
        final String[] names = new String[8];
        for (int i = 0; i < 8; i++) names[i] = dayName(-i);
        new AlertDialog.Builder(this).setTitle("কোন দিনের অনুষ্ঠান")
                .setSingleChoiceItems(names, -offset, (d, which) -> {
                    offset = -which;
                    d.dismiss();
                    load();
                })
                .setNegativeButton("বন্ধ", null).show();
    }

    private void load() {
        final int my = ++loadGen;
        dayBtn.setText("দিন: " + dayName(offset));
        dayBtn.setContentDescription("দিন বদলান। এখন: " + dayName(offset));
        progs.clear();
        labels.clear();
        adapter.notifyDataSetChanged();
        state.setText("অনুষ্ঠানের তালিকা আনা হচ্ছে…");
        final int off = offset;
        io.execute(() -> {
            List<JioClient.Prog> got = null;
            String err = null;
            try { got = JioClient.epg(getApplicationContext(), jioId, off); }
            catch (Throwable t) { err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
            final List<JioClient.Prog> g = got;
            final String e = err;
            ui.post(() -> {
                if (my != loadGen) return;
                if (g == null) { state.setText(e); return; }
                long now = System.currentTimeMillis();
                SimpleDateFormat tf = new SimpleDateFormat("hh:mm a", Locale.US);
                tf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
                for (JioClient.Prog p : g) {
                    if (p.start > now) continue;              // not started yet
                    progs.add(p);
                    boolean liveNow = p.end > now;
                    labels.add(tf.format(new Date(p.start)) + " — " + p.title + (liveNow ? " (এখন চলছে)" : ""));
                }
                adapter.notifyDataSetChanged();
                state.setText(progs.isEmpty()
                        ? "এই দিনের কোনো অনুষ্ঠান পাওয়া যায়নি"
                        : progs.size() + "টি অনুষ্ঠান। চাপলে শুরু থেকে চলবে");
                applyPending();
            });
        });
    }

    /** p == null: live */
    private void openPlayer(JioClient.Prog p) {
        Intent in = new Intent(this, TvPlayerActivity.class).putExtra(TvPlayerActivity.EXTRA_INDEX, idx);
        if (p != null && p.end <= System.currentTimeMillis()) {
            in.putExtra(TvPlayerActivity.EXTRA_CU_SRNO, p.srno)
                    .putExtra(TvPlayerActivity.EXTRA_CU_START, p.start)
                    .putExtra(TvPlayerActivity.EXTRA_CU_END, p.end)
                    .putExtra(TvPlayerActivity.EXTRA_CU_TITLE, p.title);
        }
        startActivity(in);
    }


    // ------------------------------------------------------------ find by day and time ("বৃহস্পতিবার রাত নটা")

    @SuppressWarnings("deprecation")
    private void askVoice() {
        Intent vi = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        vi.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        vi.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-IN");
        vi.putExtra(RecognizerIntent.EXTRA_PROMPT, "দিন আর সময় বলুন, যেমন: বৃহস্পতিবার রাত নটা");
        try { startActivityForResult(vi, REQ_VOICE); }
        catch (Throwable t) { state.setText("এই ফোনে ভয়েস সার্চ পাওয়া যাচ্ছে না"); }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) handleWhen(r.get(0));
        }
    }

    private void askText() {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setHint("যেমন: বৃহস্পতিবার রাত ৯টা");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("কোন দিন, কোন সময়?").setView(box)
                .setPositiveButton("খুঁজুন", (d, w) -> handleWhen(e.getText().toString()))
                .setNegativeButton("বাতিল", null).show();
    }

    private void handleWhen(String text) {
        int[] w = parseWhen(text);
        if (w[0] == NONE && w[1] < 0) {
            state.setText("\"" + text + "\" থেকে দিন বা সময় বুঝতে পারিনি। যেমন বলুন: বৃহস্পতিবার রাত নটা");
            return;
        }
        if (w[0] != NONE) offset = w[0];
        pendingMin = w[1];
        load();
    }

    /** after the day's list is loaded: jump to the programme running at the asked time */
    private void applyPending() {
        if (pendingMin < 0) return;
        int min = pendingMin;
        pendingMin = -1;
        if (progs.isEmpty()) return;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.add(Calendar.DAY_OF_YEAR, offset);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long t = c.getTimeInMillis() + min * 60000L;
        if (t > System.currentTimeMillis()) { state.setText("ওই সময় এখনও আসেনি"); return; }
        int at = 0;
        for (int i = 0; i < progs.size(); i++) if (progs.get(i).start <= t) at = i;
        final JioClient.Prog p = progs.get(at);
        list.setSelection(at);
        SimpleDateFormat tf = new SimpleDateFormat("hh:mm a", Locale.US);
        tf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        String msg = dayName(offset) + " " + tf.format(new Date(p.start)) + " — " + p.title;
        state.setText("পাওয়া গেছে: " + msg);
        new AlertDialog.Builder(this).setTitle("এই অনুষ্ঠান?").setMessage(msg)
                .setPositiveButton("চালান", (d, w) -> openPlayer(p))
                .setNegativeButton("থাক", null).show();
    }

    private static String normText(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : (s == null ? "" : s).toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch >= '\u09E6' && ch <= '\u09EF') sb.append((char) ('0' + (ch - '\u09E6')));   // Bengali digits
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static final String[][] WEEKDAYS = {
            {"রবিবার", "রবি", "sunday"}, {"সোমবার", "সোম", "monday"}, {"মঙ্গলবার", "মঙ্গল", "tuesday"},
            {"বুধবার", "বুধ", "wednesday"}, {"বৃহস্পতিবার", "বৃহস্পতি", "বৃহঃ", "thursday"},
            {"শুক্রবার", "শুক্র", "friday"}, {"শনিবার", "শনি", "saturday"}};     // index + 1 = Calendar day of week

    private static final String[][] NUMWORDS = {
            {"1", "এক"}, {"2", "দুই", "দু"}, {"3", "তিন"}, {"4", "চার"}, {"5", "পাঁচ", "পাচ"}, {"6", "ছয়", "ছ"},
            {"7", "সাত"}, {"8", "আট"}, {"9", "নয়", "ন"}, {"10", "দশ"}, {"11", "এগারো", "এগার"}, {"12", "বারো", "বার"}};

    /** {day offset (0, -1 ... -7) or NONE, minutes of the day or -1} */
    static int[] parseWhen(String raw) {
        String s = normText(raw);
        int day = NONE;
        if (has(s, "গতকাল", "গত কাল", "yesterday", "গতরাত", "গত রাত")) day = -1;
        else if (has(s, "পরশু")) day = -2;
        else if (has(s, "আজ", "today")) day = 0;

        // time of day
        int hour = -1, min = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m|p\\.m|টা|টায়|টে|টার|বাজে)?").matcher(s);
        boolean partWord = has(s, "রাত", "night", "সকাল", "ভোর", "morning", "দুপুর", "noon", "বিকেল", "বিকাল", "afternoon", "সন্ধ্যা", "সন্ধে", "evening");
        while (m.find()) {
            boolean suffix = m.group(3) != null, colon = m.group(2) != null;
            if (suffix || colon || partWord) {
                int h = Integer.parseInt(m.group(1));
                if (h >= 0 && h <= 24) { hour = h; min = colon ? Integer.parseInt(m.group(2)) : 0; break; }
            }
        }
        if (hour < 0) {   // numbers written as words: নটা, দশটা ...
            for (String[] nw : NUMWORDS) {
                for (int k = 1; k < nw.length; k++) {
                    if (java.util.regex.Pattern.compile("(?<![\\p{L}])" + nw[k] + "(টা|টে|টায়|টার|টো|ঘটিকা)").matcher(s).find()) { hour = Integer.parseInt(nw[0]); break; }
                }
                if (hour >= 0) break;
            }
        }
        if (hour >= 0) {
            if (has(s, "সাড়ে")) min = 30;
            else if (has(s, "সোয়া")) min = 15;
            else if (has(s, "পৌনে")) { hour = hour - 1; min = 45; }
            boolean pm = has(s, "pm", "p.m"), am = has(s, "am", "a.m");
            if (pm && hour < 12) hour += 12;
            else if (am && hour == 12) hour = 0;
            else if (has(s, "রাত", "night")) { if (hour >= 6 && hour < 12) hour += 12; else if (hour == 12) hour = 0; }
            else if (has(s, "সকাল", "ভোর", "morning")) { if (hour == 12) hour = 0; }
            else if (has(s, "দুপুর", "noon")) { if (hour >= 1 && hour <= 4) hour += 12; }
            else if (has(s, "বিকেল", "বিকাল", "afternoon", "সন্ধ্যা", "সন্ধে", "evening")) { if (hour < 12) hour += 12; }
            else if (!am && hour >= 6 && hour < 12) hour += 12;     // nothing said: evening is the likely one
            if (hour > 23) hour = 23;
        }
        int minutes = hour < 0 ? -1 : Math.max(0, Math.min(1439, hour * 60 + min));

        // weekday name -> the latest such day (today counts), going back up to a week
        if (day == NONE) {
            int target = -1;
            for (int i = 0; i < WEEKDAYS.length && target < 0; i++) if (has(s, WEEKDAYS[i])) target = i + 1;
            if (target > 0) {
                Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
                int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
                for (int d = 0; d >= -7; d--) {
                    Calendar c = (Calendar) now.clone();
                    c.add(Calendar.DAY_OF_YEAR, d);
                    if (c.get(Calendar.DAY_OF_WEEK) != target) continue;
                    if (d == 0 && minutes > nowMin) continue;          // that time has not come today: last week's
                    day = d;
                    break;
                }
            }
        }
        return new int[]{day, minutes};
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
