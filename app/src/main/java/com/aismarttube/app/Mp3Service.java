package com.aismarttube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFormat;

import de.sciss.jump3r.lowlevel.LameEncoder;

/** ভিডিওর অডিও নামিয়ে MP3 বানায়: ডাউনলোড → ডিকোড (MediaCodec) → MP3 এনকোড (jump3r) → Music/AISmartTube */
public class Mp3Service extends Service {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    private static final String CH = "mp3_convert";
    private static final int NID = 4711;
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int pending = 0;
    private int lastPct = -1;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        final String url = intent.getStringExtra(EXTRA_URL);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        pending++;
        startForeground(NID, buildNote(title, "শুরু হচ্ছে…", -1));
        pool.execute(() -> {
            String err = null;
            String name = Mp3Service.this.safe(title);
            try {
                convert(url, name);
            } catch (Throwable t) {
                t.printStackTrace();
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final String msg = err == null ? "MP3 তৈরি হয়েছে: " + name + " (Music/AISmartTube)" : "MP3 হয়নি: " + err;
            ui.post(() -> {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NID + 1 + (int) (System.nanoTime() & 0xff), buildDone(name, msg));
                pending--;
                if (pending <= 0) { stopForeground(true); stopSelf(); }
            });
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private String safe(String title) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (t.isEmpty()) t = "audio";
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t;
    }

    // ------------------------------------------------------------ pipeline

    private void convert(String url, String name) throws Exception {
        if (url == null || !url.startsWith("https://")) throw new Exception("লিংক পাওয়া যায়নি");
        File tmp = new File(getCacheDir(), "mp3src_" + System.currentTimeMillis() + ".bin");
        OutputTarget out = null;
        try {
            progress(name, "অডিও নামানো হচ্ছে…", 0);
            download(url, tmp, name);
            out = openOutput(name);
            progress(name, "MP3 বানানো হচ্ছে…", 0);
            encode(tmp, out.stream, name);
            out.stream.flush();
            out.stream.close();
            out.finish();
            DownloadLog.add(getApplicationContext(), name, "mp3", out.where, 0);
            out = null;
        } finally {
            tmp.delete();
            if (out != null) {
                try { out.stream.close(); } catch (Throwable ignore) { }
                out.abort();
            }
        }
    }

    /** YouTube slows down one long plain download. Asking for small pieces with several connections at once is much faster. */
    private void download(final String url, final File dst, final String name) throws Exception {
        long total = -1;
        boolean ranged = false;
        HttpURLConnection p = (HttpURLConnection) new URL(url).openConnection();
        try {
            p.setRequestProperty("User-Agent", UA);
            p.setRequestProperty("Range", "bytes=0-0");
            p.setConnectTimeout(15000);
            p.setReadTimeout(15000);
            p.connect();
            if (p.getResponseCode() == 206) {
                String cr = p.getHeaderField("Content-Range"); // bytes 0-0/12345
                int sl = cr == null ? -1 : cr.lastIndexOf('/');
                if (sl > 0) {
                    try { total = Long.parseLong(cr.substring(sl + 1).trim()); ranged = total > 0; } catch (Throwable ignore) { }
                }
            }
        } catch (Throwable ignore) {
            ranged = false;
        } finally {
            p.disconnect();
        }
        if (!ranged || total < 256 * 1024) { downloadPlain(url, dst, name); return; }
        try {
            downloadParallel(url, dst, name, total);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            downloadPlain(url, dst, name); // pieces did not work: the old way
        }
    }

    private void downloadParallel(final String url, final File dst, final String name, final long total) throws Exception {
        final int CHUNK = 1024 * 1024;
        final int chunks = (int) ((total + CHUNK - 1) / CHUNK);
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong got = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicReference<Exception> fail = new java.util.concurrent.atomic.AtomicReference<>();
        final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dst, "rw");
        try {
            raf.setLength(total);
            int nThreads = Math.min(4, chunks);
            ExecutorService dl = Executors.newFixedThreadPool(nThreads);
            java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int t = 0; t < nThreads; t++) {
                fs.add(dl.submit(() -> {
                    byte[] buf = new byte[32 * 1024];
                    int idx;
                    while (fail.get() == null && (idx = next.getAndIncrement()) < chunks) {
                        long from = (long) idx * CHUNK;
                        long to = Math.min(total, from + CHUNK) - 1;
                        boolean ok = false;
                        for (int attempt = 0; attempt < 3 && !ok && fail.get() == null; attempt++) {
                            HttpURLConnection c = null;
                            try {
                                c = (HttpURLConnection) new URL(url).openConnection();
                                c.setRequestProperty("User-Agent", UA);
                                c.setRequestProperty("Range", "bytes=" + from + "-" + to);
                                c.setConnectTimeout(15000);
                                c.setReadTimeout(20000);
                                if (c.getResponseCode() != 206) throw new java.io.IOException("HTTP " + c.getResponseCode());
                                long pos = from;
                                try (InputStream in = c.getInputStream()) {
                                    int n;
                                    while ((n = in.read(buf)) > 0) {
                                        synchronized (raf) { raf.seek(pos); raf.write(buf, 0, n); }
                                        pos += n;
                                        long g = got.addAndGet(n);
                                        progress(name, "অডিও নামানো হচ্ছে…", (int) (g * 40 / total));
                                    }
                                }
                                if (pos != to + 1) throw new java.io.IOException("অসম্পূর্ণ অংশ");
                                ok = true;
                            } catch (Exception e) {
                                if (attempt == 2) fail.compareAndSet(null, e);
                            } finally {
                                if (c != null) c.disconnect();
                            }
                        }
                    }
                    return null;
                }));
            }
            dl.shutdown();
            for (java.util.concurrent.Future<?> f : fs) {
                try { f.get(); } catch (java.util.concurrent.ExecutionException e) { fail.compareAndSet(null, new Exception(String.valueOf(e.getCause()))); }
            }
            if (fail.get() != null) throw fail.get();
        } finally {
            try { raf.close(); } catch (Throwable ignore) { }
        }
    }

    private void downloadPlain(String url, File dst, String name) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.connect();
        if (c.getResponseCode() / 100 != 2) throw new Exception("সার্ভার সাড়া দেয়নি (" + c.getResponseCode() + ")");
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted()) throw new Exception("বাতিল");
                fo.write(buf, 0, n);
                got += n;
                if (total > 0) progress(name, "অডিও নামানো হচ্ছে…", (int) (got * 40 / total));
            }
        } finally {
            c.disconnect();
        }
    }

    private void encode(File src, OutputStream os, String name) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        LameEncoder enc = null;
        try {
            ex.setDataSource(src.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new Exception("অডিও ট্র্যাক পাওয়া যায়নি");
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            byte[] mp3 = null;
            byte[] pcm = new byte[0];

            while (!outDone) {
                if (Thread.currentThread().isInterrupted()) throw new Exception("বাতিল");
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        if (enc == null) {
                            MediaFormat of = codec.getOutputFormat();
                            int rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            int ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            if (ch < 1 || ch > 2) throw new Exception("এই অডিও ফরম্যাট সমর্থিত নয় (চ্যানেল " + ch + ")");
                            AudioFormat af = new AudioFormat(rate, 16, ch, true, false);
                            enc = newFastEncoder(af);
                            mp3 = new byte[enc.getMP3BufferSize()];
                            pcm = new byte[enc.getPCMBufferSize()];
                        }
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        ob.get(chunk);
                        int off = 0;
                        while (off < chunk.length) {
                            int len = Math.min(pcm.length, chunk.length - off);
                            int w = enc.encodeBuffer(chunk, off, len, mp3);
                            if (w > 0) os.write(mp3, 0, w);
                            off += len;
                        }
                        if (durUs > 0) {
                            progress(name, "MP3 বানানো হচ্ছে…", 40 + (int) (info.presentationTimeUs * 60 / durUs));
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            if (enc == null) throw new Exception("অডিও ডিকোড করা যায়নি");
            int w = enc.encodeFinish(mp3);
            if (w > 0) os.write(mp3, 0, w);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignore) { }
            try { ex.release(); } catch (Throwable ignore) { }
            try { if (enc != null) enc.close(); } catch (Throwable ignore) { }
        }
    }

    /** MP3 quality 7 (fast) at 128 kbit/s. If this library version has no such constructor, the normal encoder is used. */
    private static LameEncoder newFastEncoder(AudioFormat af) {
        try {
            java.lang.reflect.Constructor<LameEncoder> k = LameEncoder.class.getConstructor(
                    AudioFormat.class, int.class, int.class, int.class, boolean.class);
            return k.newInstance(af, 128, -1, 7, false);
        } catch (Throwable t) {
            return new LameEncoder(af);
        }
    }

    // ------------------------------------------------------------ output file

    private abstract static class OutputTarget {
        OutputStream stream;
        String where = "";
        abstract void finish();
        abstract void abort();
    }

    private OutputTarget openOutput(String base) throws Exception {
        final OutputTarget t = new OutputTarget() { void finish() { } void abort() { } };
        if (Build.VERSION.SDK_INT >= 29) {
            final android.content.ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, uniqueName(base));
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AISmartTube");
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            final Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("ফাইল তৈরি করা যায়নি");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new Exception("ফাইল খোলা যায়নি");
            OutputTarget o = new OutputTarget() {
                void finish() {
                    ContentValues u = new ContentValues();
                    u.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    cr.update(uri, u, null, null);
                }
                void abort() { try { cr.delete(uri, null, null); } catch (Throwable ignore) { } }
            };
            o.stream = os;
            o.where = uri.toString();
            return o;
        }
        final File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "AISmartTube");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("ফোল্ডার তৈরি করা যায়নি");
        final File f = new File(dir, uniqueFile(dir, base));
        OutputTarget o = new OutputTarget() {
            void finish() {
                android.media.MediaScannerConnection.scanFile(getApplicationContext(), new String[]{f.getAbsolutePath()}, new String[]{"audio/mpeg"}, null);
            }
            void abort() { f.delete(); }
        };
        o.stream = new java.io.BufferedOutputStream(new FileOutputStream(f), 64 * 1024);
        o.where = Uri.fromFile(f).toString();
        return o;
    }

    private String uniqueName(String base) { return base + ".mp3"; }

    private String uniqueFile(File dir, String base) {
        String n = base + ".mp3";
        int i = 1;
        while (new File(dir, n).exists() && i < 100) n = base + " (" + (i++) + ").mp3";
        return n;
    }

    // ------------------------------------------------------------ notification

    private void progress(String title, String text, int pct) {
        if (pct == lastPct) return;
        lastPct = pct;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NID, buildNote(title, text, pct));
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "MP3 তৈরি", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification buildNote(String title, String text, int pct) {
        ensureChannel();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title == null ? "MP3" : title)
                .setContentText(pct >= 0 ? text + " " + pct + "%" : text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (pct >= 0) b.setProgress(100, Math.min(100, pct), false);
        return b.build();
    }

    private Notification buildDone(String title, String msg) {
        ensureChannel();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title == null ? "MP3" : title)
                .setContentText(msg)
                .setAutoCancel(true);
        return b.build();
    }
}
