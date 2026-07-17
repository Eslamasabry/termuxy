package com.termux.app.servers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A remote server the user connects to in order to drive a persistent tmux session
 * (typically running an AI coding agent). Stored locally by {@link ServerStore}.
 */
public class Server {

    public final long id;

    @NonNull
    public String name;

    @NonNull
    public String host;

    public int port;

    @NonNull
    public String user;

    /** Optional path to a private key on device (e.g. {@code ~/.ssh/id_ed25519}). Null = default/agent. */
    @Nullable
    public String keyPath;

    /**
     * Optional password for ssh login. When set, the connect command is wrapped with
     * {@code sshpass} so the password is entered automatically when ssh prompts for it.
     * Stored in app-private storage; Keystore encryption is a tracked fast-follow.
     */
    @Nullable
    public String password;

    /** Prefer mosh (resilient roaming) over plain ssh when available. */
    public boolean useMosh;

    /** tmux session to attach-or-create on the remote. Defaults to "termuxy". */
    @NonNull
    public String tmuxSession;

    public Server(long id, @NonNull String name, @NonNull String host, int port,
                  @NonNull String user, @Nullable String keyPath, @Nullable String password,
                  boolean useMosh, @NonNull String tmuxSession) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.user = user;
        this.keyPath = keyPath;
        this.password = password;
        this.useMosh = useMosh;
        this.tmuxSession = tmuxSession;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("host", host);
        o.put("port", port);
        o.put("user", user);
        o.put("useMosh", useMosh);
        o.put("tmuxSession", tmuxSession);
        if (keyPath != null) o.put("keyPath", keyPath);
        if (password != null) o.put("password", password);
        return o;
    }

    @NonNull
    public static Server fromJson(@NonNull JSONObject o) throws JSONException {
        String key = o.has("keyPath") && !o.isNull("keyPath") ? o.optString("keyPath") : null;
        if (key != null && key.isEmpty()) key = null;
        String pw = o.has("password") && !o.isNull("password") ? o.optString("password") : null;
        if (pw != null && pw.isEmpty()) pw = null;
        String tmux = o.optString("tmuxSession", "termuxy");
        if (tmux.isEmpty()) tmux = "termuxy";
        return new Server(
            o.optLong("id", 0L),
            o.optString("name", ""),
            o.optString("host", ""),
            o.optInt("port", 22),
            o.optString("user", ""),
            key,
            pw,
            o.optBoolean("useMosh", false),
            tmux
        );
    }
}
