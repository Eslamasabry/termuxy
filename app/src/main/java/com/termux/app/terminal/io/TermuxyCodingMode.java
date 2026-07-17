package com.termux.app.terminal.io;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

public class TermuxyCodingMode {

    private static final String CTRL_B = "\u0002";
    private static final String ESC = "\u001b";
    private static final String CTRL_C = "\u0003";

    private final TermuxActivity mActivity;

    public TermuxyCodingMode(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    public void startCodingWorkspace() {
        writeCommand(getCodingWorkspaceCommand());
    }

    public void installPackageAutoheal() {
        writeCommand(getPackageAutohealCommand());
    }

    /**
     * Installs and starts a local whisper.cpp server in the termuxy Linux (proot Debian)
     * workspace, listening on 127.0.0.1:8080. Voice mode POSTs mic audio to it for on-device,
     * offline transcription. Run once; re-run to rebuild/update.
     */
    public void installWhisperServer() {
        writeCommand(getWhisperSetupCommand());
    }

    public void showAgentPromptDialog() {
        EditText promptInput = new EditText(mActivity);
        promptInput.setMinLines(6);
        promptInput.setMaxLines(12);
        promptInput.setSingleLine(false);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
            .setTitle(R.string.action_agent_prompt)
            .setView(promptInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_voice_input, null)
            .setPositiveButton(R.string.action_send_to_agent, null)
            .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                mActivity.getTermuxVoiceInput().start(promptInput));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String prompt = promptInput.getText().toString().trim();
                if (prompt.isEmpty()) return;

                sendAgentPrompt(prompt);
                dialog.dismiss();
                mActivity.getDrawer().closeDrawers();
            });
        });

        dialog.show();
    }

    public void sendTmuxPrefix() {
        write(CTRL_B);
    }

    public void splitHorizontal() {
        write(CTRL_B + "\"");
    }

    public void splitVertical() {
        write(CTRL_B + "%");
    }

    public void newWindow() {
        write(CTRL_B + "c");
    }

    public void chooseSession() {
        write(CTRL_B + "s");
    }

    /** Approve the agent's current prompt (sends y + Enter). */
    public void sendApprove() {
        writeCommand("y");
    }

    /** Deny the agent's current prompt (sends n + Enter). */
    public void sendDeny() {
        writeCommand("n");
    }

    /** Send Escape (dismiss popups, cancel the current agent input). */
    public void sendEscape() {
        write(ESC);
    }

    /** Send Ctrl-C (interrupt the running agent/command). */
    public void sendInterrupt() {
        write(CTRL_C);
    }

    @NonNull
    private String getWhisperSetupCommand() {
        return "cat > \"$HOME/.termuxy-whisper-setup.sh\" <<'TERMUXY_EOF'\n" +
            "set -e\n" +
            "PREFIX=\"/data/data/io.termuxy/files/usr\"\n" +
            "if ! command -v proot-distro >/dev/null 2>&1; then echo 'Installing proot-distro...'; pkg install -y proot-distro; fi\n" +
            "if [ ! -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/debian\" ]; then proot-distro install debian; fi\n" +
            "proot-distro login debian --shared-tmp -- bash -s <<'WHISPER_EOF'\n" +
            "set -e\n" +
            "export DEBIAN_FRONTEND=noninteractive\n" +
            "cd /root\n" +
            "if [ ! -d whisper.cpp ]; then\n" +
            "  echo 'Installing build tools...'\n" +
            "  apt-get update\n" +
            "  apt-get install -y git cmake build-essential\n" +
            "  echo 'Cloning whisper.cpp...'\n" +
            "  git clone --depth 1 https://github.com/ggerganov/whisper.cpp\n" +
            "fi\n" +
            "cd /root/whisper.cpp\n" +
            "if [ ! -f build/bin/whisper-server ] && [ ! -f build/bin/server ]; then\n" +
            "  echo 'Building whisper.cpp server (slow on a phone)...'\n" +
            "  cmake -B build -DWHISPER_BUILD_SERVER=ON\n" +
            "  cmake --build build --config Release -j\n" +
            "fi\n" +
            "SERVER_BIN=\"build/bin/whisper-server\"\n" +
            "[ -f \"$SERVER_BIN\" ] || SERVER_BIN=\"build/bin/server\"\n" +
            "if [ ! -f models/ggml-base.en.bin ]; then\n" +
            "  echo 'Downloading base.en model (~142MB)...'\n" +
            "  bash models/download-ggml-model.sh base.en\n" +
            "fi\n" +
            "echo 'Starting whisper server on 127.0.0.1:8080...'\n" +
            "pkill -f whisper-server 2>/dev/null || true\n" +
            "nohup ./$SERVER_BIN -m models/ggml-base.en.bin --host 127.0.0.1 --port 8080 > /tmp/whisper-server.log 2>&1 &\n" +
            "sleep 2\n" +
            "echo 'whisper server started. Open Voice Mode again. Log: /tmp/whisper-server.log'\n" +
            "WHISPER_EOF\n" +
            "TERMUXY_EOF\n" +
            "bash \"$HOME/.termuxy-whisper-setup.sh\"";
    }

    private void writeCommand(String command) {
        write(command + "\r");
    }

    private void write(String text) {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        if (session.isRunning()) {
            session.write(text);
        } else {
            mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
        }
    }

    @NonNull
    private String getCodingWorkspaceCommand() {
        return "cat > \"$HOME/.termuxy-linux-workspace.sh\" <<'TERMUXY_EOF'\n" +
            "set -e\n" +
            "mkdir -p \"$HOME/.termuxy\"\n" +
            getPackageAutohealCommand() + "\n" +
            "cat > \"$PREFIX/bin/termuxy-linux\" <<'LINUX'\n" +
            "#!/data/data/io.termuxy/files/usr/bin/bash\n" +
            "set -e\n" +
            "distro=\"${TERMUXY_DISTRO:-debian}\"\n" +
            "if ! command -v proot-distro >/dev/null 2>&1; then\n" +
            "  echo 'proot-distro is not installed. Run Coding Mode again after host packages finish installing.'\n" +
            "  exit 1\n" +
            "fi\n" +
            "if [ ! -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/$distro\" ]; then\n" +
            "  proot-distro install \"$distro\"\n" +
            "fi\n" +
            "proot-distro login \"$distro\" --shared-tmp -- bash -s <<'LINUX_ENTRY'\n" +
            "set -e\n" +
            "export DEBIAN_FRONTEND=noninteractive\n" +
            "mkdir -p /root/workspace\n" +
            "if [ ! -f /root/.termuxy-base-ready ]; then\n" +
            "  apt-get update\n" +
            "  apt-get install -y ca-certificates curl git tmux python3 python3-pip nodejs npm openssh-client ripgrep fd-find nano\n" +
            "  touch /root/.termuxy-base-ready\n" +
            "fi\n" +
            "cat > /root/.tmux.conf <<'TMUXCONF'\n" +
            "set -g mouse on\n" +
            "set -g history-limit 50000\n" +
            "set -g status-style bg=black,fg=white\n" +
            "set -g status-left '#[bold]Termuxy Linux #[fg=green]#S '\n" +
            "set -g status-right '#[fg=cyan]%H:%M'\n" +
            "setw -g mode-keys vi\n" +
            "bind-key | split-window -h -c '#{pane_current_path}'\n" +
            "bind-key - split-window -v -c '#{pane_current_path}'\n" +
            "bind-key r source-file ~/.tmux.conf \\; display-message 'tmux config reloaded'\n" +
            "TMUXCONF\n" +
            "if ! tmux has-session -t termuxy 2>/dev/null; then\n" +
            "  tmux new-session -d -s termuxy -n code -c /root/workspace\n" +
            "  tmux split-window -h -t termuxy:code -c /root/workspace\n" +
            "  tmux split-window -v -t termuxy:code.1 -c /root/workspace\n" +
            "  tmux send-keys -t termuxy:code.0 \"printf 'code pane: clone or open project here\\\\n'\" C-m\n" +
            "  tmux send-keys -t termuxy:code.1 \"printf 'shell pane: commands here\\\\n'\" C-m\n" +
            "  tmux send-keys -t termuxy:code.2 \"printf 'tests/logs pane: feedback here\\\\n'\" C-m\n" +
            "  tmux select-pane -t termuxy:code.0\n" +
            "fi\n" +
            "exec tmux attach-session -t termuxy\n" +
            "LINUX_ENTRY\n" +
            "LINUX\n" +
            "chmod 700 \"$PREFIX/bin/termuxy-linux\"\n" +
            getAgentLauncherInstallCommand() +
            "missing=\"\"\n" +
            "need_pkg() {\n" +
            "  command -v \"$2\" >/dev/null 2>&1 || missing=\"$missing $1\"\n" +
            "}\n" +
            "need_pkg proot-distro proot-distro\n" +
            "need_pkg tmux tmux\n" +
            "if [ -n \"$missing\" ]; then\n" +
            "  echo \"Installing host packages:$missing\"\n" +
            "  [ -x \"$PREFIX/bin/termuxy-autoheal\" ] && \"$PREFIX/bin/termuxy-autoheal\" || true\n" +
            "  pkg install -y $missing\n" +
            "fi\n" +
            "termuxy-linux\n" +
            "TERMUXY_EOF\n" +
            "bash \"$HOME/.termuxy-linux-workspace.sh\"";
    }

    @NonNull
    private String getPackageAutohealCommand() {
        return "mkdir -p \"$PREFIX/bin\" \"$PREFIX/etc/apt/apt.conf.d\" \"$PREFIX/etc/profile.d\" \"$PREFIX/tmp\"\n" +
            "if [ -x \"$PREFIX/bin/pkg\" ] && ! grep -q 'TERMUXY_PKG_WRAPPER' \"$PREFIX/bin/pkg\" 2>/dev/null; then\n" +
            "  rm -f \"$PREFIX/bin/pkg.real\"\n" +
            "  mv \"$PREFIX/bin/pkg\" \"$PREFIX/bin/pkg.real\"\n" +
            "fi\n" +
            "if [ -x \"$PREFIX/bin/apt\" ] && ! grep -q 'TERMUXY_APT_WRAPPER' \"$PREFIX/bin/apt\" 2>/dev/null; then\n" +
            "  rm -f \"$PREFIX/bin/apt.real\"\n" +
            "  mv \"$PREFIX/bin/apt\" \"$PREFIX/bin/apt.real\"\n" +
            "fi\n" +
            "cat > \"$PREFIX/bin/termuxy-fix-deb-paths\" <<'TERMUXY_FIX'\n" +
            "#!/data/data/io.termuxy/files/usr/bin/bash\n" +
            "# TERMUXY_FIX_DEB_PATHS\n" +
            "set -e\n" +
            "PREFIX=\"/data/data/io.termuxy/files/usr\"\n" +
            "OLD=\"/data/data/com.termux\"\n" +
            "NEW=\"/data/data/io.termuxy\"\n" +
            "TMPBASE=\"${TMPDIR:-$PREFIX/tmp}/termuxy-apt-pathfix-$$\"\n" +
            "mkdir -p \"$TMPBASE\"\n" +
            "cleanup() { rm -rf \"$TMPBASE\"; }\n" +
            "trap cleanup EXIT INT TERM\n" +
            "fix_deb() {\n" +
            "  local deb=\"$1\"\n" +
            "  local work out target patched\n" +
            "  [ -f \"$deb\" ] || return 0\n" +
            "  \"$PREFIX/bin/dpkg-deb\" -c \"$deb\" 2>/dev/null | grep -q 'data/data/com\\.termux' || return 0\n" +
            "  work=\"$TMPBASE/$(basename \"$deb\").d\"\n" +
            "  out=\"$TMPBASE/$(basename \"$deb\")\"\n" +
            "  rm -rf \"$work\"\n" +
            "  mkdir -p \"$work\"\n" +
            "  \"$PREFIX/bin/dpkg-deb\" -R \"$deb\" \"$work\"\n" +
            "  if [ -d \"$work/data/data/com.termux\" ]; then\n" +
            "    mkdir -p \"$work/data/data\"\n" +
            "    rm -rf \"$work/data/data/io.termuxy\"\n" +
            "    mv \"$work/data/data/com.termux\" \"$work/data/data/io.termuxy\"\n" +
            "  fi\n" +
            "  find \"$work\" -type l | while IFS= read -r link; do\n" +
            "    target=\"$(readlink \"$link\")\"\n" +
            "    patched=\"${target//$OLD/$NEW}\"\n" +
            "    if [ \"$target\" != \"$patched\" ]; then\n" +
            "      ln -sfn \"$patched\" \"$link\"\n" +
            "    fi\n" +
            "  done\n" +
            "  find \"$work\" -type f -exec sed -i \"s|$OLD|$NEW|g\" {} + 2>/dev/null || true\n" +
            "  if [ -d \"$work/DEBIAN\" ]; then\n" +
            "    find \"$work/DEBIAN\" -type f \\( -name preinst -o -name postinst -o -name prerm -o -name postrm -o -name config \\) -exec chmod 755 {} +\n" +
            "    find \"$work/DEBIAN\" -type f ! \\( -name preinst -o -name postinst -o -name prerm -o -name postrm -o -name config \\) -exec chmod 644 {} +\n" +
            "  fi\n" +
            "  \"$PREFIX/bin/dpkg-deb\" -b \"$work\" \"$out\" >/dev/null\n" +
            "  cat \"$out\" > \"$deb\"\n" +
            "}\n" +
            "if [ \"${1:-}\" = \"--stdin\" ]; then\n" +
            "  while IFS= read -r deb; do\n" +
            "    fix_deb \"$deb\"\n" +
            "  done\n" +
            "fi\n" +
            "for deb in \"$PREFIX\"/tmp/apt-dpkg-install-*/*.deb \"$PREFIX\"/var/cache/apt/archives/*.deb; do\n" +
            "  [ -e \"$deb\" ] || continue\n" +
            "  fix_deb \"$deb\"\n" +
            "done\n" +
            "TERMUXY_FIX\n" +
            "chmod 700 \"$PREFIX/bin/termuxy-fix-deb-paths\"\n" +
            "printf 'DPkg::Pre-Install-Pkgs { \"%s --stdin\"; };\\n' \"$PREFIX/bin/termuxy-fix-deb-paths\" > \"$PREFIX/etc/apt/apt.conf.d/00termuxy-pathfix\"\n" +
            "cat > \"$PREFIX/bin/apt\" <<'TERMUXY_APT'\n" +
            "#!/data/data/io.termuxy/files/usr/bin/bash\n" +
            "# TERMUXY_APT_WRAPPER\n" +
            "set -e\n" +
            "PREFIX=\"/data/data/io.termuxy/files/usr\"\n" +
            "[ -x \"$PREFIX/bin/termuxy-autoheal\" ] && \"$PREFIX/bin/termuxy-autoheal\" >/dev/null 2>&1 || true\n" +
            "exec \"$PREFIX/bin/apt.real\" \"$@\"\n" +
            "TERMUXY_APT\n" +
            "chmod 700 \"$PREFIX/bin/apt\"\n" +
            "cat > \"$PREFIX/bin/pkg\" <<'TERMUXY_PKG'\n" +
            "#!/data/data/io.termuxy/files/usr/bin/bash\n" +
            "# TERMUXY_PKG_WRAPPER\n" +
            "set -e\n" +
            "PREFIX=\"/data/data/io.termuxy/files/usr\"\n" +
            "[ -x \"$PREFIX/bin/termuxy-autoheal\" ] && \"$PREFIX/bin/termuxy-autoheal\" >/dev/null 2>&1 || true\n" +
            "exec \"$PREFIX/bin/pkg.real\" \"$@\"\n" +
            "TERMUXY_PKG\n" +
            "chmod 700 \"$PREFIX/bin/pkg\"\n" +
            "cat > \"$PREFIX/bin/termuxy-autoheal\" <<'TERMUXY_AUTO'\n" +
            "#!/data/data/io.termuxy/files/usr/bin/bash\n" +
            "# TERMUXY_AUTOHEAL\n" +
            "set -e\n" +
            "PREFIX=\"/data/data/io.termuxy/files/usr\"\n" +
            "mkdir -p \"$PREFIX/etc/apt/apt.conf.d\"\n" +
            "if [ -x \"$PREFIX/bin/termuxy-fix-deb-paths\" ]; then\n" +
            "  printf 'DPkg::Pre-Install-Pkgs { \"%s --stdin\"; };\\n' \"$PREFIX/bin/termuxy-fix-deb-paths\" > \"$PREFIX/etc/apt/apt.conf.d/00termuxy-pathfix\"\n" +
            "  \"$PREFIX/bin/termuxy-fix-deb-paths\" >/dev/null 2>&1 || true\n" +
            "fi\n" +
            "TERMUXY_AUTO\n" +
            "chmod 700 \"$PREFIX/bin/termuxy-autoheal\"\n" +
            "cat > \"$PREFIX/etc/profile.d/00-termuxy-autoheal.sh\" <<'TERMUXY_PROFILE'\n" +
            "if [ -x \"/data/data/io.termuxy/files/usr/bin/termuxy-autoheal\" ]; then\n" +
            "  \"/data/data/io.termuxy/files/usr/bin/termuxy-autoheal\" >/dev/null 2>&1 || true\n" +
            "fi\n" +
            "TERMUXY_PROFILE\n" +
            "\"$PREFIX/bin/termuxy-autoheal\" >/dev/null 2>&1 || true\n" +
            "printf 'Termuxy autoheal installed\\n'";
    }

    private void sendAgentPrompt(@NonNull String prompt) {
        String delimiter = "TERMUXY_AGENT_PROMPT_" + System.currentTimeMillis();
        while (prompt.contains(delimiter)) {
            delimiter = delimiter + "_";
        }

        String command = "set -e\n" +
            "mkdir -p \"$HOME/.termuxy\"\n" +
            getAgentLauncherInstallCommand() +
            "cat > \"$HOME/.termuxy/last-agent-prompt.md\" <<'" + delimiter + "'\n" +
            "# Termuxy Agent Prompt\n" +
            "\n" +
            "You are coding from Termuxy on Android inside the current terminal or tmux pane.\n" +
            "\n" +
            "User request:\n" +
            prompt + "\n" +
            "\n" +
            "Operating rules:\n" +
            "- Keep work local unless explicitly asked otherwise.\n" +
            "- Inspect the current project before editing.\n" +
            "- Preserve unrelated user changes.\n" +
            "- Run the smallest useful verification before reporting done.\n" +
            delimiter + "\n" +
            "termuxy-agent \"$HOME/.termuxy/last-agent-prompt.md\"";
        writeCommand(command);
    }

    @NonNull
    private String getAgentLauncherInstallCommand() {
        return "cat > \"$PREFIX/bin/termuxy-agent\" <<'AGENT'\n" +
            "#!/data/data/io.termuxy/files/usr/bin/bash\n" +
            "set -e\n" +
            "PREFIX=\"/data/data/io.termuxy/files/usr\"\n" +
            "prompt_file=\"${1:-$HOME/.termuxy/last-agent-prompt.md}\"\n" +
            "if [ ! -f \"$prompt_file\" ]; then\n" +
            "  echo \"No prompt file found: $prompt_file\"\n" +
            "  exit 1\n" +
            "fi\n" +
            "mkdir -p \"$HOME/.termuxy\"\n" +
            "mkdir -p \"$PREFIX/tmp\"\n" +
            "cp \"$prompt_file\" \"$PREFIX/tmp/termuxy-agent-prompt.md\"\n" +
            "context_file=\"$HOME/.termuxy/last-agent-context.md\"\n" +
            "{\n" +
            "  echo '# Termuxy Context'\n" +
            "  printf 'cwd: %s\\n' \"$PWD\"\n" +
            "  if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then\n" +
            "    printf '\\n## Git\\n'\n" +
            "    git status --short\n" +
            "  fi\n" +
            "} > \"$context_file\"\n" +
            "if command -v proot-distro >/dev/null 2>&1 && [ -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/debian\" ]; then\n" +
            "  proot-distro login debian --shared-tmp -- bash -s <<'LINUX_AGENT'\n" +
            "set -e\n" +
            "prompt_file=\"/tmp/termuxy-agent-prompt.md\"\n" +
            "cd /root/workspace 2>/dev/null || cd /root\n" +
            "if command -v codex >/dev/null 2>&1; then\n" +
            "  codex \"$(cat \"$prompt_file\")\"\n" +
            "elif command -v opencode >/dev/null 2>&1; then\n" +
            "  opencode run \"$(cat \"$prompt_file\")\"\n" +
            "elif command -v claude >/dev/null 2>&1; then\n" +
            "  claude \"$(cat \"$prompt_file\")\"\n" +
            "elif command -v aider >/dev/null 2>&1; then\n" +
            "  aider --message-file \"$prompt_file\"\n" +
            "else\n" +
            "  echo 'No supported coding agent found in Debian.'\n" +
            "  echo 'Install codex, opencode, claude, or aider inside the Linux Workspace.'\n" +
            "  printf '\\nPrompt saved to %s\\n\\n' \"$prompt_file\"\n" +
            "  cat \"$prompt_file\"\n" +
            "fi\n" +
            "LINUX_AGENT\n" +
            "elif command -v codex >/dev/null 2>&1; then\n" +
            "  codex \"$(cat \"$prompt_file\")\"\n" +
            "elif command -v opencode >/dev/null 2>&1; then\n" +
            "  opencode run \"$(cat \"$prompt_file\")\"\n" +
            "elif command -v claude >/dev/null 2>&1; then\n" +
            "  claude \"$(cat \"$prompt_file\")\"\n" +
            "elif command -v aider >/dev/null 2>&1; then\n" +
            "  aider --message-file \"$prompt_file\"\n" +
            "else\n" +
            "  echo 'No supported local coding agent found.'\n" +
            "  echo 'Open Linux Workspace, then install codex, opencode, claude, or aider in Debian.'\n" +
            "  printf '  termuxy-agent %q\\n' \"$prompt_file\"\n" +
            "  printf '\\nPrompt saved to %s\\n' \"$prompt_file\"\n" +
            "  printf 'Context saved to %s\\n\\n' \"$context_file\"\n" +
            "  cat \"$prompt_file\"\n" +
            "fi\n" +
            "AGENT\n" +
            "chmod 700 \"$PREFIX/bin/termuxy-agent\"\n";
    }
}
