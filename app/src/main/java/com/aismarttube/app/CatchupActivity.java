package com.aismarttube.app;

import android.content.Intent;
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

        ListView list = new ListView(this);
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

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
