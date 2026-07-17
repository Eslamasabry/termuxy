package com.termux.app.terminal.io;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice input for the Termuxy cockpit.
 *
 * Two modes:
 *  1. One-shot field fill  — {@link #start(EditText)} transcribes a single utterance into an
 *     EditText (used by the agent-prompt dialog and the toolbar text input).
 *  2. Voice mode           — {@link #toggleVoiceMode()} enables hands-free, continuous listening:
 *     each recognized utterance is sent directly to the current terminal session (i.e. to the
 *     running agent) followed by Enter, then listening re-arms automatically. Talk to your agent
 *     while walking around.
 */
public class TermuxVoiceInput {

    public static final int REQUEST_RECORD_AUDIO_PERMISSION = 1100;

    private static final String LOG_TAG = "TermuxVoiceInput";
    private static final long REARM_DELAY_MS = 250;

    private final TermuxActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer mSpeechRecognizer;
    private EditText mTargetTextInput;

    /** True while a single one-shot recognition is in flight. */
    private boolean mIsListening;

    /** True while hands-free voice mode is active (continuous, send-to-session). */
    private boolean mVoiceModeActive;

    /** True if we are waiting for the microphone permission to start voice mode. */
    private boolean mPendingVoiceMode;

    public TermuxVoiceInput(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    // ---------------------------------------------------------------------
    // One-shot: transcribe into an EditText
    // ---------------------------------------------------------------------

    public void start(@Nullable EditText textInputView) {
        if (textInputView == null) return;

        mTargetTextInput = textInputView;

        if (mIsListening) {
            stop();
            return;
        }

        if (!hasRecordAudioPermission()) {
            mPendingVoiceMode = false;
            requestRecordAudioPermission();
            return;
        }

        beginListening(false);
    }

    // ---------------------------------------------------------------------
    // Voice mode: continuous, hands-free, send-to-session
    // ---------------------------------------------------------------------

    public boolean isVoiceModeActive() {
        return mVoiceModeActive;
    }

    public void toggleVoiceMode() {
        if (mVoiceModeActive) {
            stopVoiceMode();
        } else {
            startVoiceMode();
        }
    }

    public void startVoiceMode() {
        if (mVoiceModeActive) return;

        if (!hasRecordAudioPermission()) {
            mPendingVoiceMode = true;
            requestRecordAudioPermission();
            return;
        }

        mTargetTextInput = null;
        mVoiceModeActive = true;
        beginListening(true);
    }

    public void stopVoiceMode() {
        mVoiceModeActive = false;
        mPendingVoiceMode = false;
        cancelRearm();
        stop();
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_mode_off), false);
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    public void stop() {
        if (mSpeechRecognizer != null) {
            try {
                mSpeechRecognizer.stopListening();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop voice input", e);
                resetRecognizer();
            }
        }
    }

    public void destroy() {
        mVoiceModeActive = false;
        mPendingVoiceMode = false;
        cancelRearm();
        resetRecognizer();
    }

    public boolean onRequestPermissionsResult(int requestCode, @NonNull int[] grantResults) {
        if (requestCode != REQUEST_RECORD_AUDIO_PERMISSION) return false;

        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_permission_not_granted), true);
            mPendingVoiceMode = false;
            return true;
        }

        if (mPendingVoiceMode) {
            mPendingVoiceMode = false;
            startVoiceMode();
        } else if (mTargetTextInput != null) {
            start(mTargetTextInput);
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private boolean hasRecordAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return PermissionUtils.checkPermission(mActivity, Manifest.permission.RECORD_AUDIO);
    }

    private void requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PermissionUtils.requestPermission(mActivity, Manifest.permission.RECORD_AUDIO, REQUEST_RECORD_AUDIO_PERMISSION);
        }
    }

    private void beginListening(boolean voiceMode) {
        SpeechRecognizer speechRecognizer = ensureRecognizer();
        if (speechRecognizer == null) {
            // Failed to create — abort whatever mode requested it.
            mVoiceModeActive = false;
            return;
        }

        mIsListening = true;
        if (voiceMode) {
            Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_mode_on), false);
        } else {
            if (mTargetTextInput != null) mTargetTextInput.requestFocus();
            Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_listening), false);
        }

        try {
            speechRecognizer.startListening(createRecognizerIntent());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start voice input", e);
            showError(e.getMessage());
            resetRecognizer();
            mVoiceModeActive = false;
        }
    }

    @Nullable
    private SpeechRecognizer ensureRecognizer() {
        if (mSpeechRecognizer != null) return mSpeechRecognizer;

        SpeechRecognizer recognizer = createSpeechRecognizer();
        if (recognizer == null) return null;

        mSpeechRecognizer = recognizer;
        mSpeechRecognizer.setRecognitionListener(new VoiceRecognitionListener());
        return mSpeechRecognizer;
    }

    @Nullable
    private SpeechRecognizer createSpeechRecognizer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(mActivity)) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(mActivity);
            }

            if (!SpeechRecognizer.isRecognitionAvailable(mActivity)) {
                Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_recognizer_unavailable), true);
                return null;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_on_device_unavailable), true);
            }

            return SpeechRecognizer.createSpeechRecognizer(mActivity);
        } catch (UnsupportedOperationException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "On-device speech recognition unavailable", e);
            Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_recognizer_unavailable), true);
            return null;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create speech recognizer", e);
            Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_recognizer_unavailable), true);
            return null;
        }
    }

    @NonNull
    private Intent createRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
        }

        return intent;
    }

    private void insertRecognizedText(@Nullable String text) {
        if (text == null || text.isEmpty() || mTargetTextInput == null) return;

        Editable editable = mTargetTextInput.getText();
        int selectionStart = Math.max(mTargetTextInput.getSelectionStart(), 0);
        int selectionEnd = Math.max(mTargetTextInput.getSelectionEnd(), 0);
        int replaceStart = Math.min(selectionStart, selectionEnd);
        int replaceEnd = Math.max(selectionStart, selectionEnd);

        editable.replace(replaceStart, replaceEnd, text);
        mTargetTextInput.setSelection(replaceStart + text.length());
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_inserted), false);
    }

    /** Send a recognized utterance directly to the running agent in the current session. */
    private void sendToSession(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) return;

        TerminalSession session = mActivity.getCurrentSession();
        if (session == null || !session.isRunning()) {
            Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_mode_no_session), true);
            stopVoiceMode();
            return;
        }

        session.write(text + "\r");
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_mode_sent, text), false);
    }

    private void showError(@Nullable String message) {
        if (message == null || message.isEmpty()) {
            Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_no_match), false);
        } else {
            Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_error, message), true);
        }
    }

    private void resetRecognizer() {
        mIsListening = false;

        if (mSpeechRecognizer != null) {
            try {
                mSpeechRecognizer.destroy();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to destroy speech recognizer", e);
            }
            mSpeechRecognizer = null;
        }
    }

    private void cancelRearm() {
        mHandler.removeCallbacksAndMessages(null);
    }

    /** Re-arm listening for continuous voice mode, with a small delay to avoid RECOGNIZER_BUSY. */
    private void rearmContinuous() {
        cancelRearm();
        mHandler.postDelayed(() -> {
            if (!mVoiceModeActive) return;
            SpeechRecognizer recognizer = ensureRecognizer();
            if (recognizer == null) {
                stopVoiceMode();
                return;
            }
            try {
                recognizer.startListening(createRecognizerIntent());
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to re-arm voice mode", e);
                stopVoiceMode();
            }
        }, REARM_DELAY_MS);
    }

    @Nullable
    private String getRecognizerErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return mActivity.getString(R.string.msg_voice_input_permission_not_granted);
            case SpeechRecognizer.ERROR_NETWORK:
                return "network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return null;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "recognizer busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "recognition service error";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "too many voice input requests";
            default:
                return "error " + error;
        }
    }

    private final class VoiceRecognitionListener implements RecognitionListener {

        @Override
        public void onReadyForSpeech(Bundle params) {}

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            if (mVoiceModeActive) {
                // Transient hiccups (no-match, timeout, busy) are part of continuous listening —
                // keep the loop going. Anything else tears voice mode down.
                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    rearmContinuous();
                    return;
                }
                showError(getRecognizerErrorMessage(error));
                stopVoiceMode();
                return;
            }

            showError(getRecognizerErrorMessage(error));
            resetRecognizer();
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (matches == null || matches.isEmpty()) ? null : matches.get(0);

            if (mVoiceModeActive) {
                sendToSession(text);
                mIsListening = false;
                rearmContinuous();
                return;
            }

            if (text == null || text.isEmpty()) {
                showError(null);
            } else {
                insertRecognizedText(text);
            }
            resetRecognizer();
        }

        @Override
        public void onPartialResults(Bundle partialResults) {}

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }
}
