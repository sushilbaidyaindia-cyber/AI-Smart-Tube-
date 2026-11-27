package com.aismarttube.app;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** What was downloaded (M4A / MP3): play here, skip 10 s, delete. */
public class DownloadsActivity extends AppCompatActivity {

    private static final class Row {
        int index;          // position in the saved list
        String title, type, uri;
        long dm;
        String state;       // "ready" | "running" | "failed"
        int percent;
    }

    private final List<Row> rows = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private TextView status;
    private ExoPlayer player;
    private int playing = -1;   // row position being played

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        ((TextView) findViewById(R.id.barTitle)).setText("ডাউনলোড");
        status = findViewById(R.id.status);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return rows.size(); }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(p.getContext());
                row.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView v = new TextView(p.getContext());
                v.setId(android.R.id.text1);
                v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                v.setMinHeight(dp(64));
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(20), dp(12), dp(8), dp(12));
                v.setTextColor(0xFFEAF5F3);
                v.setTextSize(17);
                v.setClickable(true);
                v.setFocusable(true);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
                row.addView(v);
                android.widget.Button del = new android.widget.Button(p.getContext());
                del.setId(android.R.id.button1);
                del.setText("মুছুন");
                del.setAllCaps(false);
                del.setMinWidth(dp(72));
                del.setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
                ((android.widget.LinearLayout.LayoutParams) del.getLayoutParams()).setMargins(0, 0, dp(12), 0);
                row.addView(del);
                return new RecyclerView.ViewHolder(row) { };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                final Row r = rows.get(pos);
                TextView v = h.itemView.findViewById(android.R.id.text1);
                android.widget.Button del = h.itemView.findViewById(android.R.id.button1);
                String st = "ready".equals(r.state)
                        ? (pos == playing && player != null && player.isPlaying() ? "চলছে" : "প্রস্তুত")
                        : "running".equals(r.state) ? "নামছে " + VideoItem.formatCountBn(r.percent) + "%" : "ব্যর্থ";
                String line = r.type.toUpperCase() + " · " + st;
                v.setText(r.title + "\n" + line);
                v.setContentDescription(r.title + ", " + line);
                v.setOnClickListener(x -> tap(pos));
                v.setOnLongClickListener(x -> { confirmDelete(r); return true; });
                ViewCompat.addAccessibilityAction(v, "১০ সেকেন্ড সামনে", (view, args) -> { skip(pos, 10000); return true; });
                ViewCompat.addAccessibilityAction(v, "১০ সেকেন্ড পিছনে", (view, args) -> { skip(pos, -10000); return true; });
                del.setContentDescription("মুছুন: " + r.title);
                del.setOnClickListener(x -> confirmDelete(r));
            }
        };
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        rows.clear();
        JSONArray a = DownloadLog.all(this);
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Row r = new Row();
            r.index = i;
            r.title = o.optString("title", "অডিও");
            r.type = o.optString("type", "m4a");
            r.uri = o.optString("uri", "");
            r.dm = o.optLong("dm", 0);
            r.state = "ready";
            if (r.dm > 0 && dm != null) {
                r.state = "failed";
                Cursor c = null;
                try {
                    c = dm.query(new DownloadManager.Query().setFilterById(r.dm));
                    if (c != null && c.moveToFirst()) {
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (st == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri u = dm.getUriForDownloadedFile(r.dm);
                            r.uri = u == null ? "" : u.toString();
                            r.state = r.uri.isEmpty() ? "failed" : "ready";
                        } else if (st == DownloadManager.STATUS_FAILED) {
                            r.state = "failed";
                        } else {
                            r.state = "running";
                            long tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            r.percent = tot > 0 ? (int) (got * 100 / tot) : 0;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                }
            }
            rows.add(r);
        }
        adapter.notifyDataSetChanged();
        status.setVisibility(View.VISIBLE);
        status.setText(rows.isEmpty()
                ? "এখনো কিছু ডাউনলোড করা হয়নি। প্লেয়ারে M4A বা MP3 ডাউনলোড বোতাম আছে।"
                : rows.size() + "টি ডাউনলোড। চাপলে চলবে। প্রতিটির পাশে মুছুন বোতাম আছে।");
    }

    private void tap(int pos) {
        if (pos < 0 || pos >= rows.size()) return;
        Row r = rows.get(pos);
        if ("running".equals(r.state)) { toast("এখনও নামছে, একটু অপেক্ষা করুন"); return; }
        if (!"ready".equals(r.state) || r.uri.isEmpty()) { toast("এই ডাউনলোডটি ব্যর্থ হয়েছে। মুছে আবার চেষ্টা করুন।"); return; }
        if (pos == playing && player != null) {   // same row: pause / resume
            if (player.isPlaying()) player.pause(); else player.play();
            adapter.notifyItemChanged(pos);
            return;
        }
        play(pos);
    }

    private void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(this).setHandleAudioBecomingNoisy(true).build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_ENDED) nextFrom(playing);
            }

            @Override
            public void onIsPlayingChanged(boolean p) {
                if (playing >= 0 && playing < rows.size()) adapter.notifyItemChanged(playing);
            }
        });
    }

    private void play(int pos) {
        Row r = rows.get(pos);
        try { PlayerActivity.yieldToOthers(); } catch (Throwable ignored) { }
        try { RadioHub.stopForOther(); } catch (Throwable ignored) { }
        ensurePlayer();
        int old = playing;
        playing = pos;
        player.setMediaItem(MediaItem.fromUri(Uri.parse(r.uri)));
        player.prepare();
        player.play();
        if (old >= 0 && old < rows.size()) adapter.notifyItemChanged(old);
        adapter.notifyItemChanged(pos);
        status.setText("চলছে: " + r.title);
    }

    private void nextFrom(int pos) {
        for (int i = pos + 1; i < rows.size(); i++) {
            if ("ready".equals(rows.get(i).state) && !rows.get(i).uri.isEmpty()) { play(i); return; }
        }
        status.setText("সব শেষ");
    }

    private void skip(int pos, long deltaMs) {
        if (player == null || pos != playing) { toast("আগে এটা চালু করুন"); return; }
        long t = Math.max(0, player.getCurrentPosition() + deltaMs);
        long d = player.getDuration();
        if (d != androidx.media3.common.C.TIME_UNSET && t > d) t = d;
        player.seekTo(t);
    }

    private void confirmDelete(final Row r) {
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setMessage("এই ডাউনলোড মুছবেন? ফাইলটিও ফোন থেকে মুছে যাবে।")
                .setPositiveButton("মুছুন", (d, w) -> deleteRow(r))
                .setNegativeButton("থাক", null)
                .show();
    }

    private static final int REQ_DEL = 91;
    private Row delRow;

    private void deleteRow(Row r) {
        boolean gone = true;
        try {
            if (playing >= 0 && playing < rows.size() && rows.get(playing) == r && player != null) {
                player.stop();
                playing = -1;
            }
            if (r.dm > 0) {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) dm.remove(r.dm);
            } else if (!r.uri.isEmpty()) {
                Uri u = Uri.parse(r.uri);
                if (ContentResolver.SCHEME_CONTENT.equals(u.getScheme())) {
                    try {
                        getContentResolver().delete(u, null, null);
                    } catch (SecurityException se) {
                        // the file is not owned by this install: ask the user to allow the delete
                        gone = false;
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            delRow = r;
                            android.app.PendingIntent pi = android.provider.MediaStore.createDeleteRequest(
                                    getContentResolver(), java.util.Collections.singletonList(u));
                            startIntentSenderForResult(pi.getIntentSender(), REQ_DEL, null, 0, 0, 0);
                            return;
                        }
                    }
                } else if (u.getPath() != null) {
                    File f = new File(u.getPath());
                    if (f.exists()) gone = f.delete();
                }
            }
        } catch (Throwable e) {
            gone = false;
        }
        if (!gone) { toast("ফাইলটি মোছা যায়নি"); return; }
        DownloadLog.removeAt(this, r.index);
        refresh();
        status.announceForAccessibility("মুছে ফেলা হয়েছে");
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DEL && delRow != null) {
            if (res == RESULT_OK) {
                DownloadLog.removeAt(this, delRow.index);
                refresh();
                status.announceForAccessibility("মুছে ফেলা হয়েছে");
            } else {
                toast("মোছা হয়নি");
            }
            delRow = null;
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        super.onDestroy();
    }
}
