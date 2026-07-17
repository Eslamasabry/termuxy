package com.termux.app.servers;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.terminal.TerminalSession;

/**
 * Connects to a {@link Server} by opening a new terminal session and running an ssh (or mosh)
 * command that attaches to — or creates — the remote tmux session.
 *
 * <p>The command guarantees the required client package is installed first ({@code openssh} or
 * {@code mosh}), then connects with {@code tmux new-session -A -s <session>} which attaches if the
 * session exists or creates it otherwise. This keeps the agent process running on the server across
 * disconnects; reconnect simply re-attaches and the scrollback is right there.</p>
 *
 * <p>Auth precedence: an installed SSH key (preferred, passwordless, and makes mosh work cleanly)
 * beats a saved password (which is auto-entered via sshpass). One-tap key setup via
 * {@link #installKey(Server)}.</p>
 */
public class ServerConnector {

    private static final int MAX_ATTEMPTS = 25;
    private static final long ATTEMPT_DELAY_MS = 100;

    /** Default on-device key path used by {@link #installKey(Server)}. */
    public static final String DEFAULT_KEY_PATH = "~/.ssh/termuxy_ed25519";

    private final TermuxActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public ServerConnector(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    public void connect(@NonNull Server server) {
        if (server.host.isEmpty() || server.user.isEmpty()) {
            toast(R.string.termuxy_servers_required);
            return;
        }
        runInNewSession(serverName(server), buildCommand(server));
    }

    /**
     * Generates an ed25519 key on-device (if absent) and appends its public half to the server's
     * {@code ~/.ssh/authorized_keys} using the saved password once. After this, connect uses the
     * key (passwordless) — which also lets mosh run without sshpass.
     */
    public void installKey(@NonNull Server server) {
        if (server.host.isEmpty() || server.user.isEmpty()) {
            toast(R.string.termuxy_servers_required);
            return;
        }
        if (server.password == null || server.password.isEmpty()) {
            toast(R.string.termuxy_servers_need_password_for_key);
            return;
        }
        runInNewSession("key:" + serverName(server), buildKeyInstallCommand(server));
    }

    private void runInNewSession(@NonNull String sessionName, @NonNull String command) {
        TermuxTerminalSessionActivityClient client = mActivity.getTermuxTerminalSessionClient();
        if (client == null) {
            toast(R.string.termuxy_terminal_not_ready);
            return;
        }

        client.addNewSession(false, sessionName);

        final String commandWithEnter = command + "\r";
        final int[] attempts = {0};
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                TerminalSession session = mActivity.getCurrentSession();
                if (session != null && session.isRunning()) {
                    session.write(commandWithEnter);
                    closeDrawer();
                    return;
                }
                if (attempts[0]++ < MAX_ATTEMPTS) {
                    mHandler.postDelayed(this, ATTEMPT_DELAY_MS);
                } else if (session != null) {
                    // Last resort: send even if we could not confirm the shell pid in time.
                    session.write(commandWithEnter);
                    closeDrawer();
                }
            }
        });
    }

    @NonNull
    private static String serverName(@NonNull Server server) {
        return (server.name == null || server.name.isEmpty()) ? "server" : server.name;
    }

    @NonNull
    private String buildCommand(@NonNull Server server) {
        int port = server.port > 0 ? server.port : 22;
        String session = (server.tmuxSession == null || server.tmuxSession.isEmpty())
                ? "termuxy" : server.tmuxSession;
        String target = server.user + "@" + server.host;

        boolean useKey = server.keyPath != null && !server.keyPath.isEmpty();
        boolean usePassword = !useKey && server.password != null && !server.password.isEmpty();

        // Base ssh flags (shared by ssh and the mosh --ssh wrapper).
        StringBuilder sshFlags = new StringBuilder("ssh");
        sshFlags.append(" -o StrictHostKeyChecking=accept-new"); // never hang on first-connect host-key prompt
        sshFlags.append(" -o ServerAliveInterval=30");           // keep-alive so links over Tailscale don't idle-die
        if (useKey) {
            sshFlags.append(" -i ").append(shellQuote(server.keyPath));
        }
        sshFlags.append(" -p ").append(port);

        // Only fall back to sshpass when no key is configured. Keys are preferred because they
        // keep sshpass (and its pty) out of mosh's way, so mosh actually works.
        String sshpassInstall = usePassword
                ? "{ command -v sshpass >/dev/null 2>&1 || pkg install -y sshpass; } && "
                : "";
        String sshpassPrefix = usePassword
                ? "sshpass -p " + shellQuote(server.password) + " "
                : "";

        if (server.useMosh) {
            String moshInstall = "{ command -v mosh >/dev/null 2>&1 || pkg install -y mosh; } && ";
            return sshpassInstall + moshInstall + sshpassPrefix +
                    "mosh --ssh=" + shellQuote(sshFlags.toString()) + " " + shellQuote(target) +
                    " -- tmux new-session -A -s " + shellQuote(session);
        }

        return sshpassInstall +
                "{ command -v ssh >/dev/null 2>&1 || pkg install -y openssh; } && " +
                sshpassPrefix + sshFlags + " " + shellQuote(target) + " -t " +
                shellQuote("tmux new-session -A -s " + session);
    }

    @NonNull
    private String buildKeyInstallCommand(@NonNull Server server) {
        int port = server.port > 0 ? server.port : 22;
        String target = server.user + "@" + server.host;
        // NOTE: do NOT pipe the pubkey via ssh stdin — sshpass allocates a pty that overrides ssh's
        // stdin, so the remote `cat` would get nothing. Embed the pubkey in the remote command
        // instead (expanded locally by the phone shell inside double quotes).
        return "set -e; KEY=\"$HOME/.ssh/termuxy_ed25519\"; mkdir -p \"$HOME/.ssh\"; "
                + "[ -f \"$KEY\" ] || ssh-keygen -t ed25519 -f \"$KEY\" -N \"\" -C termuxy; "
                + "{ command -v sshpass >/dev/null 2>&1 || pkg install -y sshpass; }; "
                + "PUB=\"$(cat \"$KEY.pub\")\"; "
                + "sshpass -p " + shellQuote(server.password)
                + " ssh -o StrictHostKeyChecking=accept-new -p " + port + " " + shellQuote(target)
                + " \"mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo \\\"$PUB\\\" >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys\"; "
                + "echo 'TERMUXY_KEY_INSTALLED '\"$KEY\"";
    }

    @NonNull
    private static String shellQuote(@NonNull String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void closeDrawer() {
        try {
            if (mActivity.getDrawer() != null) mActivity.getDrawer().closeDrawers();
        } catch (Exception ignored) {
        }
    }

    private void toast(int resId) {
        Toast.makeText(mActivity, resId, Toast.LENGTH_SHORT).show();
    }
}
