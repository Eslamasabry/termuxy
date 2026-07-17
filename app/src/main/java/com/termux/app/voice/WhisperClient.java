package com.termux.app.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Talks to a local whisper.cpp server over loopback. proot shares Android's network namespace, so
 * a server bound to 127.0.0.1 inside the termuxy Linux env is reachable here at 127.0.0.1.
 *
 * <p>Expected server contract (whisper.cpp {@code examples/server}):
 * <ul>
 *   <li>{@code GET /health} → 200 when ready.</li>
 *   <li>{@code POST /inference?response_format=json} with multipart field {@code file} (WAV) →
 *       {@code {"text": "..."}}.</li>
 * </ul>
 * If the server returns plain text instead of JSON (older builds), the raw body is returned.</p>
 */
public class WhisperClient {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";

    private static final int HEALTH_CONNECT_MS = 1000;
    private static final int HEALTH_READ_MS = 1000;
    private static final int POST_CONNECT_MS = 3000;
    private static final int POST_READ_MS = 45_000;

    private final String baseUrl;

    public WhisperClient() {
        this(DEFAULT_BASE_URL);
    }

    public WhisperClient(@NonNull String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** True if the whisper server responds on /health. */
    public boolean isAvailable() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/health").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HEALTH_CONNECT_MS);
            conn.setReadTimeout(HEALTH_READ_MS);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Transcribe 16 kHz mono PCM. Returns the recognized text, trimmed; never null. */
    @NonNull
    public String transcribe(@NonNull short[] pcm16k) throws Exception {
        byte[] wav = PcmWav.toWav(pcm16k, VoiceRecorder.SAMPLE_RATE);
        String boundary = "termuxy-whisper-" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Multipart.writeField(body, boundary, "file", "voice.wav", "audio/wav", wav);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/inference?response_format=json").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(POST_CONNECT_MS);
            conn.setReadTimeout(POST_READ_MS);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.getOutputStream().write(body.toByteArray());

            int code = conn.getResponseCode();
            String response = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new Exception("whisper server HTTP " + code + (response.isEmpty() ? "" : ": " + response));
            }
            return parseText(response).trim();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @NonNull
    private static String parseText(@Nullable String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JSONObject json = new JSONObject(body.trim());
            return json.optString("text", "").trim();
        } catch (Exception e) {
            // Older whisper.cpp builds return plain text; honour that.
            return body.trim();
        }
    }

    @NonNull
    private static String readAll(@Nullable InputStream in) {
        if (in == null) return "";
        try (InputStream src = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = src.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
