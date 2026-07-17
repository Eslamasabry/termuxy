package com.termux.app.servers;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.List;

/**
 * The Cockpit "Servers" flow: pick a saved server to connect, or add/edit/delete entries.
 * Uses AlertDialogs to stay consistent with the rest of the Termuxy command center.
 */
public class ServersDialog {

    private final TermuxActivity mActivity;
    private final ServerConnector mConnector;

    public ServersDialog(@NonNull TermuxActivity activity) {
        mActivity = activity;
        mConnector = new ServerConnector(activity);
    }

    public void show() {
        closeDrawer();
        final List<Server> servers = ServerStore.load(mActivity);
        if (servers.isEmpty()) {
            showEditor(null, null);
            return;
        }
        String[] labels = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) {
            Server s = servers.get(i);
            labels[i] = s.name + "\n" + s.user + "@" + s.host + ":" + s.port
                    + (s.useMosh ? "  · mosh" : "  · ssh");
        }
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.termuxy_servers_title)
                .setItems(labels, (d, which) -> mConnector.connect(servers.get(which)))
                .setPositiveButton(R.string.termuxy_servers_add, (d, w) -> showEditor(null, null))
                .setNeutralButton(R.string.termuxy_servers_edit, (d, w) -> showEditPicker(servers))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditPicker(@NonNull final List<Server> servers) {
        String[] labels = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) {
            labels[i] = servers.get(i).name + "  —  " + servers.get(i).user + "@" + servers.get(i).host;
        }
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.termuxy_servers_edit)
                .setItems(labels, (d, which) -> showEditor(servers.get(which), servers))
                .show();
    }

    private void showEditor(@Nullable final Server editing, @Nullable final List<Server> list) {
        View form = LayoutInflater.from(mActivity).inflate(R.layout.termuxy_server_editor, null);
        EditText name = form.findViewById(R.id.server_name);
        EditText host = form.findViewById(R.id.server_host);
        EditText port = form.findViewById(R.id.server_port);
        EditText user = form.findViewById(R.id.server_user);
        EditText key = form.findViewById(R.id.server_key);
        EditText password = form.findViewById(R.id.server_password);
        EditText tmux = form.findViewById(R.id.server_tmux);
        CheckBox mosh = form.findViewById(R.id.server_mosh);

        if (editing != null) {
            name.setText(editing.name);
            host.setText(editing.host);
            port.setText(String.valueOf(editing.port));
            user.setText(editing.user);
            if (editing.keyPath != null) key.setText(editing.keyPath);
            if (editing.password != null) password.setText(editing.password);
            tmux.setText(editing.tmuxSession);
            mosh.setChecked(editing.useMosh);
        } else {
            port.setText("22");
            tmux.setText("termuxy");
        }

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle(editing != null ? R.string.termuxy_servers_edit : R.string.termuxy_servers_add)
                .setView(form)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        if (editing != null) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL,
                    mActivity.getString(R.string.termuxy_servers_delete),
                    (d, w) -> {
                        List<Server> current = (list != null) ? list : ServerStore.load(mActivity);
                        current.remove(editing);
                        ServerStore.save(mActivity, current);
                    });
        }

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Server built = buildFromForm(editing, name, host, port, user, key, password, tmux, mosh);
                    if (built == null) return;
                    List<Server> current = (list != null) ? list : ServerStore.load(mActivity);
                    if (editing != null) {
                        int idx = current.indexOf(editing);
                        if (idx >= 0) current.set(idx, built); else current.add(built);
                    } else {
                        current.add(built);
                    }
                    ServerStore.save(mActivity, current);
                    dialog.dismiss();
                }));

        dialog.show();
    }

    @Nullable
    private Server buildFromForm(@Nullable Server editing, EditText name, EditText host, EditText port,
                                 EditText user, EditText key, EditText password, EditText tmux, CheckBox mosh) {
        String n = name.getText().toString().trim();
        String h = host.getText().toString().trim();
        String u = user.getText().toString().trim();
        if (h.isEmpty() || u.isEmpty()) {
            host.setError(mActivity.getString(R.string.termuxy_servers_required));
            return null;
        }
        if (n.isEmpty()) n = u + "@" + h;
        int p = 22;
        try {
            p = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        String k = key.getText().toString().trim();
        String pw = password.getText().toString();
        String s = tmux.getText().toString().trim();
        return new Server(
                editing != null ? editing.id : System.currentTimeMillis(),
                n, h, p, u,
                k.isEmpty() ? null : k,
                pw.isEmpty() ? null : pw,
                mosh.isChecked(),
                s.isEmpty() ? "termuxy" : s
        );
    }

    private void closeDrawer() {
        try {
            if (mActivity.getDrawer() != null) mActivity.getDrawer().closeDrawers();
        } catch (Exception ignored) {
        }
    }
}
