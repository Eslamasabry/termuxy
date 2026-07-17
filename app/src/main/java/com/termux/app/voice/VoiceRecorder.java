package com.termux.app.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Records a single utterance from the mic at 16 kHz mono PCM-16 (whisper.cpp's required format).
 *
 * <p>Blocking call intended to run on a background thread. Uses simple energy-based voice-activity
 * detection: it waits for speech to start, then stops after a sustained silence (or a hard max
 * duration / no-speech timeout). Returns the raw PCM samples, or {@code null} on error / nothing
 * heard.</p>
 */
public class VoiceRecorder {

    public static final int SAMPLE_RATE = 16000;

    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private static final int FRAME_MS = 100;
    private static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000; // 1600

    private static final long NO_SPEECH_TIMEOUT_MS = 8000;  // give up if nothing heard
    private static final long MAX_UTTERANCE_MS = 30_000;     // hard cap
    private static final long SILENCE_TAIL_MS = 1200;        // stop after this much silence
    private static final double SPEECH_RMS = 550.0;          // ~-45dBFS at 16-bit; tunable

    /** Buffer receiving one utterance; null if nothing usable was captured. */
    @Nullable
    public short[] recordOneUtterance(@Nullable Runnable onSpeechStart) {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
        int frameBytes = FRAME_SAMPLES * 2;
        int bufSize = Math.max(minBuf, frameBytes * 4);

        AudioRecord record = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_IN, ENCODING, bufSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) return null;

            short[] frame = new short[FRAME_SAMPLES];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            record.startRecording();
            long start = System.currentTimeMillis();
            boolean speechStarted = false;
            long silenceSince = 0;

            while (true) {
                int n = record.read(frame, 0, frame.length);
                if (n <= 0) continue;
                appendPcm(out, frame, n);

                double rms = rms(frame, n);
                long now = System.currentTimeMillis();

                if (rms > SPEECH_RMS) {
                    if (!speechStarted && onSpeechStart != null) onSpeechStart.run();
                    speechStarted = true;
                    silenceSince = 0;
                } else if (speechStarted && silenceSince == 0) {
                    silenceSince = now;
                }

                if (speechStarted && silenceSince != 0 && now - silenceSince > SILENCE_TAIL_MS) break;
                if (!speechStarted && now - start > NO_SPEECH_TIMEOUT_MS) return null;
                if (now - start > MAX_UTTERANCE_MS) break;
            }

            if (!speechStarted) return null;
            return toShortArray(out.toByteArray());
        } catch (Exception e) {
            return null;
        } finally {
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
                } catch (Exception ignored) {
                }
                try { record.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static void appendPcm(@NonNull java.io.ByteArrayOutputStream out, @NonNull short[] frame, int n) {
        for (int i = 0; i < n; i++) {
            out.write(frame[i] & 0xff);
            out.write((frame[i] >> 8) & 0xff);
        }
    }

    private static short[] toShortArray(@NonNull byte[] bytes) {
        short[] s = new short[bytes.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((bytes[i * 2] & 0xff) | ((bytes[i * 2 + 1] & 0xff) << 8));
        }
        return s;
    }

    private static double rms(@NonNull short[] frame, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            long v = frame[i];
            sum += v * v;
        }
        return Math.sqrt((double) sum / Math.max(1, n));
    }
}
