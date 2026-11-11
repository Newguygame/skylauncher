package org.fathomstudios.skylauncher.android;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates a concrete, fully checked Minecraft Java launch plan using
 * SkyLauncher's own per-profile files and official Mojang version metadata.
 *
 * This does NOT start Minecraft until a genuine Android OpenJDK 21 + LWJGL
 * backend and Minecraft Microsoft authentication are available.
 */
final class SkyLaunchPlan {
    static final class Plan {
        final String versionId;
        final String mainClass;
        final String classpath;
        final File gameDirectory;
        final File assetRoot;
        final String assetsIndex;
        final List<String> gameArgs;
        final List<String> blockers;
        final int libraries;
        Plan(String versionId, String mainClass, String classpath,
             File gameDir, File assetRoot, String assetsIndex,
             List<String> gameArgs, List<String> blockers, int libraries) {
            this.versionId = versionId;
            this.mainClass = mainClass;
            this.classpath = classpath;
            this.gameDirectory = gameDir;
            this.assetRoot = assetRoot;
            this.assetsIndex = assetsIndex;
            this.gameArgs = Collections.unmodifiableList(gameArgs);
            this.blockers = Collections.unmodifiableList(blockers);
            this.libraries = libraries;
        }
        String report() {
            StringBuilder text = new StringBuilder();
            text.append("Minecraft: ").append(versionId)
                .append("\nMain class: ").append(mainClass)
                .append("\nLibraries in classpath: ").append(libraries)
                .append("\nGame directory: ").append(gameDirectory.getAbsolutePath())
                .append("\nLaunch arguments prepared: ").append(gameArgs.size())
                .append("\n\n");
            if (blockers.isEmpty()) {
                text.append("Game files and metadata pass the SkyLauncher preflight.");
            } else {
                text.append("Launch blocked by ").append(blockers.size()).append(" issue(s):");
                for (String issue : blockers) text.append("\n• ").append(issue);
            }
            text.append("\n\nSkyLauncher does not execute Minecraft until "
                    + "Android Java 21, LWJGL rendering and account authentication "
                    + "are verified. No fake Minecraft launch is attempted.");
            return text.toString();
        }
    }

    private SkyLaunchPlan() { }

    static Plan preflight(Context context, ProfileStore.Profile profile) throws Exception {
        File root = new File(context.getFilesDir(), "minecraft").getCanonicalFile();
        File gameDir = ProfileStore.root(context, profile);
        File assetsRoot = new File(root, "assets");
        File versionsRoot = new File(root, "versions");
        File librariesRoot = new File(root, "libraries");
        List<String> missing = new ArrayList<>();
        if (!new File(gameDir, "skylauncher-install.json").isFile()) {
            missing.add("Install the official Minecraft game files first.");
        }

        File baseDescriptor = secureFile(versionsRoot, profile.version + "/" + profile.version + ".json");
        JSONObject base = parseJson(baseDescriptor, missing, "Minecraft version metadata");
        String requested = profile.version;
        JSONObject selected = base;
        if (!"Vanilla".equals(profile.loader)) {
            File loaderMeta = new File(gameDir, "skylauncher-loader.json");
            JSONObject loaderRecord = parseJson(loaderMeta, missing, "Modloader state");
            String versionId = loaderRecord.optString("versionId", "");
            if (!versionId.matches("[A-Za-z0-9._+-]{1,140}")) {
                missing.add("Select and initialize the modloader first.");
            } else {
                requested = versionId;
                File desc = secureFile(versionsRoot, versionId + "/" + versionId + ".json");
                selected = parseJson(desc, missing, "Modloader version descriptor");
            }
            if (loaderRecord.optBoolean("needsNativeJvm", false)) {
                missing.add("Forge / NeoForge installer must run using independent Java 21.");
            }
        }

        String baseId = base.optString("id");
        if (!profile.version.equals(baseId))
            missing.add("Minecraft metadata version ID does not match the profile.");
        if (!profile.version.equals(selected.optString("inheritsFrom", profile.version))
                && selected != base) {
            missing.add("Loader metadata inherits a different Minecraft version.");
        }

        String mainClass = selected.optString("mainClass", base.optString("mainClass"));
        if (!mainClass.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
            missing.add("Minecraft main class is missing or invalid.");
            mainClass = "(unknown)";
        }
        String assetId = base.optString("assets",
                base.optJSONObject("assetIndex") != null
                        ? base.optJSONObject("assetIndex").optString("id") : "");
        if (!assetId.matches("[A-Za-z0-9._-]{1,100}")) {
            missing.add("Minecraft assets index name is invalid.");
            assetId = profile.version;
        }
        File indexFile = secureFile(new File(assetsRoot, "indexes"), assetId + ".json");
        if (!indexFile.isFile()) missing.add("Minecraft assets index has not been installed.");

        Set<String> classpath = new LinkedHashSet<>();
        int libraryCount = 0;
        libraryCount += appendLibraries(base.optJSONArray("libraries"), librariesRoot,
                classpath, missing);
        if (selected != base) libraryCount += appendLibraries(
                selected.optJSONArray("libraries"), librariesRoot, classpath, missing);
        File jar = secureFile(versionsRoot, profile.version + "/" + profile.version + ".jar");
        if (jar.isFile()) classpath.add(jar.getAbsolutePath());
        else missing.add("Minecraft client JAR is missing: " + profile.version);

        List<String> args = new ArrayList<>();
        JSONArray gameArguments = base.optJSONObject("arguments") == null ? null
                : base.optJSONObject("arguments").optJSONArray("game");
        if (gameArguments == null) {
            missing.add("Minecraft game arguments metadata unavailable.");
        } else {
            // Count executable arguments, but never put access tokens into a log.
            for (int i = 0; i < gameArguments.length(); i++) {
                Object item = gameArguments.opt(i);
                if (item instanceof String) {
                    args.add((String) item);
                } else if (item instanceof JSONObject) {
                    JSONObject conditional = (JSONObject) item;
                    if (!allowRules(conditional.optJSONArray("rules"))) continue;
                    Object value = conditional.opt("value");
                    if (value instanceof String) args.add((String)value);
                    if (value instanceof JSONArray) {
                        JSONArray list = (JSONArray) value;
                        for (int j = 0; j < list.length(); j++) {
                            String part = list.optString(j, "");
                            if (!part.isEmpty()) args.add(part);
                        }
                    }
                }
            }
        }

        if (Build.SUPPORTED_ABIS.length == 0 || !Build.SUPPORTED_ABIS[0].startsWith("arm64")) {
            missing.add("This development runtime currently targets Android ARM64 only.");
        }
        File javaHome = new File(context.getFilesDir(), "minecraft/runtimes/java21");
        if (!new File(javaHome, "lib/server/libjvm.so").isFile()
                || !new File(javaHome, "lib/modules").isFile())
            missing.add("Android ARM64 OpenJDK 21 runtime has not been installed.");
        File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir);
        if (!new File(nativeLib, "liblwjgl.so").isFile()
                || !new File(nativeLib, "libglfw.so").isFile())
            missing.add("Minecraft-compatible Android LWJGL/GLFW libraries are not packaged.");
        if (!new File(nativeLib, "libOSMesa.so").isFile()
                && !new File(nativeLib, "libGL.so").isFile())
            missing.add("Desktop OpenGL renderer (Mesa Zink or compatible) not packaged.");
        if (MicrosoftLogin.username(context).isEmpty()
                || MicrosoftLogin.uuid(context).isEmpty())
            missing.add("Sign in to a licensed Minecraft Java Microsoft account.");
        return new Plan(requested, mainClass, String.join(":", classpath),
                gameDir, assetsRoot, assetId, args, missing, libraryCount);
    }

    private static int appendLibraries(JSONArray libraries, File root,
                                       Set<String> classpath, List<String> missing)
            throws Exception {
        if (libraries == null) return 0;
        int ready = 0;
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject lib = libraries.optJSONObject(i);
            if (lib == null || !allowRules(lib.optJSONArray("rules"))) continue;
            JSONObject downloads = lib.optJSONObject("downloads");
            JSONObject artifact = downloads == null ? null : downloads.optJSONObject("artifact");
            String relative = artifact == null ? "" : artifact.optString("path", "");
            if (relative.isEmpty()) {
                // Fabric/Quilt library profiles commonly use Maven coordinates.
                relative = mavenPath(lib.optString("name", ""));
            }
            if (relative.isEmpty() || !relative.endsWith(".jar")) continue;
            File file = secureFile(root, relative);
            if (file.isFile() && file.length() > 0) {
                classpath.add(file.getAbsolutePath());
                ready++;
            } else {
                String name = lib.optString("name", relative);
                if (missing.size() < 50) missing.add("Required Java library missing: " + name);
            }
        }
        return ready;
    }

    private static boolean allowRules(JSONArray rules) {
        if (rules == null || rules.length() == 0) return true;
        boolean allowed = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            JSONObject features = rule.optJSONObject("features");
            if (features != null && features.length() > 0) continue;
            JSONObject os = rule.optJSONObject("os");
            if (os != null && !"linux".equals(os.optString("name"))) continue;
            allowed = "allow".equals(rule.optString("action"));
        }
        return allowed;
    }

    private static String mavenPath(String notation) {
        String[] parts = notation.split(":", -1);
        if (parts.length < 3 || parts.length > 4) return "";
        for (String part : parts)
            if (!part.matches("[A-Za-z0-9_.+-]{1,110}")) return "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                + parts[1] + "-" + parts[2]
                + (parts.length == 4 ? "-" + parts[3] : "") + ".jar";
    }

    private static File secureFile(File root, String relative) throws Exception {
        String[] parts = relative.split("/", -1);
        for (String part : parts)
            if (part.isEmpty() || part.equals("..") || part.equals(".")
                    || part.contains("\\") || part.contains(":"))
                throw new Exception("Unsafe Minecraft storage path.");
        File safe = new File(root, relative).getCanonicalFile();
        if (!safe.toPath().startsWith(root.getCanonicalFile().toPath()))
            throw new Exception("Minecraft storage path escapes root.");
        return safe;
    }

    private static JSONObject parseJson(File f, List<String> missing, String description) {
        try {
            if (!f.isFile() || f.length() > 12L * 1024 * 1024)
                throw new Exception("Not installed or too large.");
            try (InputStream in = new FileInputStream(f);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    if (out.size() + n > 12 * 1024 * 1024)
                        throw new Exception("Metadata limit reached.");
                    out.write(chunk, 0, n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Exception e) {
            missing.add(description + ": " + e.getMessage());
            return new JSONObject();
        }
    }
}
