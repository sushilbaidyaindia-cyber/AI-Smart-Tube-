package com.aismarttube.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.PowerManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Plays live radio (HLS .m3u8 / AAC / MP3 streams) with the phone's own MediaPlayer, so it keeps going
 * with the screen off. Every station can have several backup URLs; if one fails the next is tried.
 * States reported: loading, playing, paused, error, stopped.
 */
class RadioPlayer {

    interface Listener {
        void onState(String state);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36";

    private final Context ctx;
    private final Listener listener;
    private final AudioManager audio;
    private MediaPlayer mp;
    private String[] urls = new String[0];
    private int idx;
    private int gen;
    private boolean focus;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        }
    };

    RadioPlayer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    boolean hasStation() {
        return urls.length > 0;
    }

    /** start a station; urls = main link first, backups after */
    void play(String[] list) {
        urls = list == null ? new String[0] : list;
        idx = 0;
        if (urls.length == 0) {
            listener.onState("error");
            return;
        }
        open();
    }

    void resume() {
        if (urls.length == 0) return;
        open();
    }

    /** a live stream cannot "wait", so pausing simply lets go of the player; resume reconnects */
    void pause() {
        gen++;
        release();
        if (urls.length > 0) listener.onState("paused");
    }

    void stop() {
        gen++;
        release();
        abandonFocus();
        urls = new String[0];
        listener.onState("stopped");
    }

    private void open() {
        release();
        final int my = ++gen;
        listener.onState("loading");
        requestFocus();
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            p.setDataSource(ctx, Uri.parse(urls[idx]), headers);
            p.setOnPreparedListener(m -> {
                if (my != gen) return;
                m.start();
                listener.onState("playing");
            });
            p.setOnErrorListener((m, what, extra) -> {
                failed(my);
                return true;
            });
            p.setOnCompletionListener(m -> failed(my));   // a live stream should never end -> reconnect / next link
            p.prepareAsync();
        } catch (Throwable t) {
            t.printStackTrace();
            failed(my);
        }
    }

    private void failed(int my) {
        if (my != gen) return;
        release();
        if (idx + 1 < urls.length) {
            idx++;
            open();
        } else {
            idx = 0;
            listener.onState("error");
        }
    }

    private void release() {
        MediaPlayer p = mp;
        mp = null;
        if (p != null) {
            try { p.setOnPreparedListener(null); p.setOnErrorListener(null); p.setOnCompletionListener(null); } catch (Throwable ignored) {}
            try { p.reset(); } catch (Throwable ignored) {}
            try { p.release(); } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        if (audio != null && !focus) {
            focus = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (audio != null && focus) {
            audio.abandonAudioFocus(focusListener);
            focus = false;
        }
    }
}
