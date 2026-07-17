package com.termux.app.voice;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Writes a single file field into a multipart/form-data body. */
final class Multipart {

    private Multipart() {}

    static void writeField(@NonNull ByteArrayOutputStream out, @NonNull String boundary,
                           @NonNull String fieldName, @NonNull String filename,
                           @NonNull String contentType, @NonNull byte[] data)
            throws java.io.IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data, 0, data.length);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
