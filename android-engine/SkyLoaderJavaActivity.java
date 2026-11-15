package org.fathomstudios.skylauncher.android;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import net.kdt.pojavlaunch.BaseActivity;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * SkyLauncher-owned Java loader install runner, with no Amethyst installer UI.
 * Android's embedded runtime is reused ONLY for Java execution. Forge and
 * NeoForge's own official installers do the client patching.
 */
public final class SkyLoaderJavaActivity extends BaseActivity {
    static final String EXTRA_INSTALLER = "skyloader.installer";
    static final String EXTRA_KIND = "skyloader.kind";
    static final String EXTRA_PROFILE = "skyloader.profile";
    static final String EXTRA_VERSION_ID = "skyloader.version_id";
    static final String EXTRA_MIN_JAVA = "skyloader.java_requirement";
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Scoped flag understood only by SkyLauncher's native JVM lifecycle patch.
        getIntent().putExtra("skylauncher_loader_runner", true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(28, 30, 28, 30);
        layout.setBackgroundColor(0xff101c30);
        status = new TextView(this);
        status.setText("SkyLauncher Java initializer\nPreparing modloader installer...");
        status.setTextSize(19);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        layout.addView(status);
        ProgressBar indicator = new ProgressBar(this);
        layout.addView(indicator);
        setContentView(layout);
        new Thread(this::runInstaller, "SkyLauncher-Loader-JVM").start();
    }

    private void report(String text) {
        runOnUiThread(() -> { if (!isFinishing()) status.setText(text); });
    }

    private void runInstaller() {
        File file = null;
        try {
            SkyEngineProfileBridge.prepareEngine(this);
            Intent input = getIntent();
            String name = input.getStringExtra(EXTRA_INSTALLER);
            String kind = input.getStringExtra(EXTRA_KIND);
            String id = input.getStringExtra(EXTRA_PROFILE);
            String versionId = input.getStringExtra(EXTRA_VERSION_ID);
            int java = input.getIntExtra(EXTRA_MIN_JAVA, 21);
            if (name == null || id == null || versionId == null || kind == null
                    || !(kind.equals("Forge") || kind.equals("NeoForge")))
                throw new IllegalStateException("Incomplete SkyLauncher loader install request.");
            if (!ProfileStore.active(this).id.equals(id))
                throw new IllegalStateException("The selected profile changed.");
            file = new File(name).getCanonicalFile();
            File cacheRoot = getCacheDir().getCanonicalFile();
            if (!file.toPath().startsWith(cacheRoot.toPath())
                    || !file.isFile() || !file.getName().endsWith(".jar"))
                throw new IllegalStateException("Untrusted modloader installer location.");

            report("Preparing Java " + java + " for " + kind + "...");
            String jreName = MultiRTUtils.getNearestJreName(java);
            Runtime runtime = jreName == null ? null : MultiRTUtils.forceReread(jreName);
            if (runtime == null || runtime.javaVersion < java) {
                report("Installing required Java " + java + " runtime...");
                NewJREUtil.tryDownloadRuntime(this, java);
                jreName = MultiRTUtils.getNearestJreName(java);
                runtime = jreName == null ? null : MultiRTUtils.forceReread(jreName);
            }
            if (runtime == null || runtime.javaVersion < java)
                throw new IllegalStateException("Java " + java + " could not be installed.");
            JREUtils.redirectAndPrintJRELog();

            List<String> args = new ArrayList<>();
            Tools.getCacioJavaArgs(args, runtime.javaVersion == 8, this);
            if (kind.equals("Forge")) {
                File agent = new File(Tools.DIR_DATA, "forge_installer/forge_installer.jar");
                if (!agent.isFile())
                    throw new IllegalStateException("Forge profile-install agent is missing.");
                args.add("-javaagent:" + agent.getAbsolutePath() + "=\"" + versionId + "\"");
            }
            args.add("-jar");
            args.add(file.getAbsolutePath());
            args.add("--installClient");

            // Native VM needs its Android-specific filesystem and libraries,
            // but the loader initialization logic and user interface belong
            // exclusively to SkyLauncher.
            int savedRam = LauncherPreferences.PREF_RAM_ALLOCATION;
            try {
                LauncherPreferences.PREF_RAM_ALLOCATION = Math.min(savedRam, 1536);
                report("Running official " + kind + " installer with SkyLauncher...");
                getSharedPreferences("skylauncher_installer_exit", MODE_PRIVATE)
                        .edit().remove("last_exit_code").commit();
                JREUtils.launchJavaVM(this, runtime, null, args, "");
            } finally {
                LauncherPreferences.PREF_RAM_ALLOCATION = savedRam;
            }

            int exit = getSharedPreferences("skylauncher_installer_exit", MODE_PRIVATE)
                    .getInt("last_exit_code", Integer.MIN_VALUE);
            if (exit != 0)
                throw new IllegalStateException("Java installer exited with code " + exit);
            if (!ProfileStore.active(this).id.equals(id))
                throw new IllegalStateException("Profile changed during installation.");
            setResult(RESULT_OK);
            report("Official " + kind + " installer completed.");
        } catch (Throwable failure) {
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            LogBook.write(this, "SkyLoader Java installer failed: " + message);
            Intent detail = new Intent();
            detail.putExtra("skyloader.error", message);
            setResult(RESULT_CANCELED, detail);
        } finally {
            if (file != null && file.getName().startsWith("skyloader-")) file.delete();
            runOnUiThread(this::finish);
        }
    }
}
