package org.fathomstudios.skylauncher.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fully SkyLauncher-owned download/verify/install path for official Minecraft
 * Java game files. This is not a wrapper around a third-party Android launcher.
 *
 * Native Java execution and GL/input adaptation are separate and MUST be
 * implemented before any claim of playable Minecraft.
 */
final class SkyGameInstaller {
    interface Progress { void update(String message); }
    private static final long MAX_METADATA = 12L * 1024 * 1024;
    private static final long MAX_CLIENT = 200L * 1024 * 1024;
    private static final long MAX_LIBRARY = 90L * 1024 * 1024;
    private static final long MAX_ASSET = 150L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final String MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private SkyGameInstaller() { }

    static void install(Context context, ProfileStore.Profile profile,
                        AtomicBoolean cancelled, Progress progress) throws Exception {
        if (!profile.version.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IOException("Invalid Minecraft version.");
        File root = new File(context.getFilesDir(), "minecraft");
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Cannot create Minecraft storage.");
        ProfileStore.root(context, profile);
        checkCancelled(cancelled);

        progress.update("Checking official Mojang Minecraft version manifest...");
        JSONObject manifest = new JSONObject(readText(MANIFEST, MAX_METADATA, cancelled));
        JSONArray versions = manifest.getJSONArray("versions");
        JSONObject versionRef = null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject item = versions.optJSONObject(i);
            if (item != null && profile.version.equals(item.optString("id"))) {
                versionRef = item;
                break;
            }
        }
        if (versionRef == null)
            throw new IOException("Minecraft version " + profile.version + " not found.");
        File versionFolder = new File(new File(root, "versions"), profile.version);
        File versionJson = checkedFile(versionFolder, profile.version + ".json");
        String metaSha = versionRef.optString("sha1");
        String metaUrl = versionRef.getString("url");
        installVerified(metaUrl, versionJson, metaSha, MAX_METADATA, cancelled);

        JSONObject version = new JSONObject(readFile(versionJson, MAX_METADATA));
        if (!profile.version.equals(version.optString("id")))
            throw new IOException("Downloaded version metadata does not match Minecraft "
                    + profile.version);

        progress.update("Installing official Minecraft client...");
        JSONObject downloads = version.getJSONObject("downloads");
        JSONObject client = downloads.getJSONObject("client");
        File clientJar = checkedFile(versionFolder, profile.version + ".jar");
        installVerified(client.getString("url"), clientJar,
                client.getString("sha1"), MAX_CLIENT, cancelled);

        progress.update("Checking Minecraft Java libraries...");
        int librariesInstalled = 0;
        JSONArray libraries = version.getJSONArray("libraries");
        Set<String> libraryNames = new HashSet<>();
        for (int i = 0; i < libraries.length(); i++) {
            checkCancelled(cancelled);
            JSONObject item = libraries.getJSONObject(i);
            if (!allowedForAndroidJava(item)) continue;
            JSONObject artifact = item.optJSONObject("downloads");
            if (artifact == null) continue;
            artifact = artifact.optJSONObject("artifact");
            if (artifact == null) continue;
            String relative = artifact.optString("path");
            if (!relative.endsWith(".jar")) continue;
            if (!libraryNames.add(relative)) continue;
            File destination = checkedFile(new File(root, "libraries"), relative);
            installVerified(artifact.getString("url"), destination,
                    artifact.getString("sha1"), MAX_LIBRARY, cancelled);
            librariesInstalled++;
            if (librariesInstalled % 15 == 0)
                progress.update("Verified " + librariesInstalled + " Minecraft libraries...");
        }

        JSONObject assetIndexDescriptor = version.getJSONObject("assetIndex");
        String assetId = assetIndexDescriptor.getString("id");
        if (!assetId.matches("[A-Za-z0-9._-]{1,80}"))
            throw new IOException("Unsafe Minecraft asset index identifier.");
        progress.update("Downloading Minecraft asset index...");
        File assets = new File(root, "assets");
        File index = checkedFile(new File(assets, "indexes"), assetId + ".json");
        installVerified(assetIndexDescriptor.getString("url"), index,
                assetIndexDescriptor.getString("sha1"), MAX_METADATA, cancelled);

        JSONObject objects = new JSONObject(readFile(index, MAX_METADATA))
                .getJSONObject("objects");
        JSONArray assetNames = objects.names();
        int total = assetNames == null ? 0 : assetNames.length();
        int verified = 0;
        if (assetNames != null) for (int i = 0; i < assetNames.length(); i++) {
            checkCancelled(cancelled);
            String name = assetNames.getString(i);
            JSONObject entry = objects.getJSONObject(name);
            String hash = entry.getString("hash");
            if (!hash.matches("[0-9a-f]{40}"))
                throw new IOException("Invalid Minecraft resource hash.");
            String prefix = hash.substring(0, 2);
            String url = "https://resources.download.minecraft.net/" + prefix + "/" + hash;
            File resource = checkedFile(new File(assets, "objects"), prefix + "/" + hash);
            installVerified(url, resource, hash, MAX_ASSET, cancelled);
            verified++;
            if (verified % 30 == 0 || verified == total) {
                progress.update("Verified Minecraft assets: " + verified + "/" + total);
            }
        }

        JSONObject status = new JSONObject();
        status.put("minecraft", profile.version);
        status.put("loader", profile.loader);
        status.put("clientSha1", client.getString("sha1"));
        status.put("libraries", librariesInstalled);
        status.put("assets", verified);
        status.put("completedAt", System.currentTimeMillis());
        File installed = checkedFile(ProfileStore.root(context, profile),
                "skylauncher-install.json");
        byte[] statusBytes = status.toString(2).getBytes(StandardCharsets.UTF_8);
        File staged = new File(installed.getParentFile(), "skylauncher-install.json.partial");
        try (FileOutputStream out = new FileOutputStream(staged)) {
            out.write(statusBytes);
            out.getFD().sync();
        }
        if (installed.exists() && !installed.delete())
            throw new IOException("Cannot update installation record.");
        if (!staged.renameTo(installed))
            throw new IOException("Cannot finalize installation record.");
        progress.update("Minecraft " + profile.version + " game files verified: "
                + librariesInstalled + " libraries and " + verified + " assets.");
    }

    /** Library rules are evaluated for the Java side, not desktop native classifiers.
     *  Native LWJGL and graphics replacements belong to SkyLauncher's own
     *  Android compatibility layer and must not be downloaded as desktop natives.
     */
    private static boolean allowedForAndroidJava(JSONObject library) {
        JSONArray rules = library.optJSONArray("rules");
        if (rules == null || rules.length() == 0) return true;
        boolean allowed = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            JSONObject os = rule.optJSONObject("os");
            // Android uses Linux-compatible Java artifacts, but cannot run
            // desktop Linux native binaries. Only downloads.artifact is used.
            if (os != null && !"linux".equalsIgnoreCase(os.optString("name")))
                continue;
            JSONObject features = rule.optJSONObject("features");
            if (features != null && features.length() > 0) continue;
            allowed = "allow".equals(rule.optString("action"));
        }
        return allowed;
    }

    private static File checkedFile(File root, String relative) throws IOException {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("\\")
                || relative.contains(":"))
            throw new IOException("Unsafe Minecraft file path: " + relative);
        String[] parts = relative.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("Unsafe Minecraft file path.");
        }
        File canonicalRoot = root.getCanonicalFile();
        File f = new File(root, relative).getCanonicalFile();
        if (!f.toPath().startsWith(canonicalRoot.toPath()))
            throw new IOException("Minecraft file escapes target storage.");
        return f;
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new IOException("Download cancelled.");
    }

    private static HttpURLConnection connect(String value) throws Exception {
        URL current = new URL(value);
        for (int n = 0; n <= MAX_REDIRECTS; n++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())
                    || current.getUserInfo() != null)
                throw new IOException("Minecraft downloads must use HTTPS.");
            String host = current.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("piston-meta.mojang.com")
                    || host.equals("launchermeta.mojang.com")
                    || host.equals("piston-data.mojang.com")
                    || host.equals("launcher.mojang.com")
                    || host.equals("libraries.minecraft.net")
                    || host.equals("resources.download.minecraft.net")))
                throw new IOException("Unrecognized Minecraft download host: " + host);
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(25000);
            c.setReadTimeout(45000);
            c.setRequestProperty("User-Agent", "SkyLauncherIndependentAndroid/0.1");
            int status = c.getResponseCode();
            if (status == 301 || status == 302 || status == 303
                    || status == 307 || status == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("Missing download redirect.");
                current = new URL(current, location);
                continue;
            }
            if (status != 200) {
                c.disconnect();
                throw new IOException("Official Minecraft download HTTP " + status);
            }
            return c;
        }
        throw new IOException("Too many download redirects.");
    }

    private static byte[] readBytes(String url, long max, AtomicBoolean cancel)
            throws Exception {
        HttpURLConnection c = connect(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) {
                checkCancelled(cancel);
                if (out.size() + (long) count > max)
                    throw new IOException("Minecraft metadata exceeds size limit.");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String readText(String url, long max, AtomicBoolean cancel)
            throws Exception {
        return new String(readBytes(url, max, cancel), StandardCharsets.UTF_8);
    }

    private static String readFile(File file, long max) throws Exception {
        if (!file.isFile() || file.length() > max)
            throw new IOException("Minecraft metadata missing or too large.");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + (long) count > max)
                    throw new IOException("Minecraft metadata too large.");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String sha1(File target) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(target))) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte value : sha.digest())
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static void installVerified(String url, File destination, String expectedSha,
                                        long maxBytes, AtomicBoolean cancel) throws Exception {
        if (expectedSha == null || !expectedSha.matches("(?i)[a-f0-9]{40}"))
            throw new IOException("Unverified Minecraft file: " + destination.getName());
        checkCancelled(cancel);
        if (destination.isFile()) {
            if (destination.length() <= maxBytes
                    && expectedSha.equalsIgnoreCase(sha1(destination))) return;
            if (!destination.delete())
                throw new IOException("Cannot replace damaged Minecraft file.");
        }
        File folder = destination.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Cannot create Minecraft download directory.");
        File pending = new File(folder, destination.getName() + ".sky-partial");
        HttpURLConnection c = connect(url);
        try {
            long expectedLength = c.getContentLengthLong();
            if (expectedLength > maxBytes)
                throw new IOException("Download larger than permitted.");
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 FileOutputStream output = new FileOutputStream(pending);
                 OutputStream out = new BufferedOutputStream(output)) {
                byte[] buffer = new byte[65536];
                long bytes = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled(cancel);
                    bytes += read;
                    if (bytes > maxBytes) throw new IOException("Download size limit exceeded.");
                    out.write(buffer, 0, read);
                }
                out.flush();
                output.getFD().sync();
            }
            if (!expectedSha.equalsIgnoreCase(sha1(pending)))
                throw new IOException("Minecraft file checksum mismatch: "
                        + destination.getName());
            checkCancelled(cancel);
            if (!pending.renameTo(destination))
                throw new IOException("Cannot finalize verified Minecraft file.");
        } finally {
            c.disconnect();
            pending.delete();
        }
    }
}
