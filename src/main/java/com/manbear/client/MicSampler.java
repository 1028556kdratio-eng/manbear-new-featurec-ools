package com.manbear.client;

import com.manbear.ManbearClientConfig;
import com.manbear.ManbearMod;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import com.manbear.voice.VoiceCodec;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import java.util.Random;

/**
 * Listens to YOUR microphone while you are in a world and keeps short phrases of your voice in memory only.
 * Nothing is written to disk and nothing is sent anywhere: the Manbear plays them back on your own client.
 */
public final class MicSampler {
    public static final int RATE = 16000;
    private static final int CHUNK_BYTES = 3200;          // 100 ms
    private static final int MAX_CLIPS = 24;
    private static final List<short[]> CLIPS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static final ConcurrentLinkedQueue<byte[]> UPLOADS = new ConcurrentLinkedQueue<>();
    private static volatile boolean running;

    private MicSampler() { }

    public static synchronized void start() {
        if (running || !ManbearClientConfig.MIC_SAMPLING.get()) return;
        running = true;
        Thread t = new Thread(MicSampler::run, "manbear-mic");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        running = false;
        UPLOADS.clear();
        synchronized (CLIPS) { CLIPS.clear(); }   // clips never outlive the session
    }

    public static boolean isRunning() { return running; }

    /** next phrase waiting to be uploaded (if sharing is on), or null */
    public static byte[] pollUpload() { return UPLOADS.poll(); }

    public static short[] randomClip() {
        synchronized (CLIPS) {
            return CLIPS.isEmpty() ? null : CLIPS.get(RANDOM.nextInt(CLIPS.size()));
        }
    }

    private static void run() {
        TargetDataLine line = null;
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);   // signed 16-bit little-endian mono
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                ManbearMod.LOGGER.info("Manbear: no usable microphone, voice sampling disabled");
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            line.start();

            byte[] buf = new byte[CHUNK_BYTES];
            List<short[]> current = new ArrayList<>();
            boolean talking = false;
            int silent = 0;
            double floor = 150;
            while (running) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 1) continue;
                short[] pcm = new short[n / 2];
                double sum = 0;
                for (int i = 0; i < pcm.length; i++) {
                    short v = (short) ((buf[2 * i + 1] << 8) | (buf[2 * i] & 0xFF));
                    pcm[i] = v;
                    sum += (double) v * v;
                }
                double rms = Math.sqrt(sum / Math.max(1, pcm.length));
                double threshold = Math.max(ManbearClientConfig.MIC_THRESHOLD.get(), floor * 3.5);
                if (rms > threshold) {
                    talking = true;
                    silent = 0;
                    current.add(pcm);
                } else {
                    floor = floor * 0.98 + rms * 0.02;          // learn the room noise
                    if (talking) {
                        current.add(pcm);
                        if (++silent >= 7) {                     // 0.7 s of quiet: the phrase is over
                            finish(current);
                            current = new ArrayList<>();
                            talking = false;
                            silent = 0;
                        }
                    }
                }
                if (current.size() > 60) {                       // cap a phrase at 6 s
                    finish(current);
                    current = new ArrayList<>();
                    talking = false;
                    silent = 0;
                }
            }
        } catch (Throwable t) {
            ManbearMod.LOGGER.info("Manbear: microphone sampling stopped ({})", t.toString());
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            running = false;
        }
    }

    private static void finish(List<short[]> chunks) {
        if (chunks.size() < 8) return;   // under ~0.8 s including the trailing quiet: just a cough or a click
        int total = 0;
        for (short[] c : chunks) total += c.length;
        short[] clip = new short[total];
        int pos = 0;
        int peak = 1;
        for (short[] c : chunks) {
            for (short v : c) peak = Math.max(peak, Math.abs(v));
            System.arraycopy(c, 0, clip, pos, c.length);
            pos += c.length;
        }
        double gain = Math.min(4.0, 24000.0 / peak);      // even out quiet and loud phrases
        for (int i = 0; i < clip.length; i++) {
            clip[i] = (short) Math.max(-32768, Math.min(32767, clip[i] * gain));
        }
        if (ManbearClientConfig.SHARE_VOICE.get() && UPLOADS.size() < 4) UPLOADS.add(VoiceCodec.encode(clip));
        synchronized (CLIPS) {
            CLIPS.add(clip);
            while (CLIPS.size() > MAX_CLIPS) CLIPS.remove(RANDOM.nextInt(CLIPS.size()));
        }
    }
}
