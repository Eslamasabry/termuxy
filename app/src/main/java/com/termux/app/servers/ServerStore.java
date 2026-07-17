package com.termux.app.servers;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads/saves the user's saved {@link Server}s as JSON in the app's private storage.
 *
 * App-private storage is sandboxed by the Android OS; a fast-follow hardening task is to
 * wrap this with Android Keystore encryption (see docs/STRATEGY.md, MVP scope).
 */
public class ServerStore {

    private static final String FILENAME = "termuxy-servers.json";

    @NonNull
    public static List<Server> load(@NonNull Context context) {
        ArrayList<Server> list = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILENAME);
        if (!file.exists()) return list;
        try {
            JSONArray arr = new JSONArray(new String(readAll(file), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                list.add(Server.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException | IOException e) {
            // Corrupt or unreadable: start fresh rather than crash.
        }
        return list;
    }

    public static void save(@NonNull Context context, @NonNull List<Server> servers) {
        JSONArray arr = new JSONArray();
        for (Server s : servers) {
            try {
                arr.put(s.toJson());
            } catch (JSONException ignored) {
            }
        }
        File file = new File(context.getFilesDir(), FILENAME);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static byte[] readAll(@NonNull File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
