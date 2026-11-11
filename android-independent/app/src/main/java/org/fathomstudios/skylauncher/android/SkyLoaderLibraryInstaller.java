package org.fathomstudios.skylauncher.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SkyLauncher-owned Fabric/Quilt library resolver. Reads official Minecraft
 * version-profile library metadata, resolves Maven paths, validates SHA-1,
 * and writes into the independent shared library cache.
 */
final class SkyLoaderLibraryInstaller {
    interface Progress { void update(String text); }
    private static final long MAX_FILE = 96L * 1024 * 1024;
    private static final long MAX_DESCRIPTOR_FILES = 400;
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";
    private static final String QUILT_MAVEN = "https://maven.quiltmc.org/repository/release/";

    private SkyLoaderLibraryInstaller() { }

    static int install(Context ctx, JSONObject descriptor, String loader,
                       AtomicBoolean cancelled, Progress progress) throws Exception {
        JSONArray array = descriptor.optJSONArray("libraries");
        if (array == null) throw new IOException("Official loader profile has no libraries.");
        if (array.length() > MAX_DESCRIPTOR_FILES)
            throw new IOException("Too many loader dependencies.");
        File root = new File(new File(ctx.getFilesDir(), "minecraft"), "libraries");
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Cannot create loader library cache.");
        Set<String> seen = new HashSet<>();
        int count = 0;
        for (int i = 0; i < array.length(); i++) {
            check(cancelled);
            JSONObject item = array.optJSONObject(i);
            if (item == null || !allowed(item.optJSONArray("rules"))) continue;
            JSONObject downloads = item.optJSONObject("downloads");
            JSONObject artifact = downloads == null ? null : downloads.optJSONObject("artifact");
            String path = artifact != null ? artifact.optString("path", "") : "";
            if (path.isEmpty()) path = fromMavenCoordinates(item.optString("name"));
            if (!path.endsWith(".jar") || !seen.add(path)) continue;
            File destination = new File(root, path).getCanonicalFile();
            if (!destination.toPath().startsWith(root.getCanonicalFile().toPath()))
                throw new IOException("Unsafe loader library target.");
            String base = item.optString("url", "");
            if (base.isEmpty()) {
                String name = item.optString("name", "");
                if ("Fabric".equals(loader) && (name.startsWith("net.fabricmc:")
                        || name.startsWith("net.fabricmc.")))
                    base = FABRIC_MAVEN;
                else if ("Quilt".equals(loader) && (name.startsWith("org.quiltmc:")
                        || name.startsWith("org.quiltmc.")))
                    base = QUILT_MAVEN;
                else base = MAVEN_CENTRAL;
            }
            if (!base.endsWith("/")) base += "/";
            String url = artifact != null ? artifact.optString("url", "") : "";
            if (url.isEmpty()) url = base + path;
            String checksum = artifact == null ? "" : artifact.optString("sha1", "");
            if (checksum.isEmpty()) checksum = shaFromRemote(url + ".sha1", cancelled);
            if (!checksum.matches("(?i)[0-9a-f]{40}"))
                throw new IOException("Official Maven hash missing for " + path);
            if (destination.isFile() && destination.length() <= MAX_FILE
                    && checksum.equalsIgnoreCase(sha1(destination))) {
                count++;
                continue;
            }
            progress.update("Installing " + loader + " library " + (count + 1)
                    + "/" + array.length() + "...");
            installVerified(url, destination, checksum, cancelled);
            count++;
        }
        progress.update("Verified " + count + " official " + loader + " libraries.");
        return count;
    }

    private static boolean allowed(JSONArray rules) {
        if (rules == null || rules.length() == 0) return true;
        boolean enabled = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            JSONObject features = rule.optJSONObject("features");
            if (features != null && features.length() > 0) continue;
            JSONObject os = rule.optJSONObject("os");
            if (os != null && !"linux".equalsIgnoreCase(os.optString("name"))) continue;
            enabled = "allow".equals(rule.optString("action"));
        }
        return enabled;
    }

    private static String fromMavenCoordinates(String input) throws IOException {
        String[] segments = input.split(":", -1);
        if (segments.length < 3 || segments.length > 4)
            throw new IOException("Unsupported loader Maven coordinate: " + input);
        for (String segment : segments)
            if (!segment.matches("[A-Za-z0-9_.+-]{1,128}"))
                throw new IOException("Invalid Maven library name.");
        return segments[0].replace('.', '/') + "/" + segments[1]
                + "/" + segments[2] + "/" + segments[1] + "-" + segments[2]
                + (segments.length == 4 ? "-" + segments[3] : "") + ".jar";
    }

    private static String shaFromRemote(String url, AtomicBoolean cancelled)
            throws Exception {
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream()) {
            byte[] data = new byte[256];
            int n = in.read(data);
            check(cancelled);
            if (n <= 0) throw new IOException("Empty official Maven SHA-1.");
            String value = new String(data, 0, n,
                    java.nio.charset.StandardCharsets.US_ASCII).trim().split("\\s+")[0];
            if (!value.matches("(?i)[0-9a-f]{40}"))
                throw new IOException("Invalid Maven SHA-1 file.");
            return value;
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection connect(String url) throws Exception {
        URL target = new URL(url);
        if (!"https".equalsIgnoreCase(target.getProtocol())
                || target.getUserInfo() != null)
            throw new IOException("Untrusted loader library protocol.");
        String host = target.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("repo.maven.apache.org")
                || host.equals("repo1.maven.org")
                || host.equals("maven.fabricmc.net")
                || host.equals("maven.quiltmc.org")
                || host.equals("maven.minecraftforge.net")
                || host.equals("libraries.minecraft.net")))
            throw new IOException("Untrusted library repository host: " + host);
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "SkyLauncher-IndependentAndroid");
        int status = conn.getResponseCode();
        if (status != 200) {
            conn.disconnect();
            throw new IOException("Library download failed: HTTP " + status);
        }
        return conn;
    }

    private static void installVerified(String url, File destination, String expectedSha,
                                        AtomicBoolean cancelled) throws Exception {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create library folder.");
        File staging = new File(parent, "." + destination.getName() + ".partial");
        HttpURLConnection conn = connect(url);
        try {
            if (conn.getContentLengthLong() > MAX_FILE)
                throw new IOException("Loader library exceeds maximum file size.");
            try (InputStream input = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream output = new FileOutputStream(staging);
                 BufferedOutputStream buffered = new BufferedOutputStream(output)) {
                byte[] buf = new byte[65536];
                long bytes = 0;
                int n;
                while ((n = input.read(buf)) != -1) {
                    check(cancelled);
                    bytes += n;
                    if (bytes > MAX_FILE) throw new IOException("Library data too large.");
                    buffered.write(buf, 0, n);
                }
                buffered.flush();
                output.getFD().sync();
            }
            if (!expectedSha.equalsIgnoreCase(sha1(staging)))
                throw new IOException("Official library checksum mismatch.");
            if (destination.exists() && !destination.delete())
                throw new IOException("Cannot replace damaged loader library.");
            if (!staging.renameTo(destination))
                throw new IOException("Failed to finalize Minecraft loader library.");
        } finally {
            conn.disconnect();
            staging.delete();
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte x : digest.digest())
            hex.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return hex.toString();
    }

    private static void check(AtomicBoolean cancel) throws IOException {
        if (cancel.get() || Thread.currentThread().isInterrupted())
            throw new IOException("Loader library installation cancelled.");
    }
}
