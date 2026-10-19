package com.aismarttube.app;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Watches audio downloads started through the system DownloadManager and shows:
 *   - a notification with the percentage while downloading
 *   - a separate "download complete" notification (tap = open the file) or a failure notice
 * It is a foreground service so the percentage keeps updating even when the app is in the background.
 */
public class DownloadService extends Service {

    static final String EXTRA_ID = "dlid";
    static final String EXTRA_TITLE = "dltitle";
    private static final String CH_PROGRESS = "dl_progress";
    private static final String CH_DONE = "dl_done";
    private static final int FG_ID = 7101;
    private static final String BN = "০১২৩৪৫৬৭৮৯";

    private final Map<Long, String> active = new LinkedHashMap<>();
    private Thread poller;
    private boolean foreground;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String bn(int n) {
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) sb.append(c >= '0' && c <= '9' ? BN.charAt(c - '0') : c);
        return sb.toString();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_ID)) {
            synchronized (active) {
                active.put(intent.getLongExtra(EXTRA_ID, -1), intent.getStringExtra(EXTRA_TITLE));
            }
        }
        goForeground(-1, null);
        startPoller();
        return START_NOT_STICKY;
    }

    private synchronized void startPoller() {
        if (poller != null && poller.isAlive()) return;
        poller = new Thread(this::loop, "dl-poll");
        poller.start();
    }

    private void loop() {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        try {
            while (true) {
                Map<Long, String> snap;
                synchronized (active) {
                    if (active.isEmpty()) break;
                    snap = new LinkedHashMap<>(active);
                }
                int sum = 0, n = 0;
                String firstTitle = null;
                boolean unknown = false;
                for (Map.Entry<Long, String> e : snap.entrySet()) {
                    long id = e.getKey();
                    Cursor c = null;
                    try {
                        c = dm.query(new DownloadManager.Query().setFilterById(id));
                        if (c == null || !c.moveToFirst()) {
                            synchronized (active) { active.remove(id); }
                            continue;
                        }
                        int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            synchronized (active) { active.remove(id); }
                            done(dm, id, e.getValue(), true, 0);
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            int why = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                            synchronized (active) { active.remove(id); }
                            done(dm, id, e.getValue(), false, why);
                        } else {
                            if (firstTitle == null) firstTitle = e.getValue();
                            n++;
                            if (total > 0) sum += (int) (got * 100 / total); else unknown = true;
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    } finally {
                        if (c != null) c.close();
                    }
                }
                if (n > 0) {
                    int pct = unknown && sum == 0 ? -1 : Math.min(100, sum / n);
                    goForeground(pct, n == 1 ? firstTitle : (bn(n) + "টি ডাউনলোড"));
                }
                try { Thread.sleep(800); } catch (InterruptedException ie) { break; }
            }
        } finally {
            try { if (foreground) stopForeground(true); } catch (Throwable ignored) {}
            foreground = false;
            stopSelf();
        }
    }

    private void ensureChannels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            if (nm.getNotificationChannel(CH_PROGRESS) == null) {
                NotificationChannel c = new NotificationChannel(CH_PROGRESS, "ডাউনলোড চলছে", NotificationManager.IMPORTANCE_LOW);
                c.setSound(null, null);
                nm.createNotificationChannel(c);
            }
            if (nm.getNotificationChannel(CH_DONE) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH_DONE, "ডাউনলোড সম্পূর্ণ", NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
    }

    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    /** pct < 0 = size unknown yet (moving bar) */
    private void goForeground(int pct, String title) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannels(nm);
        Notification.Builder b = builder(CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(pct >= 0 ? "ডাউনলোড চলছে — " + bn(pct) + "%" : "ডাউনলোড শুরু হচ্ছে")
                .setContentText(title == null ? "" : title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp());
        b.setProgress(100, Math.max(pct, 0), pct < 0);
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, n);
            }
            foreground = true;
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void done(DownloadManager dm, long id, String title, boolean ok, int why) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannels(nm);
        Notification.Builder b = builder(CH_DONE)
                .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(ok ? "ডাউনলোড সম্পূর্ণ" : "ডাউনলোড ব্যর্থ")
                .setContentText(title == null ? "" : title)
                .setAutoCancel(true);
        PendingIntent pi = openApp();
        if (ok) {
            try {
                Uri u = dm.getUriForDownloadedFile(id);
                if (u != null) {
                    Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "audio/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    pi = PendingIntent.getActivity(this, (int) (id % 10000) + 100, v,
                            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
                }
            } catch (Throwable ignored) {}
            b.setContentText((title == null ? "" : title) + " — Music/AISmartTube ফোল্ডারে সেভ হয়েছে");
        } else {
            b.setContentText((title == null ? "" : title) + " — আবার চেষ্টা করুন (কোড " + why + ")");
        }
        b.setContentIntent(pi);
        if (nm != null) nm.notify(8000 + (int) (id % 1000), b.build());
    }
}
