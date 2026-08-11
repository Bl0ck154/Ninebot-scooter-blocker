package com.bl0ck154.ninebotblocker;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/** Small app-owned full-charge chime generated as PCM; no ringtone/resource file required. */
public final class ChargeChime {
    private static final int SAMPLE_RATE = 44100;

    private ChargeChime() {}

    public static void play() {
        try {
            short[] pcm = buildPcm();
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            int written = track.write(pcm, 0, pcm.length);
            if (written <= 0) {
                track.release();
                return;
            }
            track.play();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    track.stop();
                } catch (RuntimeException ignored) {}
                try {
                    track.release();
                } catch (RuntimeException ignored) {}
            }, 700L);
        } catch (RuntimeException ignored) {}
    }

    private static short[] buildPcm() {
        final int firstMs = 115;
        final int gapMs = 70;
        final int secondMs = 155;
        final int tailMs = 70;
        int totalSamples = samples(firstMs + gapMs + secondMs + tailMs);
        short[] out = new short[totalSamples];
        renderTone(out, 0, firstMs, 1280.0, 0.28);
        renderTone(out, samples(firstMs + gapMs), secondMs, 1710.0, 0.24);
        return out;
    }

    private static void renderTone(short[] out, int start, int durationMs,
                                   double frequencyHz, double amplitude) {
        int count = samples(durationMs);
        int attack = Math.max(1, samples(8));
        int release = Math.max(1, samples(18));
        for (int i = 0; i < count && start + i < out.length; i++) {
            double envelope = 1.0;
            if (i < attack) envelope = i / (double) attack;
            int remaining = count - i;
            if (remaining < release) envelope *= remaining / (double) release;
            double phase = 2.0 * Math.PI * frequencyHz * i / SAMPLE_RATE;
            out[start + i] = (short) Math.round(
                    Math.sin(phase) * envelope * amplitude * Short.MAX_VALUE);
        }
    }

    private static int samples(int milliseconds) {
        return Math.max(1, SAMPLE_RATE * milliseconds / 1000);
    }
}
