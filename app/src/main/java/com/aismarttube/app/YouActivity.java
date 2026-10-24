package com.aismarttube.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

/** "আপনি" tab, laid out like YouTube's: account, history, playlists, watch later, liked videos, settings. */
public class YouActivity extends AppCompatActivity {

    private static final int ACCOUNT = 0, HISTORY = 1, PLAYLISTS = 2, LATER = 3, LIKED = 4, SETTINGS = 5, COUNT = 6;

    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        ((TextView) findViewById(R.id.barTitle)).setText("আপনি");
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return COUNT; }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
                TextView v = new TextView(p.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                v.setMinHeight(dp(64));
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(20), dp(12), dp(20), dp(12));
                v.setTextColor(0xFFEAF5F3);
                v.setTextSize(18);
                v.setClickable(true);
                v.setFocusable(true);
                v.setBackgroundResource(android.R.drawable.list_selector_background);
                return new RecyclerView.ViewHolder(v) { };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                TextView v = (TextView) h.itemView;
                String t = title(pos);
                String sub = subtitle(pos);
                v.setText(sub.isEmpty() ? t : t + "\n" + sub);
                v.setContentDescription(sub.isEmpty() ? t : t + ", " + sub);
                v.setOnClickListener(x -> open(pos));
            }
        };
        list.setAdapter(adapter);
        TabBar.setup(this, TabBar.YOU);
    }

    @Override
    public void onBackPressed() {
        TabBar.go(this, TabBar.YOU, TabBar.HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.notifyDataSetChanged();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String bn(int n) {
        String d = "০১২৩৪৫৬৭৮৯", t = String.valueOf(n);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            s.append(c >= '0' && c <= '9' ? d.charAt(c - '0') : c);
        }
        return s.toString();
    }

    private String title(int pos) {
        switch (pos) {
            case ACCOUNT: return YtAuth.isSignedIn(this)
                    ? (YtAuth.email(this).isEmpty() ? "Google অ্যাকাউন্ট" : YtAuth.email(this))
                    : "Google দিয়ে লগইন";
            case HISTORY: return "ইতিহাস";
            case PLAYLISTS: return "প্লেলিস্ট";
            case LATER: return "পরে দেখব";
            case LIKED: return "পছন্দের ভিডিও";
            default: return "সেটিংস";
        }
    }

    private String count(String key) {
        try {
            String s = Store.get(this, key);
            return bn(s.isEmpty() ? 0 : new JSONArray(s).length()) + "টি ভিডিও";
        } catch (Throwable t) {
            return "";
        }
    }

    private String subtitle(int pos) {
        switch (pos) {
            case ACCOUNT: return YtAuth.isSignedIn(this) ? "সাবস্ক্রিপশন আনা বা লগআউট করতে চাপুন"
                    : "সাবস্ক্রিপশন, লাইক ও প্লেলিস্ট পেতে";
            case HISTORY: return count("history");
            case PLAYLISTS: return YtAuth.isSignedIn(this) ? "আপনার ইউটিউব প্লেলিস্ট" : "লগইন করলে দেখা যাবে";
            case LATER: return count("later");
            case LIKED: return count("liked");
            default: return "AI, সোর্স, প্লেব্যাক";
        }
    }

    private void open(int pos) {
        switch (pos) {
            case ACCOUNT:
                if (YtAuth.isSignedIn(this)) accountMenu(); else YtAuth.signIn(this);
                break;
            case HISTORY: startActivity(HistoryActivity.open(this, "history")); break;
            case PLAYLISTS:
                if (YtAuth.isSignedIn(this)) startActivity(new Intent(this, YtPlaylistsActivity.class));
                else Toast.makeText(this, "আগে Google লগইন করুন", Toast.LENGTH_SHORT).show();
                break;
            case LATER: startActivity(HistoryActivity.open(this, "later")); break;
            case LIKED: startActivity(new Intent(this, YtLikedActivity.class)); break;
            default:
                startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_TAB, "settings"));
        }
    }

    private void accountMenu() {
        new AlertDialog.Builder(this)
                .setTitle(YtAuth.email(this))
                .setItems(new String[]{"সাবস্ক্রিপশন আবার আনুন", "লগআউট"}, (d, which) -> {
                    if (which == 0) syncSubs();
                    else YtAuth.signOut(this, () -> {
                        Toast.makeText(this, "লগআউট হয়েছে", Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    });
                })
                .setNegativeButton("থাক", null)
                .show();
    }

    private void syncSubs() {
        Toast.makeText(this, "সাবস্ক্রিপশন আনা হচ্ছে…", Toast.LENGTH_SHORT).show();
        YtSync.run(this, true, (n, e) -> {
            if (e != null) Toast.makeText(this, "আনা যায়নি: " + e.getMessage(), Toast.LENGTH_LONG).show();
            else Toast.makeText(this, n == 0 ? "নতুন কোনো চ্যানেল নেই" : bn(n) + "টি চ্যানেল যোগ হয়েছে", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == YtAuth.REQ_SIGN_IN) {
            try {
                com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data)
                        .getResult(com.google.android.gms.common.api.ApiException.class);
                YtAuth.clearToken(this);
                Toast.makeText(this, "লগইন হয়েছে", Toast.LENGTH_SHORT).show();
                syncSubs();   // asks for the YouTube permission now, once
            } catch (Exception e) {
                Toast.makeText(this, "লগইন হয়নি", Toast.LENGTH_SHORT).show();
            }
            adapter.notifyDataSetChanged();
        } else if (requestCode == YtAuth.REQ_RECOVER) {
            if (resultCode == RESULT_OK) { YtAuth.clearToken(this); syncSubs(); }
            else Toast.makeText(this, "অনুমতি দেওয়া হয়নি", Toast.LENGTH_SHORT).show();
        }
    }
}
