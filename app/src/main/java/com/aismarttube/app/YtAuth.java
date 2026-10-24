package com.aismarttube.app;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

/** Google sign-in + OAuth access token for the YouTube Data API (read + like/subscribe). */
public final class YtAuth {
    public static final int REQ_SIGN_IN = 9201;
    public static final int REQ_RECOVER = 9202;
    static final String SCOPE_URL = "https://www.googleapis.com/auth/youtube";

    private static String cachedToken;

    private YtAuth() { }

    private static GoogleSignInClient client(Context c) {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(SCOPE_URL))
                .build();
        return GoogleSignIn.getClient(c, gso);
    }

    public static void signIn(Activity a) {
        a.startActivityForResult(client(a).getSignInIntent(), REQ_SIGN_IN);
    }

    public static boolean isSignedIn(Context c) {
        GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(c);
        return acc != null && acc.getAccount() != null && GoogleSignIn.hasPermissions(acc, new Scope(SCOPE_URL));
    }

    public static String email(Context c) {
        GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(c);
        return acc == null || acc.getEmail() == null ? "" : acc.getEmail();
    }

    public static void signOut(Context c, Runnable done) {
        clearToken(c);
        client(c).signOut().addOnCompleteListener(t -> { if (done != null) done.run(); });
    }

    /** Blocking — call from a background thread. */
    public static String token(Context c) throws Exception {
        if (cachedToken != null) return cachedToken;
        GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(c);
        Account a = acc == null ? null : acc.getAccount();
        if (a == null) throw new Exception("লগইন নেই");
        cachedToken = GoogleAuthUtil.getToken(c.getApplicationContext(), a, "oauth2:" + SCOPE_URL);
        return cachedToken;
    }

    /** Drop the cached token (expired / after a new sign-in). */
    public static void clearToken(Context c) {
        String t = cachedToken;
        cachedToken = null;
        if (t != null) {
            try { GoogleAuthUtil.clearToken(c.getApplicationContext(), t); } catch (Throwable ignored) { }
        }
    }

    /** If the failure is "user must grant permission", the intent to show; otherwise null. */
    public static Intent recoverIntent(Exception e) {
        if (e instanceof UserRecoverableAuthException) return ((UserRecoverableAuthException) e).getIntent();
        return null;
    }
}
