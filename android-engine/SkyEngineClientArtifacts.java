package org.fathomstudios.skylauncher.android;

import net.kdt.pojavlaunch.Tools;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;

/**
 * Validates the generated Minecraft code produced by NeoForge's installer.
 * A version JSON and neoforge-universal.jar alone are NOT a working installation:
 * FML's production client provider needs the mapped client-srg JAR, while the
 * separate NeoForge client JAR holds patches. Never touch worlds or profile data.
 */
public final class SkyEngineClientArtifacts {
    private SkyEngineClientArtifacts() {}

    public static File requireNeoForgeClient(String minecraftVersion, String installedId)
            throws Exception {
        if (installedId == null || !installedId.startsWith("neoforge-"))
            throw new IllegalArgumentException("Not a NeoForge version: " + installedId);
        String neoForgeVersion = installedId.substring("neoforge-".length());
        if (!neoForgeVersion.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("Invalid NeoForge version.");

        File versionJson = new File(Tools.DIR_HOME_VERSION,
                installedId + "/" + installedId + ".json");
        if (!versionJson.isFile())
            throw new IllegalStateException("NeoForge version descriptor is missing.");
        JSONObject version = new JSONObject(
                new String(Files.readAllBytes(versionJson.toPath()), StandardCharsets.UTF_8));
        JSONArray gameArgs = version.optJSONObject("arguments") == null
                ? null : version.optJSONObject("arguments").optJSONArray("game");
        String neoFormVersion = null;
        if (gameArgs != null) {
            for (int i = 0; i + 1 < gameArgs.length(); i++) {
                if ("--fml.neoFormVersion".equals(gameArgs.optString(i))) {
                    neoFormVersion = gameArgs.optString(i + 1);
                    break;
                }
            }
        }
        if (neoFormVersion == null ||
                !neoFormVersion.matches("[0-9]{8}\\.[0-9]+")) {
            throw new IllegalStateException("NeoForge " + neoForgeVersion
                    + " version metadata lacks a valid NeoForm version. "
                    + "The client installer did not complete correctly.");
        }
        String neoFormCoordinate = minecraftVersion + "-" + neoFormVersion;
        File gameLibraries = new File(Tools.DIR_HOME_LIBRARY);
        File clientFolder = new File(gameLibraries,
                "net/minecraft/client/" + neoFormCoordinate);
        File mappedClient = new File(clientFolder,
                "client-" + neoFormCoordinate + "-srg.jar");
        File extraClient = new File(clientFolder,
                "client-" + neoFormCoordinate + "-extra.jar");
        File loaderFolder = new File(gameLibraries,
                "net/neoforged/neoforge/" + neoForgeVersion);
        File patchedClient = new File(loaderFolder,
                "neoforge-" + neoForgeVersion + "-client.jar");
        File neoForgeUniversal = new File(loaderFolder,
                "neoforge-" + neoForgeVersion + "-universal.jar");

        checkJar(neoForgeUniversal, "NeoForge universal", null);
        checkJar(patchedClient, "NeoForge patched client", null);
        checkJar(extraClient, "Minecraft client resources", null);
        checkJar(mappedClient, "Minecraft mapped (SRG) client",
                "net/minecraft/client/Minecraft.class");
        return mappedClient;
    }

    private static void checkJar(File jar, String label, String requiredClass) throws Exception {
        if (!jar.isFile() || jar.length() < 4096) {
            throw new IllegalStateException(label + " is missing or incomplete: "
                    + jar.getName() + ". Reinstall the selected NeoForge version "
                    + "and allow all installer processors to finish.");
        }
        try (ZipFile archive = new ZipFile(jar)) {
            if (archive.size() == 0 ||
                    (requiredClass != null && archive.getEntry(requiredClass) == null)) {
                throw new IllegalStateException(label + " is not a valid Minecraft JAR: "
                        + jar.getName() + ". Reinstall NeoForge.");
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalStateException(label + " is corrupted: " + jar.getName(), e);
        }
    }
}
