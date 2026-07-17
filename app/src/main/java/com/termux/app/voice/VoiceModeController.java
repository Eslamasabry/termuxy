package com.termux.app.voice;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;

/**
 * High-level, hands-free voice mode: a top banner overlay + the local whisper.cpp engine.
 *
 * <p>Toggle on → shows a status banner ("Listening…"), records utterances, transcribes them on the
 * local whisper server, and sends each transcript straight to the running agent in the current
 * session, then keeps listening. If the whisper server isn't running it surfaces a "Set up model /
 * Use system voice" fallback so the user is never stuck.</p>
 */
public class VoiceModeController implements WhisperVoiceEngine.Listener {

    public static final int REQUEST_RECORD_AUDIO_PERMISSION = 1200;

    private final TermuxActivity mActivity;
    private final HandlerLite mHandler = new HandlerLite();

    private View mOverlay;
    private View mMainRow;
    private View mActionsRow;
    private TextView mStatus;
    private TextView mSubtitle;
    private View mMic;
    private ObjectAnimator mPulse;

    private WhisperVoiceEngine mEngine;
    private boolean mActive;
    private boolean mPendingStart;

    public VoiceModeController(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    public boolean isActive() {
        return mActive;
    }

    public void toggle() {
        if (mActive) {
            stop();
        } else {
            start();
        }
    }

    public void start() {
        if (mActive) return;
        if (!hasRecordAudioPermission()) {
            mPendingStart = true;
            requestRecordAudioPermission();
            return;
        }
        ensureOverlay();
        showOverlay();
        mActive = true;
        setBannerListening();

        mEngine = new WhisperVoiceEngine(this);
        mEngine.start();
    }

    public void stop() {
        mActive = false;
        mPendingStart = false;
        if (mEngine != null) {
            mEngine.stop();
            mEngine = null;
        }
        cancelPulse();
        hideOverlay();
    }

    public void destroy() {
        stop();
    }

    public boolean onPermissionResult(boolean granted) {
        if (!mPendingStart) return false;
        mPendingStart = false;
        if (granted) start();
        else Logger.showToast(mActivity, mActivity.getString(R.string.msg_voice_input_permission_not_granted), true);
        return true;
    }

    // ---- WhisperVoiceEngine.Listener ----

    @Override
    public void onStateChanged(@NonNull WhisperVoiceEngine.State state) {
        if (mOverlay == null) return;
        switch (state) {
            case LISTENING:
                setBannerListening();
                startPulse();
                break;
            case TRANSCRIBING:
                mStatus.setText(R.string.termuxy_voice_transcribing);
                cancelPulse();
                mMic.setAlpha(1f);
                break;
            case IDLE:
            default:
                cancelPulse();
                break;
        }
    }

    @Override
    public void onResult(@NonNull String text) {
        sendToSession(text);
        if (mSubtitle != null) mSubtitle.setText(mActivity.getString(R.string.termuxy_voice_heard, text));
        if (mOverlay != null) mOverlay.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        Toast.makeText(mActivity, mActivity.getString(R.string.msg_voice_mode_sent, text), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onUnavailable() {
        // Whisper server not running — offer setup or the system recognizer fallback.
        if (mMainRow != null) mMainRow.setVisibility(View.GONE);
        if (mActionsRow != null) mActionsRow.setVisibility(View.VISIBLE);
        mActive = false;
        cancelPulse();
    }

    @Override
    public void onError(@NonNull String message) {
        Toast.makeText(mActivity, mActivity.getString(R.string.msg_voice_input_error, message), Toast.LENGTH_LONG).show();
        stop();
    }

    // ---- banner helpers ----

    private void setBannerListening() {
        if (mMainRow != null) mMainRow.setVisibility(View.VISIBLE);
        if (mActionsRow != null) mActionsRow.setVisibility(View.GONE);
        if (mStatus != null) mStatus.setText(R.string.termuxy_voice_listening);
        if (mSubtitle != null) mSubtitle.setText(R.string.termuxy_voice_hint);
    }

    private void startPulse() {
        if (mMic == null) return;
        cancelPulse();
        mPulse = ObjectAnimator.ofFloat(mMic, View.ALPHA, 1f, 0.25f, 1f);
        mPulse.setDuration(900);
        mPulse.setRepeatCount(ObjectAnimator.INFINITE);
        mPulse.start();
    }

    private void cancelPulse() {
        if (mPulse != null) {
            mPulse.cancel();
            mPulse = null;
        }
        if (mMic != null) mMic.setAlpha(1f);
    }

    // ---- overlay plumbing ----

    private void ensureOverlay() {
        if (mOverlay != null) return;
        ViewGroup root = mActivity.findViewById(R.id.activity_termux_root_relative_layout);
        if (root == null) return;

        mOverlay = LayoutInflater.from(mActivity).inflate(R.layout.termuxy_voice_overlay, root, false);
        mMainRow = mOverlay.findViewById(R.id.voice_overlay_main);
        mActionsRow = mOverlay.findViewById(R.id.voice_overlay_actions);
        mStatus = mOverlay.findViewById(R.id.voice_overlay_status);
        mSubtitle = mOverlay.findViewById(R.id.voice_overlay_subtitle);
        mMic = mOverlay.findViewById(R.id.voice_overlay_mic);

        mOverlay.findViewById(R.id.voice_overlay_stop).setOnClickListener(v -> stop());
        mOverlay.findViewById(R.id.voice_overlay_setup).setOnClickListener(v -> {
            if (mActivity.getTermuxyCodingMode() != null) mActivity.getTermuxyCodingMode().installWhisperServer();
            if (mActivity.getDrawer() != null) mActivity.getDrawer().closeDrawers();
            Toast.makeText(mActivity, R.string.termuxy_voice_setup_started, Toast.LENGTH_LONG).show();
            stop();
        });
        mOverlay.findViewById(R.id.voice_overlay_system).setOnClickListener(v -> {
            stop();
            mActivity.getTermuxVoiceInput().startVoiceMode();
        });

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        root.addView(mOverlay, lp);
        mOverlay.setVisibility(View.GONE);
    }

    private void showOverlay() {
        if (mOverlay != null) {
            mOverlay.setVisibility(View.VISIBLE);
            mOverlay.bringToFront();
        }
    }

    private void hideOverlay() {
        if (mOverlay != null) mOverlay.setVisibility(View.GONE);
    }

    // ---- session send ----

    private void sendToSession(@NonNull String text) {
        if (text.trim().isEmpty()) return;
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null || !session.isRunning()) {
            Toast.makeText(mActivity, R.string.msg_voice_mode_no_session, Toast.LENGTH_LONG).show();
            stop();
            return;
        }
        session.write(text + "\r");
    }

    // ---- permission ----

    private boolean hasRecordAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return PermissionUtils.checkPermission(mActivity, Manifest.permission.RECORD_AUDIO);
    }

    private void requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PermissionUtils.requestPermission(mActivity, Manifest.permission.RECORD_AUDIO, REQUEST_RECORD_AUDIO_PERMISSION);
        }
    }

    /** Tiny main-thread post helper, kept for future threading needs. */
    private static final class HandlerLite {
        void post(Runnable r) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        }
    }
}
