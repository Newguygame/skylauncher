package org.fathomstudios.skylauncher.android;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Modrinth version browser and dependency-aware Android mod installer. */
final class ModrinthClient {
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 40000;
    private static final long MAX_MOD = 256L * 1024L * 1024L;
    private static final int MAX_JSON = 3_000_000;
    private static final int MAX_DEPENDENCIES = 120;
    private static final String API = "https://api.modrinth.com/v2/";
    private static final String USER_AGENT =
            "SkyLauncherAndroid/0.9 (Fathom Studios; https://fathomstudios.org)";

    interface Progress {
        void update(String message);
        boolean cancelled();
    }

    static final class Mod {
        final String id, title, description;
        Mod(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }
    }

    static final class Version {
        final String id, projectId, number, name, channel, changelog, date;
        final JSONObject data;
        Version(JSONObject json) {
            data = json;
            id = json.optString("id");
            projectId = json.optString("project_id");
            number = json.optString("version_number", "?");
            name = json.optString("name", number);
            channel = json.optString("version_type", "release");
            changelog = json.optString("changelog", "");
            date = json.optString("date_published", "");
        }
        String display() {
            return number + " [" + channel + "]" +
                    (date.length() >= 10 ? " — " + date.substring(0, 10) : "");
        }
    }

    static final class Identified {
        final String filename, sha512;
        final Version version;
        Identified(String filename, String sha512, Version version) {
            this.filename = filename;
            this.sha512 = sha512;
            this.version = version;
        }
    }

    static final class InstallResult {
        final int installed, dependencies, alreadyPresent;
        final String version;
        InstallResult(int installed, int dependencies, int alreadyPresent, String version) {
            this.installed = installed;
            this.dependencies = dependencies;
            this.alreadyPresent = alreadyPresent;
            this.version = version;
        }
    }

    private static final class Downloaded {
        final Version version;
        final File file;
        final String filename, hash;
        final boolean requiredPinned;
        Downloaded(Version version, File file, String filename, String hash,
                   boolean requiredPinned) {
            this.version = version;
            this.file = file;
            this.filename = filename;
            this.hash = hash;
            this.requiredPinned = requiredPinned;
        }
    }

    private ModrinthClient() {}

    private static boolean idSafe(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{2,96}");
    }

    private static HttpURLConnection open(String address, String host,
                                          boolean json) throws Exception {
        URL endpoint = new URL(address);
        if (!"https".equalsIgnoreCase(endpoint.getProtocol())
                || !host.equalsIgnoreCase(endpoint.getHost())
                || endpoint.getUserInfo() != null)
            throw new IOException("Untrusted Modrinth URL.");
        HttpURLConnection c = (HttpURLConnection) endpoint.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", json ? "application/json" : "*/*");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("Modrinth HTTP " + code);
        }
        return c;
    }

    private static String readJson(String endpoint) throws Exception {
        HttpURLConnection c = open(endpoint, "api.modrinth.com", true);
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16_384];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (out.size() + n > MAX_JSON)
                    throw new IOException("Modrinth API response too large.");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static List<Mod> search(String query, String minecraft, String loader) throws Exception {
        return searchProjects(query, minecraft, loader, "mod");
    }

    static List<Mod> searchModpacks(String query, String minecraft, String loader)
            throws Exception {
        return searchProjects(query, minecraft, loader, "modpack");
    }

    private static List<Mod> searchProjects(String query, String minecraft,
                                             String loader, String kind) throws Exception {
        JSONArray facets = new JSONArray()
                .put(new JSONArray().put("project_type:" + kind))
                .put(new JSONArray().put("versions:" + minecraft));
        if (!"vanilla".equalsIgnoreCase(loader))
            facets.put(new JSONArray().put("categories:" + loader.toLowerCase(Locale.ROOT)));
        String endpoint = API + "search?limit=18&query="
                + URLEncoder.encode(query, "UTF-8")
                + "&facets=" + URLEncoder.encode(facets.toString(), "UTF-8");
        JSONArray hits = new JSONObject(readJson(endpoint)).optJSONArray("hits");
        List<Mod> results = new ArrayList<>();
        if (hits != null) for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.getJSONObject(i);
            results.add(new Mod(hit.optString("project_id"),
                    hit.optString("title"), hit.optString("description")));
        }
        return results;
    }

    static List<Version> versions(String projectId, String minecraft,
                                  String loader) throws Exception {
        if (!idSafe(projectId)) throw new IOException("Invalid Modrinth project ID.");
        if ("vanilla".equalsIgnoreCase(loader))
            throw new IOException("Select a modded Minecraft loader first.");
        JSONArray game = new JSONArray().put(minecraft);
        JSONArray loaders = new JSONArray().put(loader.toLowerCase(Locale.ROOT));
        JSONArray data = new JSONArray(readJson(API + "project/" + projectId
                + "/version?game_versions=" + URLEncoder.encode(game.toString(), "UTF-8")
                + "&loaders=" + URLEncoder.encode(loaders.toString(), "UTF-8")
                + "&include_changelog=false"));
        List<Version> found = new ArrayList<>();
        for (int i = 0; i < data.length() && found.size() < 100; i++)
            found.add(new Version(data.getJSONObject(i)));
        return found;
    }

    static Version version(String versionId) throws Exception {
        if (!idSafe(versionId)) throw new IOException("Invalid Modrinth version ID.");
        return new Version(new JSONObject(readJson(API + "version/" + versionId)));
    }

    private static boolean includes(JSONArray values, String value) {
        if (values == null) return false;
        for (int i = 0; i < values.length(); i++)
            if (value.equalsIgnoreCase(values.optString(i))) return true;
        return false;
    }

    private static void validateVersion(Version version, String minecraft,
                                        String loader) throws Exception {
        if (!idSafe(version.id) || !idSafe(version.projectId))
            throw new IOException("Invalid version metadata.");
        if (!includes(version.data.optJSONArray("game_versions"), minecraft)
                || !includes(version.data.optJSONArray("loaders"), loader)) {
            throw new IOException("Incompatible version: " + version.number
                    + " does not support Minecraft " + minecraft + " / " + loader + ".");
        }
    }

    static Identified identifyInstalled(Context ctx, ModFolderManager.Entry entry)
            throws Exception {
        String sha;
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = entry.local != null
                    ? new FileInputStream(entry.local)
                    : ctx.getContentResolver().openInputStream(entry.document.getUri())) {
            if (in == null) throw new IOException("Cannot read installed mod.");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        sha = hex(digest.digest());
        try {
            String lookup = API + "version_file/" + sha + "?algorithm=sha512";
            Version known = new Version(new JSONObject(readJson(lookup)));
            return new Identified(entry.name, sha, known);
        } catch (IOException notFound) {
            if (notFound.getMessage() != null
                    && notFound.getMessage().contains("HTTP 404")) return null;
            throw notFound;
        }
    }

    static InstallResult install(Context ctx, Mod mod,
                                  String minecraft, String loader) throws Exception {
        List<Version> all = versions(mod.id, minecraft, loader);
        if (all.isEmpty()) throw new IOException("No compatible mod release found.");
        return installVersion(ctx, all.get(0).id, minecraft, loader, null,
                new Progress() {
                    @Override public void update(String message) {}
                    @Override public boolean cancelled() { return false; }
                }, null);
    }

    static InstallResult installVersion(Context ctx, String selectedVersionId,
                                        String minecraft, String loader,
                                        Identified replacing, Progress progress,
                                        String expectedProfileId) throws Exception {
        if (progress == null) progress = new Progress() {
            @Override public void update(String message) {}
            @Override public boolean cancelled() { return false; }
        };
        String profileId = expectedProfileId == null
                ? ProfileStore.active(ctx).id : expectedProfileId;
        if (!profileId.equals(ProfileStore.active(ctx).id))
            throw new IOException("The active Minecraft profile changed.");
        Version selected = version(selectedVersionId);
        validateVersion(selected, minecraft, loader);
        if (replacing != null && !selected.projectId.equals(replacing.version.projectId))
            throw new IOException("Cannot replace a mod with a different project.");
        if (replacing != null && selected.id.equals(replacing.version.id))
            return new InstallResult(0, 0, 1, selected.number);

        File stage = new File(ctx.getCacheDir(), "sky-mods-" + UUID.randomUUID());
        if (!stage.mkdirs()) throw new IOException("Cannot create mod staging area.");
        LinkedHashMap<String, Version> plan = new LinkedHashMap<>();
        Set<String> inProgress = new HashSet<>();
        Map<String, Boolean> pinned = new HashMap<>();
        try {
            resolveDependencies(selected, minecraft, loader, plan, inProgress,
                    pinned, progress, true, 0);

            List<Downloaded> downloads = new ArrayList<>();
            int number = 0;
            for (Version entry : plan.values()) {
                if (progress.cancelled()) throw new IOException("Mod installation cancelled.");
                if (!profileId.equals(ProfileStore.active(ctx).id))
                    throw new IOException("Active profile changed.");
                JSONObject jar = selectJar(entry.data.optJSONArray("files"));
                String filename = jar.getString("filename");
                if (!safeJarName(filename)) throw new IOException(
                        "Unsupported mod filename: " + filename);
                String hash = jar.getJSONObject("hashes").getString("sha512");
                if (!hash.matches("(?i)[0-9a-f]{128}"))
                    throw new IOException("No valid SHA-512 for " + filename);
                File target = new File(stage, "mod-" + (++number) + ".jar");
                progress.update("Downloading verified JAR " + number + "/" + plan.size()
                        + ": " + filename);
                downloadJar(jar, target, hash, progress);
                downloads.add(new Downloaded(entry, target, filename, hash,
                        Boolean.TRUE.equals(pinned.get(entry.projectId))));
            }
            return commit(ctx, downloads, selected, replacing, stage,
                    profileId, progress);
        } finally {
            deleteTree(stage);
        }
    }

    private static void resolveDependencies(Version current, String minecraft, String loader,
                                            LinkedHashMap<String, Version> plan,
                                            Set<String> active, Map<String, Boolean> pinned,
                                            Progress progress, boolean main, int depth)
            throws Exception {
        if (progress.cancelled()) throw new IOException("Mod installation cancelled.");
        if (depth > MAX_DEPENDENCIES || plan.size() > MAX_DEPENDENCIES)
            throw new IOException("Dependency tree is too large.");
        validateVersion(current, minecraft, loader);
        if (plan.containsKey(current.projectId)) {
            if (!plan.get(current.projectId).id.equals(current.id))
                throw new IOException("Conflicting required versions of "
                        + current.projectId + ": " + current.number + " / "
                        + plan.get(current.projectId).number);
            return;
        }
        if (!active.add(current.projectId))
            throw new IOException("Circular dependency in " + current.projectId);
        JSONArray dependencies = current.data.optJSONArray("dependencies");
        if (dependencies != null) for (int i = 0; i < dependencies.length(); i++) {
            JSONObject dep = dependencies.getJSONObject(i);
            String type = dep.optString("dependency_type");
            if (!"required".equals(type)) continue;
            String project = dep.optString("project_id", "");
            String versionId = dep.optString("version_id", "");
            Version required;
            if (idSafe(versionId)) {
                required = version(versionId);
                if (idSafe(project) && !project.equals(required.projectId))
                    throw new IOException("Dependency metadata conflicts for " + project);
            } else if (idSafe(project)) {
                List<Version> versions = versions(project, minecraft, loader);
                if (versions.isEmpty()) throw new IOException(
                        "Required mod " + project + " has no compatible version.");
                required = version(versions.get(0).id);
            } else {
                throw new IOException("A required dependency of " + current.name
                        + " has no Modrinth project/version ID; install it manually.");
            }
            validateVersion(required, minecraft, loader);
            pinned.put(required.projectId, Boolean.TRUE.equals(pinned.get(required.projectId))
                    || idSafe(versionId));
            progress.update("Resolving required dependency: " + required.name);
            resolveDependencies(required, minecraft, loader, plan, active,
                    pinned, progress, false, depth + 1);
        }
        active.remove(current.projectId);
        if (plan.size() >= MAX_DEPENDENCIES)
            throw new IOException("Too many dependencies.");
        plan.put(current.projectId, current);
    }

    private static JSONObject selectJar(JSONArray files) throws Exception {
        if (files == null) throw new IOException("Version has no downloadable files.");
        JSONObject chosen = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            if (!file.optString("filename").toLowerCase(Locale.ROOT).endsWith(".jar"))
                continue;
            String kind = file.optString("file_type", "");
            if ("sources-jar".equals(kind) || "dev-jar".equals(kind)
                    || "javadoc-jar".equals(kind)) continue;
            if (chosen == null || file.optBoolean("primary")) chosen = file;
            if (file.optBoolean("primary")) break;
        }
        if (chosen == null) throw new IOException("Release has no runnable JAR.");
        return chosen;
    }

    private static boolean safeJarName(String filename) {
        if (filename == null || filename.length() < 5
                || filename.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255
                || !filename.toLowerCase(Locale.ROOT).endsWith(".jar")
                || filename.startsWith(".") || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0) return false;
        for (int i = 0; i < filename.length(); i++)
            if (Character.isISOControl(filename.charAt(i))) return false;
        return true;
    }

    private static void downloadJar(JSONObject jar, File target, String expected,
                                    Progress progress) throws Exception {
        if (jar.optLong("size", 0) <= 0 || jar.optLong("size") > MAX_MOD)
            throw new IOException("Invalid JAR size.");
        HttpURLConnection c = open(jar.getString("url"), "cdn.modrinth.com", false);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buf = new byte[65536];
            long count = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (progress.cancelled()) throw new IOException("Mod download cancelled.");
                count += n;
                if (count > MAX_MOD) throw new IOException("JAR too large.");
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            out.flush();
            if (count != jar.optLong("size"))
                throw new IOException("Incomplete JAR download.");
            if (!hex(digest.digest()).equalsIgnoreCase(expected))
                throw new IOException("SHA-512 mismatch: " + jar.optString("filename"));
        } finally {
            c.disconnect();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }

    private static String hashFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static Set<String> modIds(File jar) {
        Set<String> result = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (String path : new String[]{"META-INF/neoforge.mods.toml",
                    "META-INF/mods.toml"}) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.getSize() > 1_000_000) continue;
                String toml = readSmall(zip.getInputStream(entry));
                int dependencies = toml.indexOf("[[dependencies.");
                if (dependencies > 0) toml = toml.substring(0, dependencies);
                Matcher matcher = Pattern.compile(
                        "(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']").matcher(toml);
                while (matcher.find()) result.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null && fabric.getSize() < 1_000_000)
                result.add(new JSONObject(readSmall(zip.getInputStream(fabric)))
                        .optString("id").toLowerCase(Locale.ROOT));
            ZipEntry quilt = zip.getEntry("quilt.mod.json");
            if (quilt != null && quilt.getSize() < 1_000_000) {
                JSONObject q = new JSONObject(readSmall(zip.getInputStream(quilt)));
                result.add(q.optJSONObject("quilt_loader").optString("id")
                        .toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {}
        result.remove("");
        return result;
    }

    private static String readSmall(InputStream source) throws Exception {
        try (InputStream input = source;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (out.size() + n > 1_000_000)
                    throw new IOException("Mod metadata too large.");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static InstallResult commit(Context ctx, List<Downloaded> downloads,
                                        Version selected, Identified replacing, File stage,
                                        String expectedProfileId, Progress progress)
            throws Exception {
        if (!expectedProfileId.equals(ProfileStore.active(ctx).id))
            throw new IOException("Profile changed before installation.");
        ModFolderManager.Folder folder = ModFolderManager.resolve(ctx);
        if (folder.local == null)
            throw new IOException("Version switching requires this profile's local mods folder.");
        File mods = folder.local.getCanonicalFile();
        if (!mods.isDirectory() && !mods.mkdirs())
            throw new IOException("Cannot create mods directory.");
        if (Files.isSymbolicLink(mods.toPath()))
            throw new IOException("Unsafe mod directory.");
        File[] listing = mods.listFiles();
        if (listing == null) throw new IOException("Could not read installed mods.");

        Map<String, File> existingNames = new HashMap<>();
        Map<String, File> existingModIds = new HashMap<>();
        File replacingFile = null;
        for (File file : listing) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"))
                continue;
            existingNames.put(file.getName().toLowerCase(Locale.ROOT), file);
            for (String modId : modIds(file)) existingModIds.put(modId, file);
            if (replacing != null && file.getName().equals(replacing.filename))
                replacingFile = file;
        }
        if (replacing != null) {
            if (replacingFile == null
                    || !replacing.sha512.equalsIgnoreCase(hashFile(replacingFile)))
                throw new IOException("Installed mod changed since version selection.");
        }

        List<Downloaded> needed = new ArrayList<>();
        int already = 0;
        for (Downloaded item : downloads) {
            File sameName = existingNames.get(item.filename.toLowerCase(Locale.ROOT));
            boolean isSelectedMod = item.version.projectId.equals(selected.projectId);
            if (sameName != null && (!isSelectedMod || replacingFile == null
                    || !sameName.equals(replacingFile))) {
                if (item.hash.equalsIgnoreCase(hashFile(sameName))) {
                    already++;
                    continue;
                }
                throw new IOException("Conflicting installed file: " + item.filename
                        + ". SkyLauncher will not overwrite it.");
            }
            Set<String> newModIds = modIds(item.file);
            File conflict = null;
            for (String modId : newModIds) {
                File found = existingModIds.get(modId);
                if (found != null && (!isSelectedMod || replacingFile == null
                        || !found.equals(replacingFile))) {
                    conflict = found;
                    break;
                }
            }
            if (conflict != null) {
                if (isSelectedMod || item.requiredPinned)
                    throw new IOException("Conflicting installed mod " + conflict.getName()
                            + " with " + item.filename
                            + ". Change its version explicitly instead of duplicating it.");
                // An unpinned required dependency is already installed.
                // Do not replace user-managed CurseForge or local JARs.
                already++;
                continue;
            }
            needed.add(item);
        }

        long bytes = 0;
        for (Downloaded d : needed) bytes += d.file.length();
        if (mods.getUsableSpace() < bytes + 100_000_000L)
            throw new IOException("Insufficient free space for mod installation.");

        File backupDir = new File(ctx.getFilesDir(), "modrinth-mod-backups/"
                + expectedProfileId + "-" + System.currentTimeMillis());
        List<File> added = new ArrayList<>();
        File savedPrevious = null;
        boolean removedOld = false;
        try {
            // Stage all checksums and dependencies before touching installed files.
            if (replacingFile != null) {
                if (!backupDir.isDirectory() && !backupDir.mkdirs())
                    throw new IOException("Cannot create version rollback backup.");
                savedPrevious = new File(backupDir, replacingFile.getName());
                Files.copy(replacingFile.toPath(), savedPrevious.toPath());
            }
            for (Downloaded item : needed) {
                if (progress.cancelled()) throw new IOException("Mod installation cancelled.");
                if (!expectedProfileId.equals(ProfileStore.active(ctx).id))
                    throw new IOException("Profile changed during mod installation.");
                File target = new File(mods, item.filename);
                if (target.exists() && (replacingFile == null || !target.equals(replacingFile)))
                    throw new IOException("Another mod created " + target.getName());
                File pending = File.createTempFile(".sky-mod-", ".part", mods);
                try {
                    Files.copy(item.file.toPath(), pending.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    if (progress.cancelled()) throw new IOException("Installation cancelled.");
                    if (replacingFile != null && target.equals(replacingFile)) {
                        if (!target.delete()) throw new IOException(
                                "Could not replace existing mod.");
                        removedOld = true;
                    }
                    Files.move(pending.toPath(), target.toPath());
                    added.add(target);
                } finally {
                    pending.delete();
                }
                progress.update("Installed verified JAR: " + item.filename);
            }
            if (replacingFile != null && !removedOld) {
                // New version normally has a different filename.
                // Only remove the old version after new files are installed.
                if (!replacingFile.delete()) throw new IOException(
                        "Could not remove previous mod version.");
                removedOld = true;
            }
        } catch (Exception failure) {
            for (int i = added.size() - 1; i >= 0; i--)
                if (!added.get(i).delete())
                    failure.addSuppressed(new IOException(
                            "Could not undo " + added.get(i).getName()));
            if (removedOld && savedPrevious != null) try {
                Files.copy(savedPrevious.toPath(), replacingFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        return new InstallResult(needed.size(), Math.max(0, needed.size() - 1),
                already, selected.number);
    }

    private static void deleteTree(File root) {
        if (root == null || !root.exists()) return;
        File[] files = root.listFiles();
        if (files != null) for (File file : files) deleteTree(file);
        root.delete();
    }
}
