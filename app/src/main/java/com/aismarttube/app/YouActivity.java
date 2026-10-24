package com.aismarttube.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

/** "আপনি" tab: local lists + Google account liked + login. */
public class YouActivity extends AppCompatActivity {

    private static final String[] ROWS = {
            "ইতিহাস",
            "পরে দেখব",
            "পছন্দের ভিডিও",
            "Google লাইক",
            "Google লগইন / লগআউট",
            "API Key সেটআপ",
            "সার্চ ইতিহাস",
            "সেটিংস",
            "গোপনীয়তা"
    };

    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_simple_list);
        ((TextView) findViewById(R.id.barTitle)).setText("আপনি");
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return ROWS.length; }

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
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                TextView v = (TextView) h.itemView;
                String t = ROWS[pos];
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

    private String subtitle(int pos) {
        if (pos <= 2) {
            String key = pos == 0 ? "history" : pos == 1 ? "later" : "liked";
            try {
                String s = Store.get(this, key);
                return bn(s.isEmpty() ? 0 : new JSONArray(s).length()) + "টি ভিডিও";
            } catch (Throwable t) {
                return "";
            }
        }
        if (pos == 3) return "ইউটিউব অ্যাকাউন্টের লাইক";
        if (pos == 4) {
            if (YtAuth.isSignedIn(this)) {
                String e = YtAuth.email(this);
                return e.isEmpty() ? "লগইন আছে — লগআউট করতে চাপুন" : e + " — লগআউট";
            }
            return "লগইন করুন";
        }
        if (pos == 5) return "API Key ও Client ID";
        if (pos == 6) return "আগের সার্চ";
        if (pos == 7) return "AI, সোর্স, প্লেব্যাক";
        if (pos == 8) return "কোন তথ্য কোথায় যায়";
        return "";
    }

    private void open(int pos) {
        if (pos == 0) startActivity(HistoryActivity.open(this, "history"));
        else if (pos == 1) startActivity(HistoryActivity.open(this, "later"));
        else if (pos == 2) startActivity(HistoryActivity.open(this, "liked"));
        else if (pos == 3) startActivity(new Intent(this, YtLikedActivity.class));
        else if (pos == 4) {
            if (YtAuth.isSignedIn(this)) {
                YtAuth.signOut(this, () -> {
                    Toast.makeText(this, "লগআউট হয়েছে", Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged();
                });
            } else {
                YtAuth.signIn(this);
            }
        } else if (pos == 5) SetupActivity.open(this, true);
        else if (pos == 6) startActivity(new Intent(this, SearchNativeActivity.class));
        else startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, pos == 7 ? "settings" : "privacy"));
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
            } catch (Exception e) {
                Toast.makeText(this, "লগইন হয়নি", Toast.LENGTH_SHORT).show();
            }
            adapter.notifyDataSetChanged();
        }
    }
}
