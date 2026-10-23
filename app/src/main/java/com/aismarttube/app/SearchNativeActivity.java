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
    private static final int REQ_VOICE = 77;
    private RecyclerView list;
    private HistAd histAdapter;
    private final List<Object> histRows = new ArrayList<>();

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
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnVoice).setOnClickListener(v -> {
            Intent vi = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            vi.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            vi.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-IN");
            vi.putExtra(RecognizerIntent.EXTRA_PROMPT, "কী খুঁজবেন, বলুন");
            try {
                startActivityForResult(vi, REQ_VOICE);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "এই ফোনে ভয়েস ইনপুট নেই", Toast.LENGTH_SHORT).show();
            }
        });
        histAdapter = new HistAd();
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (s == null || s.toString().trim().isEmpty()) showHistory();
            }
        });
        showHistory();
        query.requestFocus();
    }

    // ------------------------------------------------------------ search history

    private static final class DayHead {
        final String label; final long key; final int count;
        DayHead(String label, long key, int count) { this.label = label; this.key = key; this.count = count; }
    }

    private void showHistory() {
        loadGen++;   // a search that is still running must not paint over the history
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == Activity.RESULT_OK && data != null) {
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
        hideKeyboard();
        String q = query.getText() != null ? query.getText().toString().trim() : "";
        if (q.isEmpty()) {
            status.setText("কিছু লিখে সার্চ করুন");
            return;
        }
        if (Store.settingBool(this, "keepSearch", true)) SearchHistory.add(this, q);
        if (list.getAdapter() != adapter) list.setAdapter(adapter);
        final int gen = ++loadGen;
        status.setText("সার্চ হচ্ছে…");
        adapter.submit(new ArrayList<>());
        NativeFeedLoader.search(q, (items, error) -> main.post(() -> {
            if (gen != loadGen) return;
            if (items == null || items.isEmpty()) {
                status.setText(error != null ? error : "কিছু পাওয়া যায়নি");
                return;
            }
            adapter.submit(items);
            status.setText(items.size() + "টি ফল");
        }));
    }

    @Override
    public void onOpen(VideoItem item, int position) {
        if (item == null || item.url.isEmpty()) {
            Toast.makeText(this, "লিংক নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        NativeFeedLoader.prefetch(item.url);
        java.util.List<VideoItem> all = adapter.items();
        int idx = 0;
        for (int k = 0; k < all.size(); k++) if (all.get(k).url.equals(item.url)) { idx = k; break; }
        startActivity(PlayerActivity.intent(this, all, idx));
    }
}
