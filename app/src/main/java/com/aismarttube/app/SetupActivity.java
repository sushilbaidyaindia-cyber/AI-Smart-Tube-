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

/**
 * First launch: pick Google path (save API Key + Client ID on device) or skip.
 * Keys stay only on this phone — not in the APK / GitHub.
 * Full Google account sign-in can use the saved Client ID later (SHA-1 must match).
 */
public class SetupActivity extends AppCompatActivity {

    private LinearLayout choiceBox, keysBox;
    private EditText apiKey, clientId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Already finished setup and not forced from settings → home
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
            say("এপিআই কি এবং ক্লায়েন্ট আইডি লিখুন");
        });

        btnSkip.setOnClickListener(v -> {
            Store.putSettingBool(this, "useGoogle", false);
            Store.putSettingBool(this, "setupDone", true);
            say("লগইন ছাড়া চালু হচ্ছে");
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
            say("সেভ হয়েছে। হোমে যাচ্ছে। গুগল অ্যাকাউন্ট লগইন পরে যোগ হবে।");
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

    /** Open from settings later to change keys */
    public static void open(android.content.Context c, boolean force) {
        Intent i = new Intent(c, SetupActivity.class);
        i.putExtra("force", force);
        c.startActivity(i);
    }
}
