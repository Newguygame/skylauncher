package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgeUtils;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader;
import net.kdt.pojavlaunch.fragments.NeoForgeInstallFragment;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic installation of the modloader selected in SkyLauncher's profile.
 *
 * Uses the integrated native engine's existing loader metadata, download
 * and installer classes. Forge/NeoForge need the built-in Java installer
 * activity; Fabric/Quilt install their version descriptors directly.
 * Never starts an external launcher and never silently falls back to vanilla.
 */
public final class SkyEngineLoaderInstaller extends Activity {
    public static final String EXTRA_PROFILE_ID = "skylauncher.loader.profile_id";
    private static final int INSTALL_REQUEST = 1407;

    private ProfileStore.Profile profile;
    private TextView status;
    private ProgressBar progress;
    private Button cancel;
    private final AtomicBoolean preparing = new AtomicBoolean(false);
    private volatile boolean cancelled;
    private volatile boolean completed;
    private boolean waitingForGuiInstaller;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        profile = ProfileStore.active(this);
        String requestedId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if (requestedId == null || !requestedId.equals(profile.id)) {
            new AlertDialog.Builder(this)
                    .setTitle("Minecraft profile changed")
                    .setMessage("Select the intended profile in SkyLauncher and press Play again.")
                    .setPositiveButton("OK", (d, w) -> finish()).show();
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(28), dp(22), dp(20));
        panel.setBackgroundColor(0xff101c30);
        TextView title = new TextView(this);
        title.setText("SkyLauncher — Automatic modloader installation");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        panel.addView(title);
        status = new TextView(this);
        status.setTextColor(0xffcbddec);
        status.setTextSize(15);
        status.setPadding(0, dp(18), 0, dp(18));
        panel.addView(status);
        progress = new ProgressBar(this);
        panel.addView(progress);
        cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText("Cancel installation");
        cancel.setOnClickListener(v -> { cancelled = true; finish(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(24);
        panel.addView(cancel, params);
        setContentView(panel);
        install();
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private String loaderName() {
        return ProfileStore.LOADERS[profile.loader];
    }

    private void report(String text) {
        runOnUiThread(() -> {
            if (isFinishing() || cancelled) return;
            if (status != null) status.setText(text);
            LogBook.write(this, text);
        });
    }

    private void fail(String why) {
        preparing.set(false);
        runOnUiThread(() -> {
            if (isFinishing() || cancelled || completed) return;
            progress.setVisibility(View.GONE);
            if (status != null) status.setText(why);
            new AlertDialog.Builder(this)
                    .setTitle("Could not install " + loaderName())
                    .setMessage(why + "\n\nThe game will not start with the wrong modloader.")
                    .setPositiveButton("Try again", (d, w) -> {
                        progress.setVisibility(View.VISIBLE);
                        install();
                    })
                    .setNegativeButton("Back", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
        });
    }

    private void install() {
        if (cancelled || !preparing.compareAndSet(false, true)) return;
        report("Checking official " + loaderName() + " versions for Minecraft "
                + profile.version + "...");
        new Thread(() -> {
            try {
                SkyEngineProfileBridge.prepareEngine(this);
                final ModLoader loader = chooseCompatibleLoader();
                if (loader == null) throw new IllegalStateException(
                        "This profile does not need a separate modloader installation.");
                if (cancelled) return;
                report("Downloading " + loaderName() + " " + loader.modLoaderVersion
                        + " from the official repository...");
                Runnable task = loader.getDownloadTask(new ModloaderDownloadListener() {
                    @Override public void onDownloadFinished(File installerJar) {
                        if (cancelled) return;
                        if (!loader.requiresGuiInstallation()) {
                            // The upstream Fabric/Quilt downloader has already written
                            // the version profile JSON to versions/.
                            preparing.set(false);
                            runOnUiThread(() -> verifyAndLaunch());
                            return;
                        }
                        if (installerJar == null || !installerJar.isFile()) {
                            fail("The installer file is missing after download.");
                            return;
                        }
                        runOnUiThread(() -> {
                            if (cancelled || isFinishing()) return;
                            try {
                                Intent intent = loader.getInstallationIntent(
                                        SkyEngineLoaderInstaller.this, installerJar);
                                if (intent == null) {
                                    fail("The built-in engine does not support this installer.");
                                    return;
                                }
                                report("Installing " + loaderName() + " in the built-in Java engine...");
                                intent.putExtra("skylauncher_auto_finish", true);
                                intent.putExtra("skylauncher_java_requirement",
                                        minimumJavaVersion(profile.version));
                                waitingForGuiInstaller = true;
                                preparing.set(false);
                                // This is an INTERNAL engine Activity, not Amethyst's
                                // menu or another app's launcher.
                                startActivityForResult(intent, INSTALL_REQUEST);
                            } catch (Exception e) {
                                fail("Unable to open installer: " + e.getMessage());
                            }
                        });
                    }
                    @Override public void onDataNotAvailable() {
                        fail("No compatible " + loaderName()
                                + " installer is available for Minecraft " + profile.version + ".");
                    }
                    @Override public void onDownloadError(Exception error) {
                        fail("Download failed: " + error.getMessage());
                    }
                });
                if (task == null) throw new IllegalStateException(
                        "No native installer was returned by the built-in engine.");
                task.run();
            } catch (Exception e) {
                fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }, "SkyLauncher-Modloader-Setup").start();
    }

    // NeoForge and Forge installers can be compiled for an older Java
    // version than the Minecraft version they install.
    private static int minimumJavaVersion(String version) {
        String[] pieces = version.split("\\.");
        try {
            if (pieces.length < 2 || !"1".equals(pieces[0])) return 21;
            int minor = Integer.parseInt(pieces[1]);
            int patch = pieces.length > 2 ? Integer.parseInt(pieces[2]) : 0;
            if (minor >= 21 || (minor == 20 && patch >= 5)) return 21;
            if (minor >= 18) return 17;
            if (minor == 17) return 17;
            return 8;
        } catch (NumberFormatException e) {
            return 21;
        }
    }

    private ModLoader chooseCompatibleLoader() throws Exception {
        final String mc = profile.version;
        final String kind = loaderName();
        switch (kind) {
            case "Forge": {
                List<String> all = ForgeUtils.downloadForgeVersions();
                String prefix = mc + "-";
                String chosen = profile.loaderVersion != null
                        && !profile.loaderVersion.isEmpty()
                        ? prefix + profile.loaderVersion : latestVersionWithPrefix(all, prefix);
                if (chosen == null || all == null || !all.contains(chosen))
                    throw new IllegalStateException("Required Forge version is unavailable: "
                            + (chosen == null ? mc : chosen));
                return new ModLoader(ModLoader.MOD_LOADER_FORGE,
                        chosen.substring(prefix.length()), mc);
            }
            case "NeoForge": {
                // NeoForge 1.21.1 uses 21.1.x; 1.21.4 uses 21.4.x.
                // Older legacy NeoForge releases have a different version scheme
                // and are not guessed: an incompatible version must never install.
                if (!mc.matches("1\\.(20|21|22|23|24|25|26)\\.[0-9]+")) {
                    throw new IllegalStateException("Cannot safely determine the NeoForge "
                            + "version family for Minecraft " + mc + ".");
                }
                String prefix = mc.substring(2) + ".";
                List<String> all = NeoForgeInstallFragment.downloadNeoForgeVersions();
                String chosen = profile.loaderVersion != null
                        && !profile.loaderVersion.isEmpty()
                        ? profile.loaderVersion : latestVersionWithPrefix(all, prefix);
                if (chosen == null || !chosen.startsWith(prefix)
                        || all == null || !all.contains(chosen))
                    throw new IllegalStateException("Required NeoForge version is unavailable: "
                            + (chosen == null ? mc : chosen));
                return new ModLoader(ModLoader.MOD_LOADER_NEOFORGE, chosen, mc);
            }
            case "Fabric":
            case "Quilt": {
                FabriclikeUtils utils = kind.equals("Fabric")
                        ? FabriclikeUtils.FABRIC_UTILS : FabriclikeUtils.QUILT_UTILS;
                FabricVersion[] choices = utils.downloadLoaderVersions(mc);
                FabricVersion chosen = null;
                if (choices != null) for (FabricVersion candidate : choices) {
                    if (candidate == null || candidate.version == null
                            || candidate.version.isEmpty()) continue;
                    if (profile.loaderVersion != null && !profile.loaderVersion.isEmpty()) {
                        if (profile.loaderVersion.equals(candidate.version)) {
                            chosen = candidate;
                            break;
                        }
                        continue;
                    }
                    if (!candidate.stable) continue;
                    if (chosen == null || compareVersions(candidate.version, chosen.version) > 0) {
                        chosen = candidate;
                    }
                }
                if (chosen == null) throw new IllegalStateException(
                        "No stable " + kind + " version supports Minecraft " + mc + ".");
                return new ModLoader(kind.equals("Fabric") ? ModLoader.MOD_LOADER_FABRIC
                        : ModLoader.MOD_LOADER_QUILT, chosen.version, mc);
            }
            default:
                return null;
        }
    }

    private static String latestVersionWithPrefix(List<String> all, String prefix) {
        String latest = null;
        if (all == null) return null;
        for (String value : all) {
            if (value == null || !value.startsWith(prefix) || value.length() <= prefix.length()) {
                continue;
            }
            // Prefer stable versions. Do not silently select a pre-release.
            String suffix = value.substring(prefix.length()).toLowerCase(java.util.Locale.ROOT);
            if (suffix.contains("beta") || suffix.contains("alpha")
                    || suffix.contains("pre") || suffix.contains("snapshot")) continue;
            if (latest == null || compareVersions(value.substring(prefix.length()),
                    latest.substring(prefix.length())) > 0) latest = value;
        }
        return latest;
    }

    private static int compareVersions(String a, String b) {
        String[] left = a.split("[^0-9]+");
        String[] right = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long x = i < left.length && !left[i].isEmpty() ? parseNumber(left[i]) : 0L;
            long y = i < right.length && !right[i].isEmpty() ? parseNumber(right[i]) : 0L;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    private static long parseNumber(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) { return 0L; }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != INSTALL_REQUEST || !waitingForGuiInstaller) return;
        waitingForGuiInstaller = false;
        report("Checking the installed modloader...");
        // The installer can be closed without completing; verify the resulting
        // version descriptor before allowing Direct Play.
        verifyAndLaunch();
    }

    private void verifyAndLaunch() {
        if (cancelled || completed || isFinishing()) return;
        report("Verifying " + loaderName() + " installation...");
        new Thread(() -> {
            try {
                if (!profile.id.equals(ProfileStore.active(this).id)) {
                    throw new IllegalStateException("The active SkyLauncher profile changed.");
                }
                SkyEngineProfileBridge.synchronize(this, profile);
                runOnUiThread(() -> {
                    if (cancelled || isFinishing() || completed) return;
                    completed = true;
                    progress.setVisibility(View.GONE);
                    LogBook.write(this, "Modloader installed; starting " + profile.name);
                    // The existing bridge sets the engine's selected profile,
                    // memory and the DIRECT_PLAY extra. No external launcher.
                    SkyEngineProfileBridge.launch(this);
                    finish();
                });
            } catch (Exception e) {
                fail("Installation did not produce a compatible Minecraft profile: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }, "SkyLauncher-Verify-Modloader").start();
    }
}
