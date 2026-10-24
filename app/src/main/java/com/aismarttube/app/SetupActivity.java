package com.aismarttube.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;

/** First launch: sign in with Google (liked videos, playlists, subscriptions) or continue without an account. */
public class SetupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (Store.settingBool(this, "setupDone", false)
                && !getIntent().getBooleanExtra("force", false)) {
            goHome();
            return;
        }

        setContentView(R.layout.activity_setup);
        ViewCompat.setAccessibilityHeading(findViewById(R.id.title), true);

        Button btnGoogle = findViewById(R.id.btnGoogle);
        Button btnSkip = findViewById(R.id.btnSkip);

        btnGoogle.setOnClickListener(v -> {
            Store.putSettingBool(this, "useGoogle", true);
            Store.putSettingBool(this, "setupDone", true);
            YtAuth.signIn(this);
        });

        btnSkip.setOnClickListener(v -> {
            Store.putSettingBool(this, "useGoogle", false);
            Store.putSettingBool(this, "setupDone", true);
            say("লগইন ছাড়া চালু");
            goHome();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == YtAuth.REQ_SIGN_IN) {
            try {
                GoogleSignIn.getSignedInAccountFromIntent(data)
                        .getResult(com.google.android.gms.common.api.ApiException.class);
                YtAuth.clearToken(this);
                say("লগইন সফল। সাবস্ক্রিপশন আনা হচ্ছে…");
                sync();
            } catch (Exception e) {
                say("লগইন হয়নি — পরে \"আপনি\" ট্যাব থেকে চেষ্টা করুন");
                goHome();
            }
        } else if (requestCode == YtAuth.REQ_RECOVER) {
            if (resultCode == RESULT_OK) { YtAuth.clearToken(this); sync(); }
            else goHome();
        }
    }

    private void sync() {
        YtSync.run(this, true, (n, e) -> {
            if (e == null) say(n == 0 ? "নতুন কোনো চ্যানেল নেই" : n + "টি চ্যানেল যোগ হয়েছে");
            goHome();
        });
    }

    private void goHome() {
        Intent i = new Intent(this, BrowseActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private void say(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    public static void open(android.content.Context c, boolean force) {
        Intent i = new Intent(c, SetupActivity.class);
        i.putExtra("force", force);
        c.startActivity(i);
    }
}
