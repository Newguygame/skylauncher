package org.fathomstudios.skylauncher.android;

import android.content.Context;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
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
 * Own modloader metadata and preparation implementation.
 * Does not import code, binaries, package names, or activities from any
 * third-party Android Minecraft launcher. Forge/NeoForge installer execution
 * remains blocked until SkyLauncher's OWN Java/JNI backend is ready.
 */
final class SkyModloaderInitializer {
    interface Progress { void update(String text); }
    static final class Result {
        final String kind, loaderVersion, versionId;
        final boolean needsNativeJvm;
        Result(String kind, String loaderVersion, String id, boolean needsJvm) {
            this.kind = kind; this.loaderVersion = loaderVersion;
            this.versionId = id; this.needsNativeJvm = needsJvm;
        }
    }

    private static final long MAX_JSON = 8L * 1024 * 1024;
    private static final long MAX_INSTALLER = 200L * 1024 * 1024;
    private static final String FABRIC = "https://meta.fabricmc.net/v2";
    private static final String QUILT = "https://meta.quiltmc.org/v3";
    private static final String FORGE = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    private static final String NEOFORGE =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    private SkyModloaderInitializer() { }

    static Result prepare(Context context, ProfileStore.Profile profile,
                          AtomicBoolean cancelled, Progress progress) throws Exception {
        check(cancelled);
        if (profile.id == null || !profile.id.matches("[a-f0-9-]{36}"))
            throw new IOException("Invalid profile.");
        if (!profile.version.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IOException("Invalid Minecraft version.");
        switch (profile.loader) {
            case "Fabric": return fabricOrQuilt(context, profile, cancelled, progress, FABRIC);
            case "Quilt": return fabricOrQuilt(context, profile, cancelled, progress, QUILT);
            case "Forge": return forgeFamily(context, profile, cancelled, progress, false);
            case "NeoForge": return forgeFamily(context, profile, cancelled, progress, true);
            case "Vanilla":
                return new Result("Vanilla", profile.version, profile.version, false);
            default:
                throw new IOException("Unknown Minecraft modloader " + profile.loader);
        }
    }

    private static Result fabricOrQuilt(Context ctx, ProfileStore.Profile p,
                                        AtomicBoolean stop, Progress progress,
                                        String endpoint) throws Exception {
        String kind = endpoint.equals(FABRIC) ? "Fabric" : "Quilt";
        progress.update("Looking up official " + kind + " versions...");
        JSONArray versions = new JSONArray(readString(
                endpoint + "/versions/loader/" + p.version, MAX_JSON, stop));
        String newest = null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject o = versions.optJSONObject(i);
            if (o == null) continue;
            JSONObject loader = o.optJSONObject("loader");
            if (loader == null) continue;
            String version = loader.optString("version");
            if (!version.matches("[A-Za-z0-9._+-]{1,90}")) continue;
            boolean stable = loader.optBoolean("stable",
                    !version.toLowerCase(Locale.ROOT).contains("beta"));
            if (!stable) continue;
            if (newest == null || compare(version, newest) > 0) newest = version;
        }
        if (newest == null)
            throw new IOException("No compatible stable " + kind + " version.");
        String expectedId = kind.toLowerCase(Locale.ROOT) + "-loader-"
                + newest + "-" + p.version;
        progress.update("Preparing " + kind + " " + newest + " profile...");
        byte[] raw = readBytes(endpoint + "/versions/loader/" + p.version
                + "/" + newest + "/profile/json", MAX_JSON, stop);
        JSONObject descriptor = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        String id = descriptor.optString("id");
        if (!expectedId.equals(id)
                || !p.version.equals(descriptor.optString("inheritsFrom"))
                || descriptor.optJSONArray("libraries") == null
                || descriptor.optString("mainClass").isEmpty())
            throw new IOException("Official " + kind + " descriptor is invalid.");
        File root = new File(ctx.getFilesDir(), "minecraft");
        File folder = new File(new File(root, "versions"), id).getCanonicalFile();
        if (!folder.toPath().startsWith(root.getCanonicalFile().toPath()))
            throw new IOException("Loader path escapes SkyLauncher storage.");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Cannot create loader version folder.");
        File target = new File(folder, id + ".json");
        writeAtomic(target, raw);
        check(stop);
        // Resolve loader-side Maven dependencies independently. Minecraft's
        // base libraries are handled by SkyGameInstaller.
        SkyLoaderLibraryInstaller.install(ctx, descriptor, kind, stop, progress::update);
        check(stop);
        record(ctx, p, new Result(kind, newest, id, false));
        progress.update(kind + " " + newest + " profile prepared. Native game launcher "
                + "is still required for playing.");
        return new Result(kind, newest, id, false);
    }

    private static Result forgeFamily(Context ctx, ProfileStore.Profile p,
                                      AtomicBoolean stop, Progress progress,
                                      boolean neo) throws Exception {
        String kind = neo ? "NeoForge" : "Forge";
        String base = neo ? NEOFORGE : FORGE;
        String prefix;
        if (neo) {
            if (!p.version.matches("1\\.[0-9]{2}\\.[0-9]+"))
                throw new IOException("NeoForge family cannot be determined safely for "
                        + p.version);
            prefix = p.version.substring(2) + ".";
        } else {
            prefix = p.version + "-";
        }
        progress.update("Reading official " + kind + " Maven metadata...");
        byte[] xml = readBytes(base + "maven-metadata.xml", MAX_JSON, stop);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
        List<String> candidates = new ArrayList<>();
        boolean inVersions = false;
        int next;
        while ((next = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (next == XmlPullParser.START_TAG) {
                if ("versions".equals(parser.getName())) inVersions = true;
                if (inVersions && "version".equals(parser.getName())) {
                    String v = parser.nextText();
                    if (v.startsWith(prefix) && v.matches("[A-Za-z0-9._+-]{1,110}"))
                        candidates.add(v);
                }
            } else if (next == XmlPullParser.END_TAG &&
                    "versions".equals(parser.getName())) inVersions = false;
        }
        String selected = null;
        for (String v : candidates) {
            String lower = v.toLowerCase(Locale.ROOT);
            if (lower.contains("alpha") || lower.contains("beta")
                    || lower.contains("snapshot") || lower.contains("-pre")) continue;
            if (selected == null || compare(v, selected) > 0) selected = v;
        }
        if (selected == null)
            throw new IOException("No compatible " + kind + " version for " + p.version);

        String versionId = neo ? "neoforge-" + selected :
                p.version + "-forge-" + selected.substring(prefix.length());
        String name = (neo ? "neoforge-" : "forge-") + selected + "-installer.jar";
        String link = base + selected + "/" + name;
        File root = new File(ctx.getFilesDir(), "minecraft");
        File folder = new File(root, "loader-installers");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Cannot create loader installers folder.");
        File installer = new File(folder, name);
        progress.update("Downloading official " + kind + " " + selected + " installer...");
        String sha = readString(link + ".sha1", 256, stop).trim().split("\\s+")[0];
        if (!sha.matches("(?i)[a-f0-9]{40}"))
            throw new IOException("Missing valid official " + kind + " installer checksum.");
        installJar(link, installer, sha, stop);
        Result prepared = new Result(kind,
                neo ? selected : selected.substring(prefix.length()), versionId, true);
        record(ctx, p, prepared);
        progress.update(kind + " installer verified. Installation needs SkyLauncher's "
                + "future native Java 21 runner (not Amethyst).");
        return prepared;
    }

    private static void record(Context ctx, ProfileStore.Profile p, Result r)
            throws Exception {
        File game = ProfileStore.root(ctx, p);
        JSONObject record = new JSONObject();
        record.put("minecraft", p.version);
        record.put("loader", r.kind);
        record.put("loaderVersion", r.loaderVersion);
        record.put("versionId", r.versionId);
        record.put("needsNativeJvm", r.needsNativeJvm);
        record.put("playable", false);
        writeAtomic(new File(game, "skylauncher-loader.json"),
                record.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void installJar(String link, File dest, String sha,
                                   AtomicBoolean stop) throws Exception {
        if (dest.isFile() && sha.equalsIgnoreCase(sha1(dest))) return;
        File pending = new File(dest.getParentFile(), dest.getName() + ".pending");
        HttpURLConnection c = connect(link);
        try {
            if (c.getContentLengthLong() > MAX_INSTALLER)
                throw new IOException("Loader installer is too large.");
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 FileOutputStream fout = new FileOutputStream(pending);
                 BufferedOutputStream out = new BufferedOutputStream(fout)) {
                byte[] buffer = new byte[32768];
                long count = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    check(stop);
                    count += n;
                    if (count > MAX_INSTALLER) throw new IOException("Loader exceeds limit.");
                    out.write(buffer, 0, n);
                }
                out.flush(); fout.getFD().sync();
            }
            if (!sha.equalsIgnoreCase(sha1(pending)))
                throw new IOException("Official modloader checksum mismatch.");
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pending)) {
                if (zip.getEntry("META-INF/MANIFEST.MF") == null)
                    throw new IOException("Installer JAR manifest missing.");
            }
            if (dest.exists() && !dest.delete())
                throw new IOException("Cannot replace installer.");
            if (!pending.renameTo(dest))
                throw new IOException("Cannot finish loader download.");
        } finally {
            c.disconnect();
            pending.delete();
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte b : digest.digest())
            hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private static void writeAtomic(File target, byte[] bytes) throws Exception {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create loader metadata folder.");
        File pending = new File(parent, "." + UUID.randomUUID() + ".partial");
        try {
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (target.exists() && !target.delete())
                throw new IOException("Cannot replace loader metadata.");
            if (!pending.renameTo(target))
                throw new IOException("Cannot save verified modloader metadata.");
        } finally {
            pending.delete();
        }
    }

    private static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long l = i < x.length && !x[i].isEmpty() ? asLong(x[i]) : 0;
            long r = i < y.length && !y[i].isEmpty() ? asLong(y[i]) : 0;
            if (l != r) return Long.compare(l, r);
        }
        return a.compareTo(b);
    }

    private static long asLong(String raw) {
        try { return Long.parseLong(raw); } catch (Exception ignored) { return 0; }
    }

    private static void check(AtomicBoolean stop) throws IOException {
        if (stop.get() || Thread.currentThread().isInterrupted())
            throw new IOException("Modloader initialization cancelled.");
    }

    private static HttpURLConnection connect(String link) throws Exception {
        URL current = new URL(link);
        for (int i = 0; i < 5; i++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())
                    || current.getUserInfo() != null)
                throw new IOException("Modloader downloads require HTTPS.");
            String host = current.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("meta.fabricmc.net") || host.equals("meta.quiltmc.org")
                    || host.equals("maven.minecraftforge.net")
                    || host.equals("maven.neoforged.net")))
                throw new IOException("Unexpected modloader host: " + host);
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setConnectTimeout(20000); c.setReadTimeout(45000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "SkyLauncherIndependentAndroid/0.1");
            int response = c.getResponseCode();
            if (response == 301 || response == 302 || response == 303
                    || response == 307 || response == 308) {
                String redirect = c.getHeaderField("Location");
                c.disconnect();
                if (redirect == null) throw new IOException("Missing modloader redirect.");
                current = new URL(current, redirect);
                continue;
            }
            if (response != 200) {
                c.disconnect();
                throw new IOException("Official modloader HTTP " + response);
            }
            return c;
        }
        throw new IOException("Too many modloader redirects.");
    }

    private static byte[] readBytes(String url, long limit, AtomicBoolean stop)
            throws Exception {
        HttpURLConnection c = connect(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) != -1) {
                check(stop);
                if (out.size() + (long)n > limit)
                    throw new IOException("Modloader metadata exceeds size limit.");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String readString(String url, long limit, AtomicBoolean stop)
            throws Exception {
        return new String(readBytes(url, limit, stop), StandardCharsets.UTF_8);
    }
}
