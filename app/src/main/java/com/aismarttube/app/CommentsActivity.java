package com.aismarttube.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.stream.Description;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Comments of the video that is playing (read only, via NewPipe). */
public class CommentsActivity extends AppCompatActivity {
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final class Row {
        String head, body, tr = "";
        boolean showTr, busy;
        Row(String h, String b) { head = h; body = b; }
    }

    private final List<Row> rows = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private TextView status;
    private CommentsInfo info;
    private Page nextPage;
    private boolean loading;
    private String url = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        ((TextView) findViewById(R.id.barTitle)).setText("মন্তব্য");
        status = findViewById(R.id.status);
        url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) url = "";
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return rows.size() + (nextPage != null ? 1 : 0); }
            @Override public int getItemViewType(int pos) { return pos < rows.size() ? 0 : 1; }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
                LinearLayout box = new LinearLayout(p.getContext());
                box.setOrientation(LinearLayout.VERTICAL);
                box.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                box.setPadding(dp(20), dp(10), dp(20), dp(10));
                TextView v = new TextView(p.getContext());
                v.setId(android.R.id.text1);
                v.setMinHeight(dp(48));
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setTextColor(0xFFEAF5F3);
                v.setTextSize(16);
                v.setFocusable(true);
                box.addView(v, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                Button tb = new Button(p.getContext());
                tb.setId(android.R.id.button1);
                tb.setAllCaps(false);
                tb.setMinHeight(dp(44));
                tb.setMinimumHeight(dp(44));
                tb.setTextColor(0xFFEAF5F3);
                tb.setBackgroundColor(0xFF183039);
                box.addView(tb, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(box) { };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                TextView v = h.itemView.findViewById(android.R.id.text1);
                Button tb = h.itemView.findViewById(android.R.id.button1);
                if (pos < rows.size()) {
                    final Row r = rows.get(pos);
                    String body = r.showTr && !r.tr.isEmpty() ? r.tr : r.body;
                    v.setText(r.head + "\n" + body);
                    v.setContentDescription(r.head + ". " + (r.showTr && !r.tr.isEmpty() ? "বাংলা অনুবাদ: " : "") + body);
                    v.setClickable(false);
                    v.setBackground(null);
                    tb.setVisibility(mostlyBangla(r.body) && r.tr.isEmpty() ? View.GONE : View.VISIBLE);   // no button for comments already in Bangla
                    String label = r.busy ? "অনুবাদ হচ্ছে…" : r.showTr ? "মূল লেখা দেখুন" : "বাংলায় অনুবাদ করুন";
                    tb.setText(label);
                    tb.setEnabled(!r.busy);
                    tb.setContentDescription(label);
                    tb.setOnClickListener(x -> translate(r));
                } else {
                    tb.setVisibility(View.GONE);
                    v.setText("আরও মন্তব্য দেখুন");
                    v.setContentDescription("আরও মন্তব্য দেখুন");
                    v.setClickable(true);
                    v.setBackgroundResource(android.R.drawable.list_selector_background);
                    v.setOnClickListener(x -> loadMore());
                }
            }
        };
        list.setAdapter(adapter);
        first();
    }

    private static boolean mostlyBangla(String t) {
        int bn = 0, other = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x0980 && c <= 0x09FF) bn++;
            else if (Character.isLetter(c)) other++;
        }
        return bn > 0 && bn >= other;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void say(String s) {
        status.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        status.setText(s);
        if (s != null && !s.isEmpty()) status.announceForAccessibility(s);
    }

    private void translate(final Row r) {
        if (r.busy) return;
        if (!r.tr.isEmpty()) {            // already translated: flip between original and Bangla
            r.showTr = !r.showTr;
            adapter.notifyItemChanged(rows.indexOf(r));
            return;
        }
        r.busy = true;
        adapter.notifyItemChanged(rows.indexOf(r));
        io.execute(() -> {
            try {
                final String[] res = Translator.toBangla(r.body);
                ui.post(() -> {
                    r.busy = false;
                    if ("bn".equals(res[1])) {
                        say("এই মন্তব্য আগে থেকেই বাংলায়");
                    } else if (res[0].isEmpty()) {
                        say("অনুবাদ পাওয়া যায়নি");
                    } else {
                        r.tr = res[0];
                        r.showTr = true;
                    }
                    adapter.notifyItemChanged(rows.indexOf(r));
                });
            } catch (final Throwable e) {
                ui.post(() -> {
                    r.busy = false;
                    adapter.notifyItemChanged(rows.indexOf(r));
                    say("অনুবাদ হয়নি: " + e.getMessage());
                });
            }
        });
    }

    private void first() {
        if (url.isEmpty()) { say("ভিডিওর লিংক নেই"); return; }
        loading = true;
        say("মন্তব্য আনা হচ্ছে…");
        io.execute(() -> {
            try {
                NewPipeHelper.ensureInit();
                final CommentsInfo ci = CommentsInfo.getInfo(url);
                final List<Row> got = convert(ci.getRelatedItems());
                final Page np = ci.hasNextPage() ? ci.getNextPage() : null;
                ui.post(() -> {
                    info = ci;
                    rows.addAll(got);
                    nextPage = np;
                    adapter.notifyDataSetChanged();
                    loading = false;
                    say(got.isEmpty() ? "কোনো মন্তব্য নেই, বা এই ভিডিওতে মন্তব্য বন্ধ" : got.size() + "টি মন্তব্য");
                });
            } catch (final Throwable e) {
                ui.post(() -> { loading = false; say("মন্তব্য আনা যায়নি: " + e.getMessage()); });
            }
        });
    }

    private void loadMore() {
        if (loading || info == null || nextPage == null) return;
        loading = true;
        say("আরও আনা হচ্ছে…");
        final Page pg = nextPage;
        io.execute(() -> {
            try {
                // the exact method shape differs between extractor versions, so it is looked up by name
                Object page = null;
                for (Method m : CommentsInfo.class.getMethods()) {
                    if (!m.getName().equals("getMoreItems")) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 3) { page = m.invoke(null, ServiceList.YouTube, info, pg); break; }
                    if (pt.length == 2 && pt[1] == Page.class) { page = m.invoke(null, info, pg); break; }
                }
                if (page == null) throw new Exception("এই সংস্করণে আরও মন্তব্য আনা যাচ্ছে না");
                Method gi = page.getClass().getMethod("getItems");
                Method hn = page.getClass().getMethod("hasNextPage");
                Method np = page.getClass().getMethod("getNextPage");
                @SuppressWarnings("unchecked")
                final List<CommentsInfoItem> items = (List<CommentsInfoItem>) gi.invoke(page);
                final Page next = ((Boolean) hn.invoke(page)) ? (Page) np.invoke(page) : null;
                final List<Row> got = convert(items);
                ui.post(() -> {
                    rows.addAll(got);
                    nextPage = next;
                    adapter.notifyDataSetChanged();
                    loading = false;
                    say(rows.size() + "টি মন্তব্য");
                });
            } catch (final Throwable e) {
                ui.post(() -> { loading = false; say("আরও আনা যায়নি: " + e.getMessage()); });
            }
        });
    }

    private static List<Row> convert(List<CommentsInfoItem> items) {
        List<Row> out = new ArrayList<>();
        if (items == null) return out;
        for (CommentsInfoItem c : items) {
            String name = c.getUploaderName() == null ? "" : c.getUploaderName();
            Object t = c.getCommentText();
            String text = t instanceof Description ? ((Description) t).getContent() : String.valueOf(t);
            text = text == null ? "" : text.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&").replaceAll("&quot;", "\"").replaceAll("&#39;", "'")
                    .replaceAll("[ \\t]+", " ").trim();
            StringBuilder head = new StringBuilder(name.isEmpty() ? "দর্শক" : name);
            String when = c.getTextualUploadDate();
            if (when != null && !when.trim().isEmpty()) head.append(" · ").append(VideoItem.formatCountBn(when.trim()));
            long likes = c.getLikeCount();
            if (likes > 0) head.append(" · ").append(VideoItem.formatCountBn(likes)).append(" লাইক");
            out.add(new Row(head.toString(), text.isEmpty() ? "(লেখা নেই)" : text));
        }
        return out;
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
