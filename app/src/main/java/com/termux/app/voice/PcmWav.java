package com.termux.app.voice;

import androidx.annotation.NonNull;

/** Encodes 16-bit mono PCM into a minimal WAV container (whisper.cpp input). */
final class PcmWav {

    private PcmWav() {}

    @NonNull
    static byte[] toWav(@NonNull short[] pcm, int sampleRate) {
        byte[] body = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            body[i * 2] = (byte) (pcm[i] & 0xff);
            body[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
        }
        return toWav(body, sampleRate, 1, 16);
    }

    @NonNull
    private static byte[] toWav(@NonNull byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataLen = pcm.length;
        int chunkSize = 36 + dataLen;

        byte[] out = new byte[44 + dataLen];
        int p = 0;
        p = writeText(out, p, "RIFF");
        p = writeInt32LE(out, p, chunkSize);
        p = writeText(out, p, "WAVE");
        p = writeText(out, p, "fmt ");
        p = writeInt32LE(out, p, 16);            // PCM fmt chunk size
        p = writeInt16LE(out, p, 1);             // audio format = PCM
        p = writeInt16LE(out, p, channels);
        p = writeInt32LE(out, p, sampleRate);
        p = writeInt32LE(out, p, byteRate);
        p = writeInt16LE(out, p, blockAlign);
        p = writeInt16LE(out, p, bitsPerSample);
        p = writeText(out, p, "data");
        p = writeInt32LE(out, p, dataLen);
        System.arraycopy(pcm, 0, out, p, dataLen);
        return out;
    }

    private static int writeText(@NonNull byte[] out, int p, @NonNull String s) {
        for (int i = 0; i < s.length(); i++) out[p++] = (byte) s.charAt(i);
        return p;
    }

    private static int writeInt32LE(@NonNull byte[] out, int p, int v) {
        out[p++] = (byte) (v & 0xff);
        out[p++] = (byte) ((v >> 8) & 0xff);
        out[p++] = (byte) ((v >> 16) & 0xff);
        out[p++] = (byte) ((v >> 24) & 0xff);
        return p;
    }

    private static int writeInt16LE(@NonNull byte[] out, int p, int v) {
        out[p++] = (byte) (v & 0xff);
        out[p++] = (byte) ((v >> 8) & 0xff);
        return p;
    }
}
