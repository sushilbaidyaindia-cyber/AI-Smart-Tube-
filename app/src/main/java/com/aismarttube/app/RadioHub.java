package com.aismarttube.app;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * The one radio engine of the app (lives as long as the app process, not tied to a screen).
 * The native radio screens only show it and press its buttons; notification, lock screen and
 * headset buttons reach it through {@link #handleRemote(String)}.
 */
final class RadioHub {

    private RadioHub() {}

    interface Ui { void onChange(); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static RadioPlayer player;
    private static MediaSession session;

    static volatile int idx = -1;                  // index into RadioStations.all()
    static volatile String state = "stopped";      // loading | playing | paused | error | stopped
    static volatile Ui ui;

    static boolean active() { return idx >= 0 && !"stopped".equals(state); }

    static boolean isPlaying() { return "playing".equals(state) || "loading".equals(state); }

    static String stationName(Context c) {
        List<RadioStations.Station> l = RadioStations.all(c);
        return idx >= 0 && idx < l.size() ? l.get(idx).name : "রেডিও";
    }

    private static void ensure(Context c) {
        if (app == null) app = c.getApplicationContext();
        if (player == null) {
            player = new RadioPlayer(app, st -> MAIN.post(() -> onState(st)));
        }
        if (session == null) {
            try {
                session = new MediaSession(app, "AISmartTubeRadio");
                session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
                session.setCallback(new MediaSession.Callback() {
                    @Override public void onPlay() { handleRemote("play"); }
                    @Override public void onPause() { handleRemote("pause"); }
                    @Override public void onStop() { handleRemote("pause"); }
                    @Override public void onSkipToNext() { handleRemote("next"); }
                    @Override public void onSkipToPrevious() { handleRemote("prev"); }
                    @Override public void onFastForward() { handleRemote("next"); }
                    @Override public void onRewind() { handleRemote("prev"); }
                });
            } catch (Throwable t) {
                t.printStackTrace();
                session = null;
            }
        }
    }

    // ----------------------------------------------------------------- control

    static void start(Context c, int i) {
        ensure(c);
        List<RadioStations.Station> l = RadioStations.all(app);
        if (i < 0 || i >= l.size()) return;
        PlayerActivity.yieldToOthers();   // the video player must be quiet
        idx = i;
        state = "loading";
        try { player.play(l.get(i).urls); } catch (Throwable t) { t.printStackTrace(); state = "error"; }
        publish();
    }

    static void resume() {
        if (!active() || player == null) return;
        PlayerActivity.yieldToOthers();
        state = "loading";
        try { player.resume(); } catch (Throwable t) { t.printStackTrace(); }
        publish();
    }

    static void pause() {
        if (!active() || player == null) return;
        try { player.pause(); } catch (Throwable ignored) {}
        state = "paused";
        publish();
    }

    static void toggle() {
        if (isPlaying()) pause(); else if (state.equals("error") && idx >= 0) start(app, idx); else resume();
    }

    static void step(int d) {
        if (app == null) return;
        int n = RadioStations.all(app).size();
        if (n == 0) return;
        start(app, ((idx < 0 ? 0 : idx) + d + n) % n);
    }

    /** stop completely. keepService = true when a video is about to use the notification */
    static void stop(boolean keepService) {
        try { if (player != null) player.stop(); } catch (Throwable ignored) {}
        idx = -1;
        state = "stopped";
        if (!keepService && app != null) {
            try { app.stopService(new Intent(app, PlaybackService.class)); } catch (Throwable ignored) {}
        }
        try {
            if (session != null) {
                session.setActive(false);
            }
        } catch (Throwable ignored) {}
        notifyUi();
    }

    /** the video player is starting: the radio gives way */
    static void stopForVideo() {
        if (active()) stop(true);
    }

    /** notification / headset / lock screen. false = the radio is not the active player */
    static boolean handleRemote(String cmd) {
        if (!active() || PlayerActivity.owner || cmd == null) return false;
        MAIN.post(() -> {
            switch (cmd) {
                case "play": resume(); break;
                case "pause": pause(); break;
                case "toggle": toggle(); break;
                case "next": case "ff": step(1); break;
                case "prev": case "rew": step(-1); break;
                default: break;
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ state

    private static void onState(String st) {
        if (idx < 0 && !"stopped".equals(st)) return;   // a late callback of a stopped station
        state = st;
        publish();
    }

    private static void publish() {
        if (app != null) {
            boolean playing = "playing".equals(state);
            String title = stationName(app);
            try {
                if (session != null) {
                    long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
                    session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f).build());
                    session.setMetadata(new MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, "আকাশবাণী")
                            .build());
                    if (!session.isActive()) session.setActive(true);
                    if (active()) PlaybackService.sessionToken = session.getSessionToken();
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
            try {
                if (active() && Store.settingBool(app, "bgPlay", true)) {
                    Intent i = new Intent(app, PlaybackService.class)
                            .setAction(PlaybackService.ACTION_UPDATE)
                            .putExtra(PlaybackService.EXTRA_TITLE, title)
                            .putExtra(PlaybackService.EXTRA_PLAYING, playing || "loading".equals(state));
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
                } else if ("stopped".equals(state)) {
                    app.stopService(new Intent(app, PlaybackService.class));
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        notifyUi();
    }

    private static void notifyUi() {
        Ui u = ui;
        if (u != null) u.onChange();
    }
}
