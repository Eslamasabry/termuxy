package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";
    private static final String DEFAULT_TERMUX_PACKAGE_NAME = "com.termux";
    private static final String DEFAULT_TERMUX_DATA_DIR_PATH = "/data/data/" + DEFAULT_TERMUX_PACKAGE_NAME;

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else if (!isInstalledBootstrapCompatible()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but was installed with incompatible bootstrap paths. Attempting in-place repair.");
                if (repairExistingBootstrapIfNeeded() && isInstalledBootstrapCompatible()) {
                    whenDone.run();
                    return;
                }
                Logger.logInfo(LOG_TAG, "In-place repair failed. Reinstalling bootstrap.");
            } else {
                repairExistingBootstrapIfNeeded();
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = patchBootstrapPath(parts[0]);
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    patchBootstrapFile(targetFile);
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    installDpkgWrapperIfNeeded(TERMUX_STAGING_PREFIX_DIR);
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    private static boolean isInstalledBootstrapCompatible() {
        if (DEFAULT_TERMUX_PACKAGE_NAME.equals(TermuxConstants.TERMUX_PACKAGE_NAME)) return true;

        File loginFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "login");
        if (!loginFile.isFile()) return false;

        try (FileInputStream inStream = new FileInputStream(loginFile)) {
            byte[] buffer = new byte[512];
            int readBytes = inStream.read(buffer);
            if (readBytes < 0) return false;

            String start = new String(buffer, 0, readBytes, StandardCharsets.UTF_8);
            if (start.contains(DEFAULT_TERMUX_DATA_DIR_PATH) ||
                !start.contains(TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH))
                return false;

            File dpkgWrapperFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "dpkg");
            File dpkgRealFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "dpkg.real");
            if (!dpkgRealFile.isFile() || !doesFileContain(dpkgWrapperFile, "TERMUXY_DPKG_WRAPPER"))
                return false;

            File aptHookFile = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH,
                "apt/apt.conf.d/00termuxy-pathfix");
            File aptHookScriptFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH,
                "termuxy-fix-deb-paths");
            if (!doesFileContain(aptHookFile, "termuxy-fix-deb-paths") ||
                !doesFileContain(aptHookScriptFile, "TERMUXY_FIX_DEB_PATHS"))
                return false;

            File keyringLink = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH,
                "apt/trusted.gpg.d/grimler.gpg");
            if (keyringLink.exists()) {
                String keyringTarget = Os.readlink(keyringLink.getAbsolutePath());
                return !keyringTarget.contains(DEFAULT_TERMUX_DATA_DIR_PATH) &&
                    keyringTarget.contains(TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
            }

            return !start.contains(DEFAULT_TERMUX_DATA_DIR_PATH) &&
                start.contains(TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to check bootstrap compatibility", e);
            return false;
        }
    }

    private static void patchBootstrapFile(File targetFile) {
        if (DEFAULT_TERMUX_PACKAGE_NAME.equals(TermuxConstants.TERMUX_PACKAGE_NAME)) return;

        try {
            byte[] bytes;
            try (FileInputStream inStream = new FileInputStream(targetFile);
                 ByteArrayOutputStream outStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8096];
                int readBytes;
                while ((readBytes = inStream.read(buffer)) != -1) {
                    outStream.write(buffer, 0, readBytes);
                }
                bytes = outStream.toByteArray();
            }

            byte[] search = DEFAULT_TERMUX_DATA_DIR_PATH.getBytes(StandardCharsets.UTF_8);
            byte[] replacement = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH.getBytes(StandardCharsets.UTF_8);

            if (search.length == replacement.length && replaceBytes(bytes, search, replacement)) {
                try (FileOutputStream outStream = new FileOutputStream(targetFile, false)) {
                    outStream.write(bytes);
                }
                return;
            }

            if (!isLikelyTextFile(bytes)) return;

            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.contains(DEFAULT_TERMUX_DATA_DIR_PATH)) return;

            text = patchBootstrapPath(text);

            try (FileOutputStream outStream = new FileOutputStream(targetFile, false)) {
                outStream.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to patch bootstrap file \"" + targetFile + "\"", e);
        }
    }

    private static boolean doesFileContain(File file, String needle) {
        if (!file.isFile()) return false;

        try (FileInputStream inStream = new FileInputStream(file);
             ByteArrayOutputStream outStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8096];
            int readBytes;
            while ((readBytes = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, readBytes);
            }
            return new String(outStream.toByteArray(), StandardCharsets.UTF_8).contains(needle);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read file \"" + file + "\"", e);
            return false;
        }
    }

    static boolean repairExistingBootstrapIfNeeded() {
        if (DEFAULT_TERMUX_PACKAGE_NAME.equals(TermuxConstants.TERMUX_PACKAGE_NAME)) return true;

        try {
            installDpkgWrapperIfNeeded(TERMUX_PREFIX_DIR);
            installAptPathFixHookIfNeeded(TERMUX_PREFIX_DIR);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to repair existing bootstrap", e);
            return false;
        }
    }

    private static void installDpkgWrapperIfNeeded(File prefixDir) throws Exception {
        if (DEFAULT_TERMUX_PACKAGE_NAME.equals(TermuxConstants.TERMUX_PACKAGE_NAME)) return;

        File dpkgFile = new File(prefixDir, "bin/dpkg");
        File dpkgRealFile = new File(prefixDir, "bin/dpkg.real");
        if (!dpkgFile.isFile()) return;

        boolean dpkgFileIsWrapper = doesFileContain(dpkgFile, "TERMUXY_DPKG_WRAPPER");
        if (!dpkgFileIsWrapper) {
            if (dpkgRealFile.isFile() && !dpkgRealFile.delete())
                throw new RuntimeException("Failed to replace stale dpkg.real");
            if (!dpkgFile.renameTo(dpkgRealFile))
                throw new RuntimeException("Failed to move dpkg binary to dpkg.real");
        }

        if (!dpkgRealFile.isFile())
            throw new RuntimeException("Missing dpkg.real for dpkg wrapper");

        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String appDataDir = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
        String script = "#!" + prefix + "/bin/bash\n" +
            "# TERMUXY_DPKG_WRAPPER\n" +
            "set -e\n" +
            "PREFIX=\"" + prefix + "\"\n" +
            "OLD=\"" + DEFAULT_TERMUX_DATA_DIR_PATH + "\"\n" +
            "NEW=\"" + appDataDir + "\"\n" +
            "REAL=\"$PREFIX/bin/dpkg.real\"\n" +
            "TMPBASE=\"${TMPDIR:-$PREFIX/tmp}/termuxy-dpkg-$$\"\n" +
            "mkdir -p \"$TMPBASE\"\n" +
            "cleanup() { rm -rf \"$TMPBASE\"; }\n" +
            "trap cleanup EXIT INT TERM\n" +
            "rewrite_deb() {\n" +
            "  local deb=\"$1\"\n" +
            "  local name extract out\n" +
            "  name=\"$(basename \"$deb\")\"\n" +
            "  extract=\"$TMPBASE/extract-${#patched_args[@]}\"\n" +
            "  out=\"$TMPBASE/$name\"\n" +
            "  mkdir -p \"$extract\"\n" +
            "  \"$PREFIX/bin/dpkg-deb\" -R \"$deb\" \"$extract\"\n" +
            "  if [ -d \"$extract/data/data/com.termux\" ]; then\n" +
            "    mkdir -p \"$extract/data/data\"\n" +
            "    rm -rf \"$extract/data/data/io.termuxy\"\n" +
            "    mv \"$extract/data/data/com.termux\" \"$extract/data/data/io.termuxy\"\n" +
            "  fi\n" +
            "  while IFS= read -r link; do\n" +
            "    local target patched\n" +
            "    target=\"$(readlink \"$link\")\"\n" +
            "    patched=\"${target//$OLD/$NEW}\"\n" +
            "    if [ \"$target\" != \"$patched\" ]; then\n" +
            "      ln -sfn \"$patched\" \"$link\"\n" +
            "    fi\n" +
            "  done < <(find \"$extract\" -type l)\n" +
            "  find \"$extract\" -type f -exec sed -i \"s|$OLD|$NEW|g\" {} + 2>/dev/null || true\n" +
            "  if [ -d \"$extract/DEBIAN\" ]; then\n" +
            "    find \"$extract/DEBIAN\" -type f \\( -name preinst -o -name postinst -o -name prerm -o -name postrm -o -name config \\) -exec chmod 755 {} +\n" +
            "    find \"$extract/DEBIAN\" -type f ! \\( -name preinst -o -name postinst -o -name prerm -o -name postrm -o -name config \\) -exec chmod 644 {} +\n" +
            "  fi\n" +
            "  \"$PREFIX/bin/dpkg-deb\" -b \"$extract\" \"$out\" >/dev/null\n" +
            "  printf '%s\\n' \"$out\"\n" +
            "}\n" +
            "patched_args=()\n" +
            "for arg in \"$@\"; do\n" +
            "  if [[ \"$arg\" == *.deb && -f \"$arg\" ]]; then\n" +
            "    patched_args+=(\"$(rewrite_deb \"$arg\")\")\n" +
            "  else\n" +
            "    patched_args+=(\"$arg\")\n" +
            "  fi\n" +
            "done\n" +
            "exec \"$REAL\" \"${patched_args[@]}\"\n";

        try (FileOutputStream outStream = new FileOutputStream(dpkgFile, false)) {
            outStream.write(script.getBytes(StandardCharsets.UTF_8));
        }

        //noinspection OctalInteger
        Os.chmod(dpkgFile.getAbsolutePath(), 0700);
        //noinspection OctalInteger
        Os.chmod(dpkgRealFile.getAbsolutePath(), 0700);

        installAptPathFixHookIfNeeded(prefixDir);
    }

    private static void installAptPathFixHookIfNeeded(File prefixDir) throws Exception {
        if (DEFAULT_TERMUX_PACKAGE_NAME.equals(TermuxConstants.TERMUX_PACKAGE_NAME)) return;

        File binDir = new File(prefixDir, "bin");
        File aptConfDir = new File(prefixDir, "etc/apt/apt.conf.d");
        File profileDir = new File(prefixDir, "etc/profile.d");
        Error error = ensureDirectoryExists(binDir);
        if (error != null) throw new RuntimeException(Error.getMinimalErrorString(error));
        error = ensureDirectoryExists(aptConfDir);
        if (error != null) throw new RuntimeException(Error.getMinimalErrorString(error));
        error = ensureDirectoryExists(profileDir);
        if (error != null) throw new RuntimeException(Error.getMinimalErrorString(error));

        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String hookFilePath = prefix + "/etc/apt/apt.conf.d/00termuxy-pathfix";
        String hookScriptPath = prefix + "/bin/termuxy-fix-deb-paths";
        String script = "#!" + prefix + "/bin/bash\n" +
            "# TERMUXY_FIX_DEB_PATHS\n" +
            "set -e\n" +
            "PREFIX=\"" + prefix + "\"\n" +
            "OLD=\"" + DEFAULT_TERMUX_DATA_DIR_PATH + "\"\n" +
            "NEW=\"" + TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + "\"\n" +
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
            "  while IFS= read -r link; do\n" +
            "    target=\"$(readlink \"$link\")\"\n" +
            "    patched=\"${target//$OLD/$NEW}\"\n" +
            "    if [ \"$target\" != \"$patched\" ]; then\n" +
            "      ln -sfn \"$patched\" \"$link\"\n" +
            "    fi\n" +
            "  done < <(find \"$work\" -type l)\n" +
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
            "shopt -s nullglob\n" +
            "for deb in \"$PREFIX\"/tmp/apt-dpkg-install-*/*.deb \"$PREFIX\"/var/cache/apt/archives/*.deb; do\n" +
            "  fix_deb \"$deb\"\n" +
            "done\n";

        File hookScriptFile = new File(binDir, "termuxy-fix-deb-paths");
        try (FileOutputStream outStream = new FileOutputStream(hookScriptFile, false)) {
            outStream.write(script.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection OctalInteger
        Os.chmod(hookScriptFile.getAbsolutePath(), 0700);

        String hook = "DPkg::Pre-Install-Pkgs { \"" + hookScriptPath + " --stdin\"; };\n";
        File hookFile = new File(aptConfDir, "00termuxy-pathfix");
        try (FileOutputStream outStream = new FileOutputStream(hookFile, false)) {
            outStream.write(hook.getBytes(StandardCharsets.UTF_8));
        }

        installCommandAutohealWrapperIfNeeded(prefixDir, "apt", "TERMUXY_APT_WRAPPER");
        installCommandAutohealWrapperIfNeeded(prefixDir, "pkg", "TERMUXY_PKG_WRAPPER");

        String autoHealScript = "#!" + prefix + "/bin/bash\n" +
            "# TERMUXY_AUTOHEAL\n" +
            "set -e\n" +
            "PREFIX=\"" + prefix + "\"\n" +
            "HOOK=\"" + hookFilePath + "\"\n" +
            "FIXER=\"" + hookScriptPath + "\"\n" +
            "mkdir -p \"$PREFIX/etc/apt/apt.conf.d\"\n" +
            "if [ -x \"$FIXER\" ]; then\n" +
            "  printf 'DPkg::Pre-Install-Pkgs { \"%s --stdin\"; };\\n' \"$FIXER\" > \"$HOOK\"\n" +
            "  \"$FIXER\" >/dev/null 2>&1 || true\n" +
            "fi\n";
        File autoHealFile = new File(binDir, "termuxy-autoheal");
        try (FileOutputStream outStream = new FileOutputStream(autoHealFile, false)) {
            outStream.write(autoHealScript.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection OctalInteger
        Os.chmod(autoHealFile.getAbsolutePath(), 0700);

        String profile = "if [ -x \"" + prefix + "/bin/termuxy-autoheal\" ]; then\n" +
            "  \"" + prefix + "/bin/termuxy-autoheal\" >/dev/null 2>&1 || true\n" +
            "fi\n";
        File profileFile = new File(profileDir, "00-termuxy-autoheal.sh");
        try (FileOutputStream outStream = new FileOutputStream(profileFile, false)) {
            outStream.write(profile.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void installCommandAutohealWrapperIfNeeded(File prefixDir, String commandName, String marker) throws Exception {
        File commandFile = new File(prefixDir, "bin/" + commandName);
        File realFile = new File(prefixDir, "bin/" + commandName + ".real");
        if (!commandFile.isFile()) return;

        if (!doesFileContain(commandFile, marker)) {
            if (realFile.isFile() && !realFile.delete())
                throw new RuntimeException("Failed to replace stale " + commandName + ".real");
            if (!commandFile.renameTo(realFile))
                throw new RuntimeException("Failed to move " + commandName + " to " + commandName + ".real");
        }

        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String script = "#!" + prefix + "/bin/bash\n" +
            "# " + marker + "\n" +
            "set -e\n" +
            "PREFIX=\"" + prefix + "\"\n" +
            "[ -x \"$PREFIX/bin/termuxy-autoheal\" ] && \"$PREFIX/bin/termuxy-autoheal\" >/dev/null 2>&1 || true\n" +
            "exec \"$PREFIX/bin/" + commandName + ".real\" \"$@\"\n";

        try (FileOutputStream outStream = new FileOutputStream(commandFile, false)) {
            outStream.write(script.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection OctalInteger
        Os.chmod(commandFile.getAbsolutePath(), 0700);
        //noinspection OctalInteger
        Os.chmod(realFile.getAbsolutePath(), 0700);
    }

    private static String patchBootstrapPath(String value) {
        if (DEFAULT_TERMUX_PACKAGE_NAME.equals(TermuxConstants.TERMUX_PACKAGE_NAME)) return value;
        return value.replace(DEFAULT_TERMUX_DATA_DIR_PATH,
            TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
    }

    private static boolean replaceBytes(byte[] bytes, byte[] search, byte[] replacement) {
        boolean replaced = false;

        for (int i = 0; i <= bytes.length - search.length; i++) {
            boolean matched = true;
            for (int j = 0; j < search.length; j++) {
                if (bytes[i + j] != search[j]) {
                    matched = false;
                    break;
                }
            }

            if (matched) {
                System.arraycopy(replacement, 0, bytes, i, replacement.length);
                replaced = true;
                i += search.length - 1;
            }
        }

        return replaced;
    }

    private static boolean isLikelyTextFile(byte[] bytes) {
        int length = Math.min(bytes.length, 4096);
        for (int i = 0; i < length; i++) {
            if (bytes[i] == 0) return false;
        }
        return true;
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
