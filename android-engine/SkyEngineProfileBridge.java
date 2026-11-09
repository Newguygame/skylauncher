package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import net.kdt.pojavlaunch.TestStorageActivity;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

/**
 * The Android SkyLauncher profile is the source of truth.
 * Changes are applied to the actual embedded engine profile, not just to UI metadata.
 * All I/O takes place inside this app's own Android storage.
 */
public final class SkyEngineProfileBridge {
    public static final String EXTRA_DIRECT = "org.fathomstudios.skylauncher.android.DIRECT_PLAY";
    public static final String EXTRA_PROFILE_ID = "org.fathomstudios.skylauncher.android.PROFILE_ID";
    public static final String EXTRA_PROFILE_NAME = "org.fathomstudios.skylauncher.android.PROFILE_NAME";

    private SkyEngineProfileBridge() { }

    /** The upstream Android engine's preferred SharedPreferences and storage setup. */
    public static void prepareEngine(Context ctx) {
        if (LauncherPreferences.DEFAULT_PREF == null) {
            LauncherPreferences.DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(ctx);
        }
        Tools.initStorageConstants(ctx);
        LauncherPreferences.loadPreferences(ctx);
    }

    /** Copy/version active profile into the native Minecraft engine and apply RAM allocation. */
    public static void synchronize(Context ctx, ProfileStore.Profile profile) throws Exception {
        prepareEngine(ctx);
        if (profile.version == null || !profile.version.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Ongeldige Minecraft-versie in SkyLauncher-profiel.");
        }
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson == null ||
                LauncherProfiles.mainProfileJson.profiles == null) {
            throw new IllegalStateException("Minecraft-profielopslag kon niet worden geopend.");
        }

        String loader = ProfileStore.LOADERS[profile.loader].toLowerCase(Locale.ROOT);
        String actualVersion = resolveGameVersion(profile.version, loader);
        Map<String, MinecraftProfile> map = LauncherProfiles.mainProfileJson.profiles;
        MinecraftProfile target = map.get(profile.id);
        if (target == null) {
            target = MinecraftProfile.createTemplate();
            target.gameDir = null; // use default game root unless explicitly configured by engine
        }
        target.name = profile.name;
        target.lastVersionId = actualVersion;
        map.put(profile.id, target);
        LauncherProfiles.write();

        // Amethyst has a global RAM allocation preference; sync on every Play so
        // switching SkyLauncher profiles updates the JVM value.
        long deviceTotalMb = getDeviceTotalRamMb(ctx);
        int requested = Math.max(512, Math.min(32768, profile.ramMb));
        int maxSuggested = (int) Math.min(32768, Math.max(512, deviceTotalMb * 3 / 4));
        int actualRam = Math.min(requested, maxSuggested);
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        boolean saved = prefs.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profile.id)
                .putInt("allocation", actualRam)
                .commit();
        if (!saved) throw new IllegalStateException("Minecraft-instellingen konden niet worden opgeslagen.");
        LauncherPreferences.PREF_RAM_ALLOCATION = actualRam;
        LogBook.write(ctx, "Engineprofiel ingesteld: " + profile.name
                + ", versie " + actualVersion + ", RAM " + actualRam + " MB");
    }

    private static long getDeviceTotalRamMb(Context ctx) {
        android.app.ActivityManager am =
                (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 8192;
        android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return Math.max(512, info.totalMem / (1024 * 1024));
    }

    private static String resolveGameVersion(String mc, String loader) throws Exception {
        if ("vanilla".equals(loader)) return mc;

        // For modded profiles, use an installed loader version belonging to this
        // Minecraft version; never silently fall back to vanilla.
        File versions = new File(Tools.DIR_HOME_VERSION);
        File[] folders = versions.listFiles(File::isDirectory);
        if (folders == null) throw new MissingModloaderException(mc, loader);
        String best = null;
        long bestTime = -1;
        for (File folder : folders) {
            String directoryName = folder.getName();
            File descriptor = new File(folder, directoryName + ".json");
            if (!descriptor.isFile()) continue;
            if (descriptor.length() > 2_000_000) continue;
            String id;
            String base;
            try {
                JSONObject data = new JSONObject(
                        new String(Files.readAllBytes(descriptor.toPath()), StandardCharsets.UTF_8));
                id = data.optString("id", directoryName);
                base = data.optString("inheritsFrom", id);
            } catch (Exception ignored) {
                continue;
            }
            if (!id.toLowerCase(Locale.ROOT).contains(loader)) continue;
            if (!exactVersionMatch(id, mc) && !exactVersionMatch(base, mc)) continue;
            if (descriptor.lastModified() > bestTime) {
                bestTime = descriptor.lastModified();
                best = id;
            }
        }
        if (best == null) throw new MissingModloaderException(mc, loader);
        return best;
    }

    private static boolean exactVersionMatch(String str, String mc) {
        String value = str.toLowerCase(Locale.ROOT);
        String v = mc.toLowerCase(Locale.ROOT);
        if (value.equals(v)) return true;
        int start = value.indexOf(v);
        while (start >= 0) {
            boolean before = start == 0 || !Character.isLetterOrDigit(value.charAt(start - 1));
            int end = start + v.length();
            boolean after = end == value.length() || !Character.isLetterOrDigit(value.charAt(end));
            if (before && after) return true;
            start = value.indexOf(v, start + 1);
        }
        return false;
    }

    public static void launch(Activity activity) {
        ProfileStore.Profile profile = ProfileStore.active(activity);
        try {
            synchronize(activity, profile);
            Intent intent = new Intent(activity, TestStorageActivity.class);
            intent.putExtra(EXTRA_DIRECT, true);
            intent.putExtra(EXTRA_PROFILE_ID, profile.id);
            intent.putExtra(EXTRA_PROFILE_NAME, profile.name);
            activity.startActivity(intent);
        } catch (MissingModloaderException e) {
            new AlertDialog.Builder(activity)
                    .setTitle("Modloader ontbreekt")
                    .setMessage("Voor profiel '" + profile.name + "' is "
                            + ProfileStore.LOADERS[profile.loader]
                            + " voor Minecraft " + profile.version
                            + " nog niet geïnstalleerd in de geïntegreerde engine. "
                            + "De game start niet als vanilla. Je kunt de modloader "
                            + "eenmalig installeren via de engine-instellingen.")
                    .setPositiveButton("Modloader installeren", (dialog, which) ->
                            activity.startActivity(new Intent(activity, TestStorageActivity.class)))
                    .setNegativeButton("Annuleren", null)
                    .show();
        } catch (Exception e) {
            LogBook.write(activity, "Engineprofiel fout: " + e.getClass().getSimpleName());
            new AlertDialog.Builder(activity)
                    .setTitle("Minecraft kan niet starten")
                    .setMessage(e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private static final class MissingModloaderException extends Exception {
        MissingModloaderException(String mc, String loader) {
            super("Geen geïnstalleerde " + loader + "-versie gevonden voor Minecraft " + mc);
        }
    }
}
