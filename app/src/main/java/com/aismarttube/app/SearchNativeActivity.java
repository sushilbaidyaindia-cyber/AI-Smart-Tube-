package com.aismarttube.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.app.Activity;
import android.speech.RecognizerIntent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.ActivityNotFoundException;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;

import java.util.ArrayList;
import java.util.List;

/** Native search results list — same RecyclerView path as Home. */
public class SearchNativeActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private VideoAdapter adapter;
    private TextView status;
    private EditText query;
    private int loadGen = 0;
    private static final int REQ_VOICE = 77;       // Bangla
    private static final int REQ_VOICE_EN = 78;    // English
    private String nextTok = "";
    private boolean loadingMore = false;
    private Button btnMore;
    // results, with YouTube-like filters (all / shorts / videos / live / playlists / watched / not watched)
    private static final String[] CHIPS = {"সব", "শর্টস", "ভিডিও", "লাইভ", "প্লেলিস্ট", "দেখা", "না দেখা"};
    private static final int C_ALL = 0, C_SHORTS = 1, C_VIDEO = 2, C_LIVE = 3, C_LIST = 4, C_SEEN = 5, C_UNSEEN = 6;
    private final List<VideoItem> allItems = new ArrayList<>();
    private final List<Button> chipBtns = new ArrayList<>();
    private View chipScroll;
    private int chip = C_ALL;
    private boolean listMode = false;      // the current results are playlists
    private String lastQ = "";
    private int autoChain = 0;
    private java.util.Set<String> watched = new java.util.HashSet<>();
    private RecyclerView list;
    private HistAd histAdapter;
    private final List<Object> histRows = new ArrayList<>();
    private int sugGen = 0;
    private Runnable sugTask;
    private final List<String> sugRows = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> sugAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search_native);

        status = findViewById(R.id.status);
        query = findViewById(R.id.query);
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemViewCacheSize(8);
        adapter = new VideoAdapter(this);
        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnGo).setOnClickListener(v -> runSearch());
        final View clearBtn = findViewById(R.id.btnClear);
        clearBtn.setOnClickListener(v -> {
            query.setText("");
            query.requestFocus();
            try {
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query, 0);
            } catch (Throwable ignored) { }
            query.announceForAccessibility("লেখা মুছে ফেলা হয়েছে");
        });
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnVoice).setOnClickListener(v -> startVoice("bn-IN", "কী খুঁজবেন, বলুন", REQ_VOICE));
        findViewById(R.id.btnVoiceEn).setOnClickListener(v -> startVoice("en-IN", "Say what to search", REQ_VOICE_EN));
        btnMore = findViewById(R.id.btnMore);
        btnMore.setOnClickListener(v -> { autoChain = 0; loadMore(); });
        chipScroll = findViewById(R.id.chipScroll);
        buildChips((LinearLayout) findViewById(R.id.chipRow));
        // the next results load by themselves when the reader comes near the end of the list
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null || rv.getAdapter() != adapter) return;
                if (lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 6) { autoChain = 0; loadMore(); }
            }
        });
        histAdapter = new HistAd();
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String t = s == null ? "" : s.toString().trim();
                clearBtn.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
                if (t.isEmpty()) { sugGen++; showHistory(); }
                else scheduleSuggest(t);
            }
        });
        sugAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return sugRows.size(); }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
                TextView v = new TextView(p.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                v.setMinHeight(dp(56));
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(20), dp(10), dp(20), dp(10));
                v.setTextColor(0xFFEAF5F3);
                v.setTextSize(17);
                v.setClickable(true);
                v.setFocusable(true);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
                return new RecyclerView.ViewHolder(v) { };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                final String sg = sugRows.get(pos);
                TextView v = (TextView) h.itemView;
                v.setText(sg);
                v.setContentDescription("সাজেশন: " + sg);
                v.setOnClickListener(x -> {
                    query.setText(sg);
                    query.setSelection(sg.length());
                    runSearch();
                });
            }
        };
        showHistory();
        query.requestFocus();
    }

    // ------------------------------------------------------------ suggestions while typing

    private void scheduleSuggest(final String t) {
        final int gen = ++sugGen;
        if (sugTask != null) main.removeCallbacks(sugTask);
        sugTask = () -> new Thread(() -> {
            final List<String> r = Suggest.fetch(t);
            main.post(() -> {
                if (gen != sugGen || r.isEmpty()) return;
                sugRows.clear();
                sugRows.addAll(r);
                nextTok = "";
                btnMore.setVisibility(View.GONE);
                chipScroll.setVisibility(View.GONE);
                if (list.getAdapter() != sugAdapter) list.setAdapter(sugAdapter);
                sugAdapter.notifyDataSetChanged();
                status.setText(r.size() + "টি সাজেশন");
            });
        }).start();
        main.postDelayed(sugTask, 250);
    }

    // ------------------------------------------------------------ search history

    private static final class DayHead {
        final String label; final long key; final int count;
        DayHead(String label, long key, int count) { this.label = label; this.key = key; this.count = count; }
    }

    private void showHistory() {
        loadGen++;   // a search that is still running must not paint over the history
        nextTok = "";
        if (btnMore != null) btnMore.setVisibility(View.GONE);
        if (chipScroll != null) chipScroll.setVisibility(View.GONE);
        histRows.clear();
        if (!Store.settingBool(this, "keepSearch", true)) {
            status.setText("সার্চের ইতিহাস রাখা বন্ধ আছে (সেটিংসে চালু করা যায়)");
        } else {
            List<SearchHistory.Entry> l = SearchHistory.list(this);
            if (l.isEmpty()) {
                status.setText("এখনো কোনো সার্চ নেই");
            } else {
                status.setText("আগের সার্চ");
                histRows.add("CLEAR");
                int i = 0;
                while (i < l.size()) {
                    long dk = Dates.dayKey(l.get(i).t);
                    int j = i;
                    while (j < l.size() && Dates.dayKey(l.get(j).t) == dk) j++;
                    histRows.add(new DayHead(Dates.dayLabel(dk), dk, j - i));
                    for (int k = i; k < j; k++) histRows.add(l.get(k));
                    i = j;
                }
            }
        }
        if (list.getAdapter() != histAdapter) list.setAdapter(histAdapter);
        histAdapter.notifyDataSetChanged();
    }

    private void confirmHist(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("মুছুন", (d, w) -> yes.run())
                .setNegativeButton("বাতিল", null).show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button redButton(ViewGroup parent, String text) {
        Button del = new Button(parent.getContext());
        del.setAllCaps(false);
        del.setTextColor(0xFFEAF5F3);
        del.setBackgroundColor(0xFF7A2B2B);
        del.setMinHeight(dp(48));
        del.setMinimumHeight(dp(48));
        del.setPadding(dp(12), 0, dp(12), 0);
        del.setText(text);
        return del;
    }

    private class HistAd extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return histRows.size(); }

        @Override
        public int getItemViewType(int p) {
            Object o = histRows.get(p);
            return o instanceof DayHead ? 1 : o instanceof SearchHistory.Entry ? 2 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == 0) {
                Button b = redButton(parent, "সব সার্চ ইতিহাস মুছুন");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
                RecyclerView.LayoutParams rp = new RecyclerView.LayoutParams(-1, dp(56));
                rp.setMargins(dp(16), dp(8), dp(16), dp(4));
                b.setLayoutParams(rp);
                return new RecyclerView.ViewHolder(b) {};
            }
            if (type == 1) {
                LinearLayout row = new LinearLayout(parent.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                row.setPadding(dp(16), dp(12), dp(8), dp(4));
                TextView t = new TextView(parent.getContext());
                t.setId(android.R.id.text1);
                t.setTextColor(0xFFEAF5F3);
                t.setTextSize(18);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
                Button del = redButton(parent, "এই দিনের সার্চ মুছুন");
                del.setId(android.R.id.button1);
                row.addView(del, new LinearLayout.LayoutParams(-2, dp(48)));
                return new RecyclerView.ViewHolder(row) {};
            }
            TextView v = new TextView(parent.getContext());
            v.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            v.setMinHeight(dp(56));
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setPadding(dp(20), dp(10), dp(20), dp(10));
            v.setTextColor(0xFFEAF5F3);
            v.setTextSize(17);
            v.setClickable(true);
            v.setFocusable(true);
            v.setBackgroundResource(android.R.drawable.list_selector_background);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            Object o = histRows.get(pos);
            if (o instanceof DayHead) {
                final DayHead d = (DayHead) o;
                TextView t = h.itemView.findViewById(android.R.id.text1);
                t.setText(d.label + " · " + Dates.bn(String.valueOf(d.count)) + "টি");
                t.setContentDescription(d.label + ", " + Dates.bn(String.valueOf(d.count)) + "টি সার্চ");
                ViewCompat.setAccessibilityHeading(t, true);
                Button del = h.itemView.findViewById(android.R.id.button1);
                del.setText(d.key == 0 ? "আগের সার্চ মুছুন" : "এই দিনের সার্চ মুছুন");
                del.setContentDescription(d.label + "-এর সার্চ ইতিহাস মুছুন");
                del.setOnClickListener(v -> confirmHist(d.label + "-এর সার্চ মুছবেন?",
                        Dates.bn(String.valueOf(d.count)) + "টি সার্চ সরে যাবে।", () -> {
                            SearchHistory.deleteDay(SearchNativeActivity.this, d.key);
                            showHistory();
                            status.announceForAccessibility("মুছে ফেলা হয়েছে");
                        }));
            } else if (o instanceof SearchHistory.Entry) {
                final SearchHistory.Entry e = (SearchHistory.Entry) o;
                TextView v = (TextView) h.itemView;
                v.setText(e.q);
                v.setContentDescription("আগের সার্চ: " + e.q);
                v.setOnClickListener(x -> {
                    query.setText(e.q);
                    query.setSelection(query.getText().length());
                    runSearch();
                });
                ViewCompat.addAccessibilityAction(v, "তালিকা থেকে সরান", (view, args) -> {
                    SearchHistory.remove(SearchNativeActivity.this, e.q);
                    showHistory();
                    return true;
                });
            } else {
                Button b = (Button) h.itemView;
                b.setOnClickListener(v -> confirmHist("সব সার্চ ইতিহাস মুছবেন?", "আগের সব সার্চ সরে যাবে।", () -> {
                    SearchHistory.clear(SearchNativeActivity.this);
                    showHistory();
                    status.announceForAccessibility("সব সার্চ ইতিহাস মুছে ফেলা হয়েছে");
                }));
            }
        }
    }

    private void startVoice(String lang, String prompt, int code) {
        Intent vi = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        vi.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        vi.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        vi.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        try {
            startActivityForResult(vi, code);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "এই ফোনে ভয়েস ইনপুট নেই", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQ_VOICE || requestCode == REQ_VOICE_EN) && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                query.setText(r.get(0));
                runSearch();
            }
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus();
        } catch (Throwable ignored) {}
    }

    private void runSearch() {
        sugGen++;   // a pending suggestion must not paint over the results
        hideKeyboard();
        String q = query.getText() != null ? query.getText().toString().trim() : "";
        if (q.isEmpty()) {
            status.setText("কিছু লিখে সার্চ করুন");
            return;
        }
        if (Store.settingBool(this, "keepSearch", true)) SearchHistory.add(this, q);
        lastQ = q;
        chip = C_ALL;
        paintChips();
        startSearch(false);
    }

    /** a fresh search for lastQ: videos, or playlists when the playlists filter is chosen */
    private void startSearch(boolean playlists) {
        if (list.getAdapter() != adapter) list.setAdapter(adapter);
        listMode = playlists;
        final int gen = ++loadGen;
        status.setText("সার্চ হচ্ছে…");
        nextTok = "";
        loadingMore = false;
        autoChain = 0;
        allItems.clear();
        btnMore.setVisibility(View.GONE);
        chipScroll.setVisibility(View.VISIBLE);
        adapter.submit(new ArrayList<>());
        NativeFeedLoader.searchPaged(lastQ, playlists ? "playlists" : "videos", (items, next, error) -> main.post(() -> {
            if (gen != loadGen) return;
            if (items == null || items.isEmpty()) {
                status.setText(error != null ? error : "কিছু পাওয়া যায়নি");
                return;
            }
            allItems.addAll(items);
            nextTok = next == null ? "" : next;
            applyFilter();
            list.post(this::loadMoreIfShort);
        }));
    }

    // ------------------------------------------------------------ filters (chips)

    private void buildChips(LinearLayout row) {
        for (int i = 0; i < CHIPS.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(CHIPS[i]);
            b.setMinHeight(dp(44));
            b.setMinimumHeight(dp(44));
            b.setMinWidth(dp(56));
            b.setMinimumWidth(dp(56));
            b.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            b.setOnClickListener(v -> pickChip(idx));
            row.addView(b, lp);
            chipBtns.add(b);
        }
        paintChips();
    }

    private void paintChips() {
        for (int i = 0; i < chipBtns.size(); i++) {
            Button b = chipBtns.get(i);
            boolean on = i == chip;
            b.setBackgroundColor(on ? 0xFFEAF5F3 : 0xFF183039);
            b.setTextColor(on ? 0xFF09161A : 0xFFEAF5F3);
            b.setContentDescription(CHIPS[i] + (on ? ", নির্বাচিত" : ""));
            b.setSelected(on);
        }
    }

    private void pickChip(int idx) {
        if (idx == chip || lastQ.isEmpty()) return;
        chip = idx;
        paintChips();
        autoChain = 0;
        boolean wantList = idx == C_LIST;
        if (wantList != listMode) {          // playlists are a separate search
            startSearch(wantList);
            return;
        }
        applyFilter();
        list.post(this::loadMoreIfShort);
    }

    private boolean isShort(VideoItem v) {
        return v.shortForm || (!v.live && v.duration > 0 && v.duration <= 60);
    }

    private boolean passes(VideoItem v) {
        if (listMode) return "playlist".equals(v.kind);
        if ("playlist".equals(v.kind)) return false;
        switch (chip) {
            case C_SHORTS: return isShort(v);
            case C_VIDEO: return !isShort(v) && !v.live;
            case C_LIVE: return v.live;
            case C_SEEN: return watched.contains(v.url);
            case C_UNSEEN: return !watched.contains(v.url);
            default: return true;
        }
    }

    /** shows the loaded results that match the chosen filter */
    private void applyFilter() {
        watched = Store.historyUrls(this);
        List<VideoItem> shown = new ArrayList<>();
        for (VideoItem v : allItems) if (passes(v)) shown.add(v);
        adapter.submit(shown);
        btnMore.setVisibility(nextTok.isEmpty() ? View.GONE : View.VISIBLE);
        if (!shown.isEmpty()) status.setText(CHIPS[chip] + ": " + shown.size() + "টি ফল");
        else status.setText(nextTok.isEmpty() ? "এই ধরনের কিছু পাওয়া যায়নি" : "এই ধরনের ফল এখনও পাওয়া যায়নি, আরও খোঁজা হচ্ছে…");
    }

    private void loadMoreIfShort() {
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        if (lm == null || list.getAdapter() != adapter) return;
        if (lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 6) loadMore();
    }

    /** the next page of results; those that match the filter are added below the ones already shown */
    private void loadMore() {
        if (nextTok.isEmpty() || loadingMore || list.getAdapter() != adapter) return;
        loadingMore = true;
        btnMore.setEnabled(false);
        btnMore.setText("লোড হচ্ছে…");
        final int gen = loadGen;
        final String tok = nextTok;
        NativeFeedLoader.searchMore(tok, (items, next, error) -> main.post(() -> {
            if (gen != loadGen) return;      // a newer search started meanwhile
            loadingMore = false;
            btnMore.setEnabled(true);
            btnMore.setText("আরও দেখুন");
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
                return;          // the button stays, so the reader can try again
            }
            java.util.Set<String> have = new java.util.HashSet<>();
            for (VideoItem v : allItems) have.add(v.url);
            List<VideoItem> fresh = new ArrayList<>();
            for (VideoItem v : items) if (have.add(v.url)) { allItems.add(v); if (passes(v)) fresh.add(v); }
            adapter.append(fresh);
            nextTok = next == null ? "" : next;
            btnMore.setVisibility(nextTok.isEmpty() ? View.GONE : View.VISIBLE);
            if (adapter.getItemCount() > 0) status.setText(CHIPS[chip] + ": " + adapter.getItemCount() + "টি ফল");
            else status.setText(nextTok.isEmpty() ? "এই ধরনের কিছু পাওয়া যায়নি" : "এই ধরনের ফল এখনও পাওয়া যায়নি, আরও খোঁজা হচ্ছে…");
            // keep going by itself while the screen is not full yet, but not forever on a rare filter
            if (!items.isEmpty() && autoChain < 6) { autoChain++; list.post(this::loadMoreIfShort); }
        }));
    }

    /** a playlist from the results: its videos are fetched and played one after another */
    private void openPlaylist(final VideoItem pl) {
        Toast.makeText(this, "প্লেলিস্ট খোলা হচ্ছে…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<VideoItem> vids = new ArrayList<>();
            String msg = "প্লেলিস্ট আনা যায়নি";
            try {
                org.json.JSONObject d = new org.json.JSONObject(NewPipeHelper.getPlaylist(pl.url));
                if (d.optBoolean("ok", false)) {
                    org.json.JSONArray a = d.optJSONArray("items");
                    for (int i = 0; a != null && i < a.length(); i++) {
                        VideoItem v = VideoItem.fromJson(a.optJSONObject(i));
                        if (v != null && !v.url.isEmpty() && !"playlist".equals(v.kind)) vids.add(v);
                    }
                } else {
                    msg = d.optString("error", msg);
                }
            } catch (Throwable ignored) {}
            final String fmsg = msg;
            main.post(() -> {
                if (isFinishing()) return;
                if (vids.isEmpty()) { Toast.makeText(this, fmsg, Toast.LENGTH_SHORT).show(); return; }
                startActivity(PlayerActivity.intent(this, vids, 0, true));
            });
        }).start();
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        if (item == null || item.url.isEmpty()) {
            Toast.makeText(this, "লিংক নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("playlist".equals(item.kind)) { openPlaylist(item); return; }
        NativeFeedLoader.prefetch(item.url);
        java.util.List<VideoItem> all = adapter.items();
        int idx = 0;
        for (int k = 0; k < all.size(); k++) if (all.get(k).url.equals(item.url)) { idx = k; break; }
        startActivity(PlayerActivity.intent(this, all, idx));
    }
}
