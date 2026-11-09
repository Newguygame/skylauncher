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
        ensureIsolatedEngineProfiles(ctx);

        String loader = ProfileStore.LOADERS[profile.loader].toLowerCase(Locale.ROOT);
        String actualVersion = resolveGameVersion(profile.version, loader);
        Map<String, MinecraftProfile> map = LauncherProfiles.mainProfileJson.profiles;
        MinecraftProfile target = map.get(profile.id);
        if (target == null) throw new IllegalStateException("Selected profile is missing from the Minecraft engine.");
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

    /**
     * Every SkyLauncher profile has its own game directory, including saves,
     * mods, configs, screenshots, resourcepacks and shaderpacks. Engine assets,
     * installed version descriptors and Java runtimes remain shared read-only
     * infrastructure in the engine's .minecraft folder.
     *
     * Existing unique profile directories (including a legacy default game
     * directory for the first SkyLauncher profile) are preserved in place:
     * migrating must never discard a user's existing worlds. Previously shared
     * or overlapping directories are assigned to the later profile's dedicated
     * folder, without deleting data from the old directory.
     */
    private static synchronized void ensureIsolatedEngineProfiles(Context ctx) throws Exception {
        prepareEngine(ctx);
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson == null ||
                LauncherProfiles.mainProfileJson.profiles == null) {
            throw new IllegalStateException("Minecraft engine profiles are unavailable.");
        }
        Map<String, MinecraftProfile> map = LauncherProfiles.mainProfileJson.profiles;
        java.util.List<ProfileStore.Profile> skyProfiles = ProfileStore.all(ctx);
        java.util.List<File> assigned = new java.util.ArrayList<>();
        File storageRoot = new File(Tools.DIR_GAME_HOME).getCanonicalFile();
        boolean changed = false;
        for (int index = 0; index < skyProfiles.size(); index++) {
            ProfileStore.Profile selected = skyProfiles.get(index);
            if (selected.id == null || !selected.id.matches("[A-Za-z0-9_-]{1,72}")) {
                throw new IllegalStateException("A SkyLauncher profile has an invalid ID.");
            }
            MinecraftProfile nativeProfile = map.get(selected.id);
            if (nativeProfile == null) {
                nativeProfile = MinecraftProfile.createTemplate();
                // On the FIRST migration only, preserve the already-selected
                // engine world folder. All later profiles start with empty game
                // folders so that they never inherit other profiles' worlds.
                if (index == 0) {
                    String oldSelected = LauncherPreferences.DEFAULT_PREF.getString(
                            LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                    MinecraftProfile old = map.get(oldSelected);
                    if (old != null && !selected.id.equals(oldSelected)) {
                        nativeProfile.gameDir = old.gameDir;
                    } else {
                        nativeProfile.gameDir = profileRelativeDirectory(selected);
                    }
                } else {
                    nativeProfile.gameDir = profileRelativeDirectory(selected);
                }
                changed = true;
            }

            File folder = Tools.getGameDirPath(nativeProfile).getCanonicalFile();
            boolean safe = folder.toPath().startsWith(storageRoot.toPath())
                    && !folder.equals(storageRoot);
            for (File previous : assigned) {
                if (folder.equals(previous) ||
                        folder.toPath().startsWith(previous.toPath()) ||
                        previous.toPath().startsWith(folder.toPath())) {
                    safe = false;
                }
            }

            if (!safe) {
                String newDir = profileRelativeDirectory(selected);
                File dedicated = new File(storageRoot, newDir).getCanonicalFile();
                if (!dedicated.toPath().startsWith(storageRoot.toPath()))
                    throw new IllegalStateException("Unsafe profile storage path.");
                for (File previous : assigned) {
                    if (dedicated.equals(previous) ||
                            dedicated.toPath().startsWith(previous.toPath()) ||
                            previous.toPath().startsWith(dedicated.toPath())) {
                        throw new IllegalStateException("Profile storage paths overlap.");
                    }
                }
                nativeProfile.gameDir = newDir;
                folder = dedicated;
                changed = true;
                LogBook.write(ctx, "Separated previously shared Minecraft storage for "
                        + selected.name + ". Old files remain untouched in their prior directory.");
            }
            assigned.add(folder);
            map.put(selected.id, nativeProfile);
        }
        if (changed) LauncherProfiles.write();
    }

    private static String profileRelativeDirectory(ProfileStore.Profile p) {
        return "skylauncher-profiles/" + p.id;
    }

    /** The sole writable Minecraft game folder for a selected SkyLauncher profile. */
    public static synchronized File gameDirectory(Context ctx,
                                                  ProfileStore.Profile selected) throws Exception {
        ensureIsolatedEngineProfiles(ctx);
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(selected.id);
        if (profile == null) throw new IllegalStateException("Profile mapping was not created.");
        File root = Tools.getGameDirPath(profile).getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs())
            throw new IllegalStateException("Cannot create the private Minecraft profile folder.");
        return root;
    }

    public static void prepareForModManagement(Context ctx, ProfileStore.Profile profile) throws Exception {
        gameDirectory(ctx, profile);
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profile.id)
                .apply();
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
        if ("neoforge".equals(loader) && best.startsWith("neoforge-")) {
            String loaderVersion = best.substring("neoforge-".length());
            File patchedClient = new File(Tools.DIR_HOME_LIBRARY,
                    "net/neoforged/neoforge/" + loaderVersion + "/neoforge-"
                            + loaderVersion + "-client.jar");
            if (!patchedClient.isFile() || patchedClient.length() < 4096) {
                throw new MissingModloaderException(mc, loader,
                        "NeoForge " + loaderVersion
                        + " has a version descriptor, but its patched Minecraft client JAR is missing. "
                        + "SkyLauncher must finish the NeoForge installer before Play.");
            }
        }
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
            Intent install = new Intent(activity, SkyEngineLoaderInstaller.class);
            install.putExtra(SkyEngineLoaderInstaller.EXTRA_PROFILE_ID, profile.id);
            activity.startActivity(install);
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
            super("No installed " + loader + " version found for Minecraft " + mc);
        }
        MissingModloaderException(String mc, String loader, String reason) {
            super(reason);
        }
    }
}
