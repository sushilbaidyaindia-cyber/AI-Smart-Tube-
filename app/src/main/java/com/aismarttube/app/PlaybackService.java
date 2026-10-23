package com.aismarttube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the app alive while the screen is off, so the video/audio keeps playing.
 * Shows the usual media notification (title + previous / play-pause / next / stop).
 * The actual sound still comes from the page's <video>; this service only holds the process,
 * the CPU and the Wi-Fi awake.
 */
public class PlaybackService extends Service {

    static final String ACTION_UPDATE = "com.aismarttube.app.UPDATE";
    static final String ACTION_CMD = "com.aismarttube.app.CMD";
    static final String ACTION_STOP = "com.aismarttube.app.STOP";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_PLAYING = "playing";
    static final String EXTRA_CMD = "cmd";
    private static final String CHANNEL = "playback";
    private static final int NOTE_ID = 7001;

    /** Set by MainActivity so the notification can show the lock-screen media card. */
    static volatile MediaSession.Token sessionToken;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean foreground;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_UPDATE : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            MainActivity.dispatch("pause");
            RadioHub.stop(true);
            shutDown();
            return START_NOT_STICKY;
        }
        if (ACTION_CMD.equals(action)) {
            MainActivity.dispatch(intent.getStringExtra(EXTRA_CMD));
            return START_NOT_STICKY;
        }
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        boolean playing = intent != null && intent.getBooleanExtra(EXTRA_PLAYING, false);
        show(title == null || title.isEmpty() ? "AI Smart Tube" : title, playing);
        return START_NOT_STICKY;
    }

    private void show(String title, boolean playing) {
        Notification n = build(title, playing);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTE_ID, n);
            }
            foreground = true;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        if (playing) acquire(); else release();
    }

    private PendingIntent cmd(String c, int req) {
        Intent i = new Intent(this, PlaybackService.class).setAction(ACTION_CMD).putExtra(EXTRA_CMD, c);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, req, i, fl);
    }

    private Notification.Action act(int icon, String label, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build();
    }

    private Notification build(String title, boolean playing) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "প্লেব্যাক", NotificationManager.IMPORTANCE_LOW);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Class<?> target = (PlayerActivity.current != null && PlayerActivity.owner) ? PlayerActivity.class
                : RadioHub.active() ? RadioActivity.class : MainActivity.class;
        Intent open = new Intent(this, target).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open, fl))
                .setContentTitle(title)
                .setContentText(playing ? "চলছে" : "থামানো")
                .setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(act(android.R.drawable.ic_media_previous, "আগের ভিডিও", cmd("prev", 1)))
                .addAction(playing
                        ? act(android.R.drawable.ic_media_pause, "থামান", cmd("pause", 2))
                        : act(android.R.drawable.ic_media_play, "চালান", cmd("play", 3)))
                .addAction(act(android.R.drawable.ic_media_next, "পরের ভিডিও", cmd("next", 4)))
                .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "বন্ধ করুন",
                        PendingIntent.getService(this, 5, new Intent(this, PlaybackService.class).setAction(ACTION_STOP), fl)));
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (sessionToken != null) style.setMediaSession(sessionToken);
        b.setStyle(style);
        return b.build();
    }

    private void acquire() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AISmartTube:play");
                    wake.setReferenceCounted(false);
                }
            }
            if (wake != null && !wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
            if (wifi == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AISmartTube:wifi");
                    wifi.setReferenceCounted(false);
                }
            }
            if (wifi != null && !wifi.isHeld()) wifi.acquire();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void release() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
            if (wifi != null && wifi.isHeld()) wifi.release();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void shutDown() {
        release();
        try {
            if (foreground) stopForeground(true);
        } catch (Throwable ignored) {}
        foreground = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }
}
