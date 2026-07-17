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
 */
public class ServerConnector {

    private static final int MAX_ATTEMPTS = 25;
    private static final long ATTEMPT_DELAY_MS = 100;

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
        TermuxTerminalSessionActivityClient client = mActivity.getTermuxTerminalSessionClient();
        if (client == null) {
            toast(R.string.termuxy_terminal_not_ready);
            return;
        }

        String sessionName = (server.name == null || server.name.isEmpty()) ? "server" : server.name;
        client.addNewSession(false, sessionName);

        final String command = buildCommand(server) + "\r";
        final int[] attempts = {0};
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                TerminalSession session = mActivity.getCurrentSession();
                if (session != null && session.isRunning()) {
                    session.write(command);
                    closeDrawer();
                    return;
                }
                if (attempts[0]++ < MAX_ATTEMPTS) {
                    mHandler.postDelayed(this, ATTEMPT_DELAY_MS);
                } else if (session != null) {
                    // Last resort: send even if we could not confirm the shell pid in time.
                    session.write(command);
                    closeDrawer();
                }
            }
        });
    }

    @NonNull
    private String buildCommand(@NonNull Server server) {
        int port = server.port > 0 ? server.port : 22;
        String session = (server.tmuxSession == null || server.tmuxSession.isEmpty())
                ? "termuxy" : server.tmuxSession;
        String target = server.user + "@" + server.host;
        boolean hasPassword = server.password != null && !server.password.isEmpty();

        // Base ssh flags (shared by ssh and the mosh --ssh wrapper).
        StringBuilder sshFlags = new StringBuilder("ssh");
        if (server.keyPath != null && !server.keyPath.isEmpty()) {
            sshFlags.append(" -i ").append(shellQuote(server.keyPath));
        }
        sshFlags.append(" -p ").append(port);

        // When a password is saved, wrap the connection in sshpass so it is entered
        // automatically at the ssh prompt. sshpass is auto-installed if missing.
        String sshpassInstall = hasPassword
                ? "{ command -v sshpass >/dev/null 2>&1 || pkg install -y sshpass; } && "
                : "";
        String sshpassPrefix = hasPassword
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
