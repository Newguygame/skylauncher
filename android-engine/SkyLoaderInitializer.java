package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.util.Xml;

import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SkyLauncher's own modloader initializer.
 *
 * Loader version discovery, official downloads, profile generation and
 * validation do not call Amethyst's modloader classes or its installer UI.
 * The embedded game engine still supplies a Java runtime and the actual
 * Minecraft game launcher. Forge and NeoForge need a Java installer run.
 */
final class SkyLoaderInitializer {
    private static final String FABRIC = "https://meta.fabricmc.net/v2";
    private static final String QUILT = "https://meta.quiltmc.org/v3";
    private static final String FORGE = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    private static final String NEOFORGE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    private static final long MAX_METADATA = 5L * 1024 * 1024;
    private static final long MAX_INSTALLER = 180L * 1024 * 1024;

    interface Status {
        void update(String message);
        boolean cancelled();
    }

    static final class Result {
        final String kind, gameVersion, loaderVersion, versionId;
        final File installer;
        Result(String kind, String mc, String loader, String id, File installer) {
            this.kind = kind;
            this.gameVersion = mc;
            this.loaderVersion = loader;
            this.versionId = id;
            this.installer = installer;
        }
        boolean needsJavaInstaller() { return installer != null; }
    }

    private SkyLoaderInitializer() { }

    static Result initialize(Activity ctx, ProfileStore.Profile profile, Status progress)
            throws Exception {
        if (progress.cancelled()) throw new IOException("Modloader setup cancelled.");
        if (!ProfileStore.active(ctx).id.equals(profile.id))
            throw new IOException("The active profile changed.");
        if (profile.version == null
                || !profile.version.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IOException("Invalid Minecraft game version.");
        String kind = ProfileStore.LOADERS[profile.loader];
        if ("Vanilla".equals(kind)) throw new IOException("Vanilla needs no modloader.");

        progress.update("SkyLauncher: checking official " + kind + " metadata...");
        if ("Fabric".equals(kind) || "Quilt".equals(kind)) {
            return initializeFabriclike(ctx, profile, kind, progress);
        }
        if ("Forge".equals(kind) || "NeoForge".equals(kind)) {
            return initializeForgeLike(ctx, profile, kind, progress);
        }
        throw new IOException("Unsupported modloader: " + kind);
    }

    private static Result initializeFabriclike(Activity ctx, ProfileStore.Profile p,
                                                 String kind, Status progress)
            throws Exception {
        String base = "Fabric".equals(kind) ? FABRIC : QUILT;
        String versionsUrl = base + "/versions/loader/" + p.version;
        JSONArray candidates = new JSONArray(readText(versionsUrl, MAX_METADATA, progress));
        String chosen = null;
        boolean pinned = p.loaderVersion != null && !p.loaderVersion.isEmpty();
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.optJSONObject(i);
            if (item == null) continue;
            JSONObject loader = item.optJSONObject("loader");
            if (loader == null) continue;
            String candidate = loader.optString("version");
            if (!candidate.matches("[A-Za-z0-9.+_-]{1,80}")) continue;
            if (pinned) {
                if (p.loaderVersion.equals(candidate)) {
                    chosen = candidate;
                    break;
                }
            } else if (loader.optBoolean("stable",
                    !candidate.toLowerCase(Locale.ROOT).contains("beta"))) {
                if (chosen == null || compareVersions(candidate, chosen) > 0)
                    chosen = candidate;
            }
        }
        if (chosen == null)
            throw new IOException("No compatible " + kind + " version found for "
                    + p.version + (pinned ? " / loader " + p.loaderVersion : ""));
        String id = kind.toLowerCase(Locale.ROOT) + "-loader-" + chosen + "-" + p.version;
        String profileUrl = versionsUrl + "/" + chosen + "/profile/json";
        progress.update("SkyLauncher: generating " + kind + " profile " + chosen + "...");
        byte[] bytes = readBytes(profileUrl, MAX_METADATA, progress);
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!id.equals(json.optString("id")) || !p.version.equals(json.optString("inheritsFrom")))
            throw new IOException("Official " + kind + " profile does not match the selected version.");
        if (!json.has("libraries") || !json.has("mainClass"))
            throw new IOException("Incomplete " + kind + " launcher profile.");
        if (!p.id.equals(ProfileStore.active(ctx).id))
            throw new IOException("Profile changed before saving the modloader.");
        SkyEngineProfileBridge.prepareEngine(ctx);
        File versions = new File(Tools.DIR_HOME_VERSION, id);
        if (!versions.isDirectory() && !versions.mkdirs())
            throw new IOException("Cannot create the Minecraft version directory.");
        File target = new File(versions, id + ".json");
        File pending = new File(versions, "." + id + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (target.exists() && !target.delete())
                throw new IOException("Cannot replace the existing loader descriptor.");
            if (!pending.renameTo(target))
                throw new IOException("Cannot save " + kind + " loader descriptor.");
        } finally {
            pending.delete();
        }
        progress.update("SkyLauncher: " + kind + " profile prepared (" + chosen + ").");
        return new Result(kind, p.version, chosen, id, null);
    }

    private static Result initializeForgeLike(Activity ctx, ProfileStore.Profile p,
                                                String kind, Status progress)
            throws Exception {
        String base = "Forge".equals(kind) ? FORGE : NEOFORGE;
        String prefix;
        if ("Forge".equals(kind)) {
            prefix = p.version + "-";
        } else {
            if (!p.version.matches("1\\.(20|21|22|23|24|25|26)\\.[0-9]+"))
                throw new IOException("Unsupported NeoForge Minecraft version " + p.version);
            prefix = p.version.substring(2) + ".";
        }
        List<String> versions = readMavenVersions(
                base + "maven-metadata.xml", progress);
        boolean pinned = p.loaderVersion != null && !p.loaderVersion.isEmpty();
        String selected = null;
        for (String ver : versions) {
            if (!ver.startsWith(prefix)) continue;
            String suffix = ver.substring(prefix.length());
            if (suffix.isEmpty()) continue;
            if (pinned) {
                boolean match = "Forge".equals(kind)
                        ? (ver.equals(p.version + "-" + p.loaderVersion)
                            || ver.equals(p.loaderVersion))
                        : ver.equals(p.loaderVersion);
                if (match) { selected = ver; break; }
            } else {
                String lower = suffix.toLowerCase(Locale.ROOT);
                if (lower.contains("alpha") || lower.contains("beta")
                        || lower.contains("snapshot") || lower.contains("-pre")) continue;
                if (selected == null || compareVersions(ver, selected) > 0)
                    selected = ver;
            }
        }
        if (selected == null)
            throw new IOException("No compatible " + kind + " installer for " + p.version
                    + (pinned ? " / " + p.loaderVersion : ""));
        String id = "Forge".equals(kind)
                ? p.version + "-forge-" + selected.substring(prefix.length())
                : "neoforge-" + selected;
        String url = base + selected + "/"
                + ("Forge".equals(kind) ? "forge-" : "neoforge-")
                + selected + "-installer.jar";
        if (progress.cancelled()) throw new IOException("Modloader setup cancelled.");
        progress.update("SkyLauncher: downloading " + kind + " " + selected + "...");
        File download = new File(ctx.getCacheDir(),
                "skyloader-" + p.id + "-" + UUID.randomUUID() + ".jar");
        try {
            downloadFile(url, download, MAX_INSTALLER, progress);
            // Official Maven checksums are used when available.
            String expected = readOptionalSha1(url + ".sha1", progress);
            if (expected != null && !expected.equalsIgnoreCase(sha1(download)))
                throw new IOException("Official installer checksum mismatch.");
            try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(download)) {
                if (jar.getEntry("META-INF/MANIFEST.MF") == null)
                    throw new IOException("Downloaded installer has no Java manifest.");
            }
            if (!p.id.equals(ProfileStore.active(ctx).id))
                throw new IOException("Profile changed during modloader download.");
            return new Result(kind, p.version,
                    "Forge".equals(kind) ? selected.substring(prefix.length()) : selected,
                    id, download);
        } catch (Exception fail) {
            download.delete();
            throw fail;
        }
    }

    static int minimumJavaVersion(String version) {
        try {
            String[] parts = version.split("\\.");
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            return minor >= 21 || (minor == 20 && patch >= 5)
                    ? 21 : minor >= 17 ? 17 : 8;
        } catch (Exception ignored) {
            return 21;
        }
    }

    private static List<String> readMavenVersions(String url, Status progress)
            throws Exception {
        byte[] xml = readBytes(url, MAX_METADATA, progress);
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new java.io.ByteArrayInputStream(xml), "UTF-8");
        List<String> result = new ArrayList<>();
        boolean inVersions = false;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if ("versions".equals(parser.getName())) inVersions = true;
                if (inVersions && "version".equals(parser.getName())) {
                    String value = parser.nextText().trim();
                    if (value.matches("[A-Za-z0-9.+_-]{1,120}")) result.add(value);
                }
            } else if (event == XmlPullParser.END_TAG
                    && "versions".equals(parser.getName())) inVersions = false;
        }
        if (result.isEmpty()) throw new IOException("Official Maven metadata has no versions.");
        return result;
    }

    private static HttpURLConnection connection(String link) throws Exception {
        URL url = new URL(link);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null)
            throw new IOException("Only official HTTPS modloader downloads are permitted.");
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("meta.fabricmc.net") || host.equals("meta.quiltmc.org")
                || host.equals("maven.minecraftforge.net")
                || host.equals("maven.neoforged.net")))
            throw new IOException("Unapproved modloader download host.");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(45000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "SkyLauncher-Android/loader-initializer");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("Modloader host returned HTTP " + code);
        }
        return c;
    }

    private static byte[] readBytes(String url, long limit, Status status)
            throws Exception {
        HttpURLConnection c = connection(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (status.cancelled()) throw new IOException("Modloader setup cancelled.");
                if ((long) output.size() + n > limit)
                    throw new IOException("Modloader response is too large.");
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String readText(String url, long limit, Status status) throws Exception {
        return new String(readBytes(url, limit, status), StandardCharsets.UTF_8);
    }

    private static void downloadFile(String url, File file, long limit, Status status)
            throws Exception {
        HttpURLConnection c = connection(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             FileOutputStream output = new FileOutputStream(file);
             BufferedOutputStream buffered = new BufferedOutputStream(output)) {
            if (c.getContentLengthLong() > limit)
                throw new IOException("Installer exceeds maximum supported size.");
            long copied = 0;
            byte[] data = new byte[65536];
            int count;
            while ((count = in.read(data)) != -1) {
                if (status.cancelled()) throw new IOException("Modloader setup cancelled.");
                copied += count;
                if (copied > limit) throw new IOException("Installer exceeds size limit.");
                buffered.write(data, 0, count);
            }
            buffered.flush();
            output.getFD().sync();
        } finally {
            c.disconnect();
        }
    }

    private static String readOptionalSha1(String url, Status status) throws Exception {
        try {
            String checksum = readText(url, 300, status).trim().split("\\s+")[0];
            if (!checksum.matches("(?i)[a-f0-9]{40}"))
                throw new IOException("Invalid official Maven checksum.");
            return checksum;
        } catch (IOException error) {
            if (error.getMessage() != null && error.getMessage().contains("HTTP 404"))
                return null;
            throw error;
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] data = new byte[65536];
            int n;
            while ((n = in.read(data)) != -1) digest.update(data, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest())
            result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("[^0-9]+"), b = right.split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long n = i < a.length && !a[i].isEmpty() ? parse(a[i]) : 0;
            long m = i < b.length && !b[i].isEmpty() ? parse(b[i]) : 0;
            if (n != m) return Long.compare(n, m);
        }
        return left.compareTo(right);
    }

    private static long parse(String n) {
        try { return Long.parseLong(n); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
