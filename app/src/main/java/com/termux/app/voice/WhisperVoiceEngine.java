package com.termux.app.voice;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Continuous, hands-free voice engine backed by a local whisper.cpp server.
 *
 * <p>Runs a single background loop: record one utterance (energy-based VAD) → POST to the whisper
 * server → emit the transcript on the main thread → repeat. Designed to keep going through
 * transient hiccups; only a sustained run of failures (or a missing server) tears it down so the
 * UI can fall back to the system recognizer.</p>
 */
public class WhisperVoiceEngine {

    public enum State { IDLE, LISTENING, TRANSCRIBING }

    public interface Listener {
        @MainThread void onStateChanged(@NonNull State state);
        @MainThread void onResult(@NonNull String text);
        @MainThread void onUnavailable();              // server not reachable at start
        @MainThread void onError(@NonNull String message);
    }

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final VoiceRecorder recorder = new VoiceRecorder();
    private final WhisperClient client;
    private final Listener listener;

    private volatile boolean running;
    private Thread loop;

    public WhisperVoiceEngine(@NonNull Listener listener) {
        this(listener, new WhisperClient());
    }

    public WhisperVoiceEngine(@NonNull Listener listener, @NonNull WhisperClient client) {
        this.listener = listener;
        this.client = client;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) return;
        running = true;
        setState(State.LISTENING);
        loop = new Thread(this::runLoop, "termuxy-whisper-engine");
        loop.start();
    }

    public void stop() {
        running = false;
        // Recording reads are bounded (~frames), so the loop notices stop within ~100ms.
        Thread l = loop;
        if (l != null) l.interrupt();
        loop = null;
        setState(State.IDLE);
    }

    private void runLoop() {
        // Up-front availability check so the UI can fall back immediately.
        if (!client.isAvailable()) {
            main.post(listener::onUnavailable);
            running = false;
            setState(State.IDLE);
            return;
        }

        int consecutiveFailures = 0;
        while (running) {
            short[] pcm = recorder.recordOneUtterance(() -> setState(State.LISTENING));
            if (!running) break;
            if (pcm == null) continue; // nothing heard this round; listen again

            setState(State.TRANSCRIBING);
            try {
                String text = client.transcribe(pcm);
                if (text.isEmpty()) {
                    consecutiveFailures = 0; // empty result isn't a failure
                } else {
                    consecutiveFailures = 0;
                    final String result = text;
                    main.post(() -> listener.onResult(result));
                }
                setState(State.LISTENING);
            } catch (Exception e) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    final String msg = e.getMessage() == null ? "transcription failed" : e.getMessage();
                    main.post(() -> listener.onError(msg));
                    running = false;
                    setState(State.IDLE);
                    return;
                }
                setState(State.LISTENING); // try again
            }
        }
        setState(State.IDLE);
    }

    private void setState(@NonNull State state) {
        main.post(() -> listener.onStateChanged(state));
    }
}
