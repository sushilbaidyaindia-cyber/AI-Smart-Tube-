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

import java.util.ArrayList;

/** Native search results list — same RecyclerView path as Home. */
public class SearchNativeActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private VideoAdapter adapter;
    private TextView status;
    private EditText query;
    private int loadGen = 0;
    private static final int REQ_VOICE = 77;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search_native);

        status = findViewById(R.id.status);
        query = findViewById(R.id.query);
        RecyclerView list = findViewById(R.id.list);
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
        query.requestFocus();
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
