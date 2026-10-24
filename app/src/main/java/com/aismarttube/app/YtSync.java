package com.aismarttube.app;

import android.app.Activity;
import android.content.Intent;

/** Brings the YouTube account's subscriptions into the app's own subscription list. */
public final class YtSync {
    public interface Done { void done(int added, Exception error); }

    private static final long EVERY_MS = 6L * 60 * 60 * 1000;
    private static boolean running = false;

    private YtSync() { }

    /**
     * @param interactive true: if Google needs the user's permission, show that screen
     *                    (the activity must call run() again from onActivityResult(REQ_RECOVER)).
     */
    public static void run(final Activity a, final boolean interactive, final Done cb) {
        new Thread(() -> {
            try {
                final int n = YtApi.importSubscriptions(a);
                try { YtApi.importLikes(a); } catch (Throwable ignored) { }   // likes: best effort
                Store.putSetting(a, "lastSubSync", String.valueOf(System.currentTimeMillis()));
                a.runOnUiThread(() -> { if (cb != null) cb.done(n, null); });
            } catch (final Exception e) {
                final Intent r = YtAuth.recoverIntent(e);
                a.runOnUiThread(() -> {
                    if (r != null && interactive) {
                        try { a.startActivityForResult(r, YtAuth.REQ_RECOVER); return; } catch (Throwable ignored) { }
                    }
                    if (cb != null) cb.done(0, e);
                });
            }
        }).start();
    }

    /** Quiet refresh when a subscription screen opens: only if signed in and the last sync is old. */
    public static void auto(final Activity a, final Runnable changed) {
        if (running || !YtAuth.isSignedIn(a)) return;
        long last = 0;
        try { last = Long.parseLong(Store.settingStr(a, "lastSubSync", "0")); } catch (Throwable ignored) { }
        if (System.currentTimeMillis() - last < EVERY_MS) return;
        running = true;
        run(a, false, (n, e) -> {
            running = false;
            if (e == null && n > 0 && changed != null) changed.run();
        });
    }
}
