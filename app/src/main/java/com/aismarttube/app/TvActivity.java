package com.aismarttube.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TV tab: Bengali channel list (favourites first), search, own channels. Tap = watch. Long press = more. */
public class TvActivity extends AppCompatActivity {

    private static final class Row {
        final boolean header;
        final TvChannels.Ch ch;
        final String text;
        String custom = null;      // a programme search result line
        Row(TvChannels.Ch ch, String custom) { this.header = false; this.ch = ch; this.text = ch.name; this.custom = custom; }
        Row(String text) { this.header = true; this.text = text; this.ch = null; }
        Row(TvChannels.Ch ch) { this.header = false; this.ch = ch; this.text = ch.name; }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Row> rows = new ArrayList<>();
    private List<TvChannels.Ch> all = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private TextView status;
    private EditText search;
    private static final int REQ_VOICE = 91;
    private boolean progMode = false;   // the list shows programme search results
    private boolean loading = false;
    private boolean openedChild = false;   // true while a channel / catch-up screen was opened from here (the search stays then)
    private String catSel = "";          // "" = all, "__fav" = favourites, otherwise a category key
    private Set<String> favNow = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_tv);
        status = findViewById(R.id.status);
        search = findViewById(R.id.tvSearch);
        search.setSaveEnabled(false);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemViewCacheSize(8);
        adapter = new Ad();
        list.setAdapter(adapter);
        TabBar.setup(this, TabBar.TV);
        search.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                String t = search.getText().toString().trim();
                if (t.length() >= 2) progSearch(t);
                return true;
            }
            return false;
        });
        catSel = Store.settingStr(this, "tvCat", "");
        findViewById(R.id.tvCat).setOnClickListener(v -> catMenu());
        findViewById(R.id.tvMore).setOnClickListener(v -> moreMenu());
        findViewById(R.id.tvVoice).setOnClickListener(v -> {
            Intent vi = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            vi.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            vi.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-IN");       // Jio channel names are English
            vi.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "চ্যানেলের নাম ইংরেজিতে বলুন");
            try {
                openedChild = true;
                startActivityForResult(vi, REQ_VOICE);
            } catch (Throwable t) {
                openedChild = false;
                Toast.makeText(this, "এই ফোনে ভয়েস সার্চ পাওয়া যাচ্ছে না", Toast.LENGTH_LONG).show();
            }
        });
        final android.widget.CheckBox cuBox = findViewById(R.id.tvCatchup);
        cuBox.setChecked(Store.settingBool(this, "tvCatchup", false));
        cuBox.setContentDescription("ক্যাচআপ। চালু থাকলে Jio চ্যানেলে চাপলে আগের অনুষ্ঠানের তালিকা খোলে");
        cuBox.setOnCheckedChangeListener((v, on) -> Store.putSettingBool(this, "tvCatchup", on));
        final android.widget.CheckBox premBox = findViewById(R.id.tvPrem);
        premBox.setChecked(Store.settingBool(this, "tvPrem", false));
        premBox.setContentDescription("প্রিমিয়াম চ্যানেল দেখান। Jio-তে প্রিমিয়াম প্ল্যান থাকলে এগুলো চলে");
        final android.widget.CheckBox onlyBox = findViewById(R.id.tvPremOnly);
        onlyBox.setChecked(Store.settingBool(this, "tvPremOnly", false));
        onlyBox.setContentDescription("শুধু প্রিমিয়াম চ্যানেল দেখান। অন্য চ্যানেল লুকানো থাকবে");
        onlyBox.setOnCheckedChangeListener((v, on) -> {
            Store.putSettingBool(this, "tvPremOnly", on);
            if (on && !premBox.isChecked()) premBox.setChecked(true);     // premium channels must be in the list for this
            else { all = merge(); build(); }
        });
        premBox.setOnCheckedChangeListener((v, on) -> {
            Store.putSettingBool(this, "tvPrem", on);
            if (!on && onlyBox.isChecked()) onlyBox.setChecked(false);    // nothing premium left to show only
            all = merge();
            build();
            showStatus(on ? "প্রিমিয়াম চ্যানেল তালিকায় যোগ হয়েছে (নামের শেষে 'Jio প্রিমিয়াম')" : "প্রিমিয়াম চ্যানেল লুকানো হয়েছে");
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { progMode = false; build(); }
        });
        all = merge();
        build();
        boolean jioIn = JioClient.isLoggedIn(this);
        boolean freeNeeded = !jioIn || Store.settingBool(this, "tvFree", false);
        if (all.isEmpty() || (freeNeeded && TvChannels.stale(this)) || (jioIn && JioClient.channelsStale(this))) fetch(false);
        if (!JioClient.isLoggedIn(this) && Store.settingStr(this, "tvMode", "").isEmpty()) welcomeDialog();
    }

    /** first time in the TV tab: Jio login or plain free channels (like YouTube's sign in / without sign in) */
    private void welcomeDialog() {
        new AlertDialog.Builder(this).setTitle("টিভি কীভাবে দেখবেন?")
                .setMessage("Jio লগইন করলে Jio-র চ্যানেল, ক্যাচআপ (আগের অনুষ্ঠান) আর প্রিমিয়াম চ্যানেল পাবেন। লগইন না করলে ফ্রি চ্যানেলের তালিকা দেখবেন। পরে 'আরও' মেনু থেকে যেকোনো সময় লগইন করা যায়।")
                .setPositiveButton("Jio-তে লগইন করুন", (d, w) -> jioNumberDialog())
                .setNegativeButton("এমনি চালিয়ে যান", (d, w) -> Store.putSetting(this, "tvMode", "free"))
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        openedChild = false;
        all = merge();
        build(); // favourites may have changed in the player
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                search.setText(r.get(0).trim());
                search.setSelection(search.getText().length());
                search.announceForAccessibility("খোঁজা হচ্ছে: " + r.get(0));
                if (rows.isEmpty() && JioClient.isLoggedIn(this)) progSearch(r.get(0).trim());
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // leaving the TV tab (home, other tab): forget the search, so the tab opens fresh next time.
        // Going to a channel keeps it, so Back from the channel shows the same results.
        if (!openedChild && search != null && search.getText().length() > 0) search.setText("");
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        TabBar.go(this, TabBar.TV, TabBar.HOME);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private List<TvChannels.Ch> merge() {
        List<TvChannels.Ch> out = new ArrayList<>(TvChannels.custom(this));
        List<TvChannels.Ch> net = TvChannels.cached(this);
        // Jio channels (only when logged in) are listed on their own, with "(Jio)" after the name
        if (JioClient.isLoggedIn(this) && !Store.settingBool(this, "tvFree", false)) net.clear();   // only Jio, unless the free list is switched on
        net.addAll(JioClient.cachedChannels(this));
        Collections.sort(net, new Comparator<TvChannels.Ch>() {
            @Override public int compare(TvChannels.Ch a, TvChannels.Ch b) { return a.name.compareToIgnoreCase(b.name); }
        });
        out.addAll(net);
        return out;
    }

    private void fetch(final boolean announce) {
        if (loading) return;
        loading = true;
        showStatus("চ্যানেলের তালিকা আনা হচ্ছে…");
        io.execute(() -> {
            String err = null;
            boolean needFree = !JioClient.isLoggedIn(getApplicationContext()) || Store.settingBool(getApplicationContext(), "tvFree", false);
            if (needFree) try { TvChannels.download(getApplicationContext()); } catch (Throwable t) { err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
            if (JioClient.isLoggedIn(getApplicationContext()) && (announce || JioClient.channelsStale(getApplicationContext()))) {
                try { JioClient.fetchChannels(getApplicationContext()); } catch (Throwable t) {
                    String m = "Jio: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                    err = err == null ? m : err + "; " + m;
                }
            }
            final String e = err;
            ui.post(() -> {
                loading = false;
                all = merge();
                build();
                if (e != null) showStatus("তালিকা আনা যায়নি: " + e + (all.isEmpty() ? ". নেট দেখে 'আরও' থেকে আবার আনুন" : ". আগের তালিকা দেখানো হচ্ছে"));
                else showStatus(announce ? "তালিকা নতুন হয়েছে, মোট " + all.size() + "টি চ্যানেল" : "");
            });
        });
    }

    private void showStatus(String t) {
        if (t == null || t.isEmpty()) { status.setVisibility(View.GONE); return; }
        status.setVisibility(View.VISIBLE);
        status.setText(t);
    }

    private static final String[][] ALIAS = {
            {"স্টার জলসা", "star jalsha"}, {"জলসা মুভিজ", "jalsha movies"}, {"জলসা", "jalsha"},
            {"জি বাংলা সিনেমা", "zee bangla cinema"}, {"জি বাংলা", "zee bangla"}, {"জী বাংলা", "zee bangla"},
            {"কালার্স বাংলা সিনেমা", "colors bangla cinema"}, {"কালার্স বাংলা", "colors bangla"}, {"কালারস বাংলা", "colors bangla"},
            {"সান বাংলা", "sun bangla"}, {"এবিপি আনন্দ", "abp ananda"}, {"এবিপি", "abp"},
            {"চব্বিশ ঘণ্টা", "24 ghanta"}, {"২৪ ঘণ্টা", "24 ghanta"}, {"আকাশ আট", "akash"}, {"আকাশ ৮", "akash"},
            {"সঙ্গীত বাংলা", "sangeet bangla"}, {"রিপাবলিক বাংলা", "republic bangla"},
            {"টিভি নাইন", "tv9"}, {"টিভি ৯", "tv9"}, {"টিভি9", "tv9"}, {"কলকাতা টিভি", "kolkata tv"}, {"নিউজ", "news"}};

    /** Bengali digits to 0-9, and common Bengali channel names to the English name used in the list */
    private static String alias(String q) {
        StringBuilder sb = new StringBuilder();
        for (char ch : q.toCharArray()) sb.append(ch >= '\u09E6' && ch <= '\u09EF' ? (char) ('0' + (ch - '\u09E6')) : ch);
        String s = sb.toString();
        for (String[] a : ALIAS) {
            String from = a[0];
            StringBuilder f2 = new StringBuilder();
            for (char ch : from.toCharArray()) f2.append(ch >= '\u09E6' && ch <= '\u09EF' ? (char) ('0' + (ch - '\u09E6')) : ch);
            s = s.replace(f2.toString(), a[1]);
        }
        return s;
    }

    private boolean inCategory(TvChannels.Ch c) {
        if (catSel.isEmpty()) return true;
        if ("__fav".equals(catSel)) return TvChannels.isFavIn(favNow, c);
        if ("other".equals(catSel)) return c.cats.isEmpty();
        return c.cats.contains(catSel);
    }

    private String catName() {
        if (catSel.isEmpty()) return "সব চ্যানেল";
        if ("__fav".equals(catSel)) return "প্রিয় চ্যানেল";
        return TvChannels.catLabel(catSel);
    }

    private void build() {
        if (progMode) { adapter.notifyDataSetChanged(); return; }
        rows.clear();
        favNow = TvChannels.favs(this);
        String q = alias(search.getText().toString().trim().toLowerCase(Locale.ROOT));
        Button cb = findViewById(R.id.tvCat);
        cb.setText("ক্যাটাগরি: " + catName());
        cb.setContentDescription("ক্যাটাগরি বদলান। এখন: " + catName());
        List<TvChannels.Ch> pool = new ArrayList<>();
        boolean premOnly = Store.settingBool(this, "tvPremOnly", false);
        for (TvChannels.Ch c : all) if (inCategory(c) && (!premOnly || c.prem)) pool.add(c);
        if (!q.isEmpty()) {
            for (TvChannels.Ch c : pool) {
                if (c.name.toLowerCase(Locale.ROOT).contains(q) || TvChannels.catText(c).toLowerCase(Locale.ROOT).contains(q)
                        || c.cats.contains(q)) rows.add(new Row(c));
            }
        } else if (catSel.isEmpty()) {
            List<TvChannels.Ch> f = new ArrayList<>(), rest = new ArrayList<>(), rec = new ArrayList<>();
            for (TvChannels.Ch c : pool) (TvChannels.isFavIn(favNow, c) ? f : rest).add(c);
            for (String k : TvChannels.recentKeys(this)) {
                for (TvChannels.Ch c : rest) if (c.key().equals(k)) { rec.add(c); break; }
                if (rec.size() >= 6) break;
            }
            if (!f.isEmpty()) {
                rows.add(new Row("প্রিয় চ্যানেল"));
                for (TvChannels.Ch c : f) rows.add(new Row(c));
            }
            if (!rec.isEmpty()) {
                rows.add(new Row("সম্প্রতি দেখা"));
                for (TvChannels.Ch c : rec) rows.add(new Row(c));
            }
            if (!f.isEmpty() || !rec.isEmpty()) rows.add(new Row("সব চ্যানেল"));
            for (TvChannels.Ch c : rest) rows.add(new Row(c));
        } else {
            for (TvChannels.Ch c : pool) rows.add(new Row(c));
        }
        adapter.notifyDataSetChanged();
        if (rows.isEmpty() && !loading) {
            showStatus(all.isEmpty() ? "কোনো চ্যানেল নেই। 'আরও' থেকে তালিকা আনুন বা নিজের চ্যানেল যোগ করুন"
                    : (!q.isEmpty() && JioClient.isLoggedIn(this)
                    ? "কিছু পাওয়া যায়নি। কীবোর্ডের 'খুঁজুন' বোতাম চাপলে অনুষ্ঠানের নামে খোঁজা হবে"
                    : "কিছু পাওয়া যায়নি। ক্যাটাগরি বা খোঁজা বদলে দেখুন"));
        } else if (!loading && status.getText().toString().startsWith("কিছু পাওয়া")) {
            showStatus("");
        }
    }

    /** looks for a programme name in today's schedule of the Jio channels (current list filters apply) */
    private void progSearch(final String query) {
        if (!JioClient.isLoggedIn(this)) {
            showStatus("অনুষ্ঠানের নামে খুঁজতে Jio-তে লগইন লাগবে");
            return;
        }
        final String ql = query.toLowerCase(Locale.ROOT);
        final List<TvChannels.Ch> targets = new ArrayList<>();
        boolean premOnly = Store.settingBool(this, "tvPremOnly", false);
        for (TvChannels.Ch c : all) {
            if (!inCategory(c) || (premOnly && !c.prem)) continue;
            if (!c.streams.isEmpty() && !JioClient.idOf(c.streams.get(0).url).isEmpty()) targets.add(c);
            if (targets.size() >= 150) break;
        }
        if (targets.isEmpty()) { showStatus("খোঁজার মতো কোনো Jio চ্যানেল তালিকায় নেই"); return; }
        showStatus("অনুষ্ঠানের নামে খোঁজা হচ্ছে… (" + targets.size() + "টি চ্যানেলের তালিকা দেখা হচ্ছে, কিছুক্ষণ লাগবে)");
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        final List<Object[]> hits = java.util.Collections.synchronizedList(new ArrayList<Object[]>());
        final ExecutorService pool = Executors.newFixedThreadPool(6);
        final long now = System.currentTimeMillis();
        for (final TvChannels.Ch c : targets) {
            pool.execute(() -> {
                try {
                    for (JioClient.Prog p : JioClient.epgToday(getApplicationContext(), JioClient.idOf(c.streams.get(0).url))) {
                        if (p.end > now && p.title.toLowerCase(Locale.ROOT).contains(ql)) hits.add(new Object[]{c, p});
                    }
                } catch (Throwable ignored) { }
                int d = done.incrementAndGet();
                if (d == targets.size()) {
                    ui.post(() -> finishProgSearch(query, hits));
                    pool.shutdown();
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void finishProgSearch(String query, List<Object[]> hits) {
        if (isFinishing() || !search.getText().toString().trim().equalsIgnoreCase(query.trim())) return;   // the search text changed meanwhile
        java.util.Collections.sort(hits, (a, b) -> Long.compare(((JioClient.Prog) a[1]).start, ((JioClient.Prog) b[1]).start));
        rows.clear();
        long now = System.currentTimeMillis();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("hh:mm a", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        if (!hits.isEmpty()) rows.add(new Row("'" + query + "' — আজকের অনুষ্ঠান"));
        for (Object[] h : hits) {
            TvChannels.Ch c = (TvChannels.Ch) h[0];
            JioClient.Prog p = (JioClient.Prog) h[1];
            boolean live = p.start <= now;
            String nm = c.name.replace(" (Jio প্রিমিয়াম)", "").replace(" (Jio)", "");
            rows.add(new Row(c, p.title + "\n" + nm + " · " + (live ? "এখন চলছে" : f.format(new java.util.Date(p.start)) + "-এ শুরু")));
        }
        progMode = true;
        adapter.notifyDataSetChanged();
        showStatus(hits.isEmpty() ? "আজকের তালিকায় '" + query + "' নামে কোনো অনুষ্ঠান পাওয়া যায়নি" : hits.size() + "টি অনুষ্ঠান পাওয়া গেছে। চাপলে সেই চ্যানেল চলবে");
    }

    private void catMenu() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        int other = 0, fav = 0;
        for (TvChannels.Ch c : all) {
            if (TvChannels.isFavIn(favNow, c)) fav++;
            if (c.cats.isEmpty()) other++;
            for (String k : c.cats) counts.put(k, counts.containsKey(k) ? counts.get(k) + 1 : 1);
        }
        List<String> keys = new ArrayList<>(counts.keySet());
        final java.util.Map<String, Integer> cn = counts;
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) { return cn.get(b) - cn.get(a); }
        });
        final List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        ids.add(""); names.add("সব চ্যানেল (" + all.size() + ")");
        ids.add("__fav"); names.add("প্রিয় চ্যানেল (" + fav + ")");
        for (String k : keys) { ids.add(k); names.add(TvChannels.catLabel(k) + " (" + counts.get(k) + ")"); }
        if (other > 0) { ids.add("other"); names.add("অন্যান্য (" + other + ")"); }
        int sel = Math.max(0, ids.indexOf(catSel));
        new AlertDialog.Builder(this).setTitle("ক্যাটাগরি বেছে নিন")
                .setSingleChoiceItems(names.toArray(new String[0]), sel, (d, which) -> {
                    catSel = ids.get(which);
                    progMode = false;
                    Store.putSetting(this, "tvCat", catSel);
                    build();
                    d.dismiss();
                })
                .setNegativeButton("বন্ধ", null).show();
    }

    private void play(TvChannels.Ch ch) {
        List<TvChannels.Ch> q = new ArrayList<>();
        int idx = 0;
        for (Row r : rows) {
            if (r.header) continue;
            if (r.ch == ch) idx = q.size();
            q.add(r.ch);
        }
        TvChannels.queue = q;
        openedChild = true;
        TvChannels.addRecent(this, ch);
        boolean isJio = false;
        for (TvChannels.Stream x : ch.streams) if (!JioClient.idOf(x.url).isEmpty()) { isJio = true; break; }
        if (isJio && Store.settingBool(this, "tvCatchup", false)) {
            if (ch.cu) {
                startActivity(new Intent(this, CatchupActivity.class).putExtra(CatchupActivity.EXTRA_INDEX, idx));
                return;
            }
            Toast.makeText(this, "এই চ্যানেলে আগের অনুষ্ঠান নেই, লাইভ চলছে", Toast.LENGTH_SHORT).show();
        }
        startActivity(new Intent(this, TvPlayerActivity.class).putExtra(TvPlayerActivity.EXTRA_INDEX, idx));
    }

    private void channelMenu(final TvChannels.Ch ch) {
        final boolean fav = TvChannels.isFav(this, ch);
        List<String> names = new ArrayList<>();
        names.add(fav ? "প্রিয় থেকে সরান" : "প্রিয় চ্যানেলে রাখুন");
        names.add("লিংক কপি করুন");
        if (ch.custom) names.add("এই চ্যানেল মুছুন");
        new AlertDialog.Builder(this).setTitle(ch.name)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        boolean now = TvChannels.toggleFav(this, ch);
                        Toast.makeText(this, now ? "প্রিয়তে রাখা হয়েছে" : "প্রিয় থেকে সরানো হয়েছে", Toast.LENGTH_SHORT).show();
                        build();
                    } else if (which == 1) {
                        try {
                            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                                    .setPrimaryClip(ClipData.newPlainText("tv", ch.url()));
                            Toast.makeText(this, "লিংক কপি হয়েছে", Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) { }
                    } else {
                        TvChannels.removeCustom(this, ch);
                        all = merge();
                        build();
                    }
                })
                .setNegativeButton("বন্ধ", null).show();
    }

    private void moreMenu() {
        final boolean jio = JioClient.isLoggedIn(this);
        final List<String> names = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        names.add("চ্যানেলের তালিকা নতুন করে আনুন");
        acts.add(() -> fetch(true));
        if (jio) {
            final boolean free = Store.settingBool(this, "tvFree", false);
            names.add(free ? "ফ্রি (IPTV) চ্যানেল লুকান" : "ফ্রি (IPTV) চ্যানেলও দেখান");
            acts.add(() -> {
                Store.putSettingBool(this, "tvFree", !free);
                if (!free && TvChannels.cached(this).isEmpty()) fetch(true);
                else { all = merge(); build(); }
            });
            names.add(JioClient.allLanguages(this) ? "Jio চ্যানেল: শুধু বাংলা দেখান" : "Jio চ্যানেল: সব ভাষা দেখান");
            acts.add(() -> {
                Store.putSetting(this, "jioLang", JioClient.allLanguages(this) ? "bn" : "all");
                all = merge();
                build();
            });
        }
        final boolean low = Store.settingBool(this, "tvLowData", false);
        names.add(low ? "কম ডাটা (ছোট ছবি): চালু আছে, বন্ধ করুন" : "কম ডাটা (ছোট ছবি) চালু করুন");
        acts.add(() -> {
            Store.putSettingBool(this, "tvLowData", !low);
            Toast.makeText(this, low ? "কম ডাটা বন্ধ। পরের চ্যানেল থেকে পুরো মানে চলবে" : "কম ডাটা চালু। পরের চ্যানেল থেকে ছোট ছবিতে চলবে", Toast.LENGTH_LONG).show();
        });
        names.add("নিজের চ্যানেল যোগ করুন");
        acts.add(this::addDialog);
        names.add("তালিকার লিংক বদলান (ফ্রি তালিকা)");
        acts.add(this::srcDialog);
        names.add("আসল তালিকায় ফিরুন (বাংলা)");
        acts.add(() -> {
            Store.putSetting(this, "tvSrc", TvChannels.DEFAULT_SRC);
            fetch(true);
        });
        if (jio) {
            names.add("আমার Jio প্ল্যান দেখুন");
            acts.add(() -> {
                showStatus("প্ল্যানের তথ্য আনা হচ্ছে…");
                io.execute(() -> {
                    String txt;
                    try { txt = JioClient.plansText(getApplicationContext()); }
                    catch (Throwable t) { txt = "Jio: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()); }
                    final String t2 = txt;
                    ui.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        showStatus("");
                        new AlertDialog.Builder(this).setTitle("আমার Jio প্ল্যান").setMessage(t2)
                                .setPositiveButton("ঠিক আছে", null).show();
                    });
                });
            });
            names.add("Jio থেকে বেরিয়ে যান (লগআউট)");
            acts.add(() -> {
                JioClient.logout(this);
                if ("jio".equals(catSel)) { catSel = ""; Store.putSetting(this, "tvCat", ""); }
                all = merge();
                build();
                if (all.isEmpty()) fetch(true);
                Toast.makeText(this, "Jio থেকে বেরিয়ে এসেছেন", Toast.LENGTH_SHORT).show();
            });
        } else {
            names.add("Jio টিভিতে লগইন করুন");
            acts.add(this::jioNumberDialog);
        }
        new AlertDialog.Builder(this).setTitle("টিভি")
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .setNegativeButton("বন্ধ", null).show();
    }

    private void jioNumberDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText n = new EditText(this);
        n.setHint("১০ অঙ্কের Jio নম্বর (+91 ছাড়া)");
        n.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        box.addView(n, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Jio লগইন").setView(box)
                .setPositiveButton("OTP পাঠান", (d, w) -> {
                    final String num = n.getText().toString().trim();
                    showStatus("OTP পাঠানো হচ্ছে…");
                    io.execute(() -> {
                        String err = null;
                        try { JioClient.sendOtp(getApplicationContext(), num); }
                        catch (Throwable t) { err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
                        final String e = err;
                        ui.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (e != null) { showStatus("Jio: " + e); return; }
                            showStatus("OTP পাঠানো হয়েছে, মেসেজ দেখুন");
                            jioOtpDialog(num);
                        });
                    });
                })
                .setNegativeButton("বাতিল", null).show();
    }

    private void jioOtpDialog(final String num) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText o = new EditText(this);
        o.setHint("OTP");
        o.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(o, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("OTP দিন").setView(box)
                .setPositiveButton("লগইন", (d, w) -> {
                    final String otp = o.getText().toString().trim();
                    showStatus("লগইন হচ্ছে…");
                    io.execute(() -> {
                        String err = null;
                        try {
                            JioClient.verifyOtp(getApplicationContext(), num, otp);
                            JioClient.fetchChannels(getApplicationContext());
                        } catch (Throwable t) { err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
                        final String e = err;
                        ui.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            all = merge();
                            build();
                            if (e != null) showStatus("Jio: " + e);
                            else showStatus("Jio-তে লগইন হয়েছে। Jio-র " + JioClient.cachedChannels(TvActivity.this).size() + "টি চ্যানেল পাওয়া গেছে, 'Jio চ্যানেল' ক্যাটাগরিতে দেখুন");
                        });
                    });
                })
                .setNegativeButton("বাতিল", null).create();
        dlg.show();
    }

    private void addDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText n = new EditText(this);
        n.setHint("চ্যানেলের নাম");
        final EditText u = new EditText(this);
        u.setHint("স্ট্রিমের লিংক (https://… .m3u8)");
        u.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(n);
        box.addView(u);
        new AlertDialog.Builder(this).setTitle("নিজের চ্যানেল").setView(box)
                .setPositiveButton("যোগ করুন", (d, w) -> {
                    String name = n.getText().toString().trim();
                    String url = u.getText().toString().trim();
                    if (name.isEmpty() || !url.startsWith("http")) {
                        Toast.makeText(this, "নাম আর লিংক দুটোই লাগবে", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    TvChannels.addCustom(this, name, url);
                    all = merge();
                    build();
                    Toast.makeText(this, name + " যোগ হয়েছে", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("বাতিল", null).show();
    }

    private void srcDialog() {
        final EditText u = new EditText(this);
        u.setText(TvChannels.src(this));
        u.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(u, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("M3U তালিকার লিংক").setView(box)
                .setPositiveButton("সেভ ও আনুন", (d, w) -> {
                    String s = u.getText().toString().trim();
                    if (!s.startsWith("http")) { Toast.makeText(this, "লিংক http বা https দিয়ে শুরু হতে হবে", Toast.LENGTH_SHORT).show(); return; }
                    Store.putSetting(this, "tvSrc", s);
                    fetch(true);
                })
                .setNegativeButton("বাতিল", null).show();
    }

    private class Ad extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int p) { return rows.get(p).header ? 0 : 1; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            TextView v = new TextView(parent.getContext());
            v.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            v.setTextColor(0xFFEAF5F3);
            v.setGravity(Gravity.CENTER_VERTICAL);
            if (type == 0) {
                v.setTextSize(15);
                v.setTextColor(0xFFFFB84A);
                v.setTypeface(null, android.graphics.Typeface.BOLD);
                v.setPadding(dp(20), dp(16), dp(20), dp(4));
            } else {
                v.setTextSize(18);
                v.setMinHeight(dp(64));
                v.setPadding(dp(20), dp(10), dp(20), dp(10));
                v.setClickable(true);
                v.setFocusable(true);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
            }
            return new RecyclerView.ViewHolder(v) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            final Row r = rows.get(pos);
            TextView v = (TextView) h.itemView;
            if (r.header) {
                v.setText(r.text);
                v.setContentDescription(r.text);
                ViewCompat.setAccessibilityHeading(v, true);
                v.setOnClickListener(null);
                v.setOnLongClickListener(null);
                return;
            }
            ViewCompat.setAccessibilityHeading(v, false);
            if (r.custom != null) {
                v.setText(r.custom);
                v.setContentDescription(r.custom);
                v.setOnClickListener(x -> play(r.ch));
                v.setOnLongClickListener(x -> { channelMenu(r.ch); return true; });
                return;
            }
            boolean fav = TvChannels.isFavIn(favNow, r.ch);
            String line2 = TvChannels.catText(r.ch);
            int n = r.ch.streams.size();
            String extra = n > 1 ? n + "টি সার্ভার" : "";
            String sub = line2.isEmpty() ? extra : (extra.isEmpty() ? line2 : line2 + " · " + extra);
            v.setText(sub.isEmpty() ? r.ch.name : r.ch.name + "\n" + sub);
            v.setContentDescription(r.ch.name + (sub.isEmpty() ? "" : ", " + sub) + (fav ? ", প্রিয়" : ""));
            v.setOnClickListener(x -> play(r.ch));
            v.setOnLongClickListener(x -> { channelMenu(r.ch); return true; });
        }
    }
}
