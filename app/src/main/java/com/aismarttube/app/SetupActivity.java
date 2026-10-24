package com.aismarttube.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

/**
 * First launch / settings: skip or save YouTube API Key (+ optional Client ID), then Google login.
 */
public class SetupActivity extends AppCompatActivity {

    private LinearLayout choiceBox, keysBox;
    private EditText apiKey, clientId;
    private boolean pendingLogin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (Store.settingBool(this, "setupDone", false)
                && !getIntent().getBooleanExtra("force", false)) {
            goHome();
            return;
        }

        setContentView(R.layout.activity_setup);
        choiceBox = findViewById(R.id.choiceBox);
        keysBox = findViewById(R.id.keysBox);
        apiKey = findViewById(R.id.apiKey);
        clientId = findViewById(R.id.clientId);

        apiKey.setText(Store.settingStr(this, "ytApiKey", ""));
        clientId.setText(Store.settingStr(this, "ytClientId", ""));

        Button btnGoogle = findViewById(R.id.btnGoogle);
        Button btnSkip = findViewById(R.id.btnSkip);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnBack = findViewById(R.id.btnBack);

        ViewCompat.setAccessibilityHeading(findViewById(R.id.title), true);

        btnGoogle.setOnClickListener(v -> {
            choiceBox.setVisibility(View.GONE);
            keysBox.setVisibility(View.VISIBLE);
            apiKey.requestFocus();
            say("এপিআই কি লিখুন। চাইলে Client ID ও দিন।");
        });

        btnSkip.setOnClickListener(v -> {
            Store.putSettingBool(this, "useGoogle", false);
            Store.putSettingBool(this, "setupDone", true);
            say("লগইন ছাড়া চালু");
            goHome();
        });

        btnBack.setOnClickListener(v -> {
            keysBox.setVisibility(View.GONE);
            choiceBox.setVisibility(View.VISIBLE);
            btnGoogle.requestFocus();
        });

        btnSave.setOnClickListener(v -> {
            String key = apiKey.getText() != null ? apiKey.getText().toString().trim() : "";
            String cid = clientId.getText() != null ? clientId.getText().toString().trim() : "";
            if (key.isEmpty()) {
                say("এপিআই কি লিখুন");
                apiKey.requestFocus();
                return;
            }
            Store.putSetting(this, "ytApiKey", key);
            Store.putSetting(this, "ytClientId", cid);
            Store.putSettingBool(this, "useGoogle", true);
            Store.putSettingBool(this, "setupDone", true);
            pendingLogin = true;
            say("সেভ হয়েছে। এখন Google লগইন…");
            YtAuth.signIn(this);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == YtAuth.REQ_SIGN_IN) {
            try {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
                task.getResult(ApiException.class);
                YtAuth.clearToken(this);
                say("Google লগইন সফল");
            } catch (ApiException e) {
                say("লগইন হয়নি — পরে আপনি ট্যাব থেকে চেষ্টা করুন");
            }
            goHome();
        }
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
