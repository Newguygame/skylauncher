package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

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
import java.security.MessageDigest;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Modpack installation for the in-process Minecraft engine.
 *
 * No other Android launcher is accessed. GitHub downloads use the official
 * Fathom Studios client repository. CurseForge downloads use a user-configured
 * HTTPS Core API relay with the API key held on the relay server, not in APK.
 * The pack's files/overrides are staged before writing into the game directory.
 */
final class SkyPackInstaller {
    static final String FATHOM_REPOSITORY = "Newguygame/fathom-studios-mc-pack";
    static final String FATHOM_DOWNLOAD =
            "https://github.com/" + FATHOM_REPOSITORY + "/archive/refs/heads/main.zip";
    static final String RELAY_SETTING = "curseforge_relay_url";
    static final String DESKTOP_VPS = "https://185.83.154.171:25568";
    private static volatile SSLSocketFactory pinnedVpsTls;
    static final long MAX_ZIP_BYTES = 1_800_000_000L;
    static final long MAX_SINGLE_FILE = 350_000_000L;
    static final long MAX_TOTAL_FILES = 4_000_000_000L;
    static final int MAX_ENTRIES = 15000;
    private static final int MAX_API = 3_000_000;
    private static final String USER_AGENT = "SkyLauncherAndroid/0.8 (Fathom Studios)";

    interface Progress {
        void update(String message);
        void completed(String message);
        void failed(String message);
        boolean cancelled();
    }

    static final class CurseProject {
        final long id;
        final String name, summary;
        CurseProject(long id, String name, String summary) {
            this.id = id; this.name = name; this.summary = summary;
        }
    }

    private SkyPackInstaller() { }

    static String relay(Context context) {
        String saved = context.getSharedPreferences("skylauncher_android", Context.MODE_PRIVATE)
                .getString(RELAY_SETTING, DESKTOP_VPS).trim();
        // Android 0.8 saved an empty relay value on some devices.
        // Continue using the actual desktop VPS after an in-place update.
        return saved.isEmpty() ? DESKTOP_VPS : saved;
    }

    static void saveRelay(Context context, String raw) {
        String value = raw.trim();
        if (value.isEmpty()) value = DESKTOP_VPS;
        if (!value.isEmpty()) {
            try {
                URL url = new URL(value);
                if (!"https".equalsIgnoreCase(url.getProtocol())
                        || url.getHost() == null || url.getHost().isEmpty()
                        || url.getUserInfo() != null || url.getQuery() != null
                        || url.getRef() != null) throw new Exception();
                value = url.toExternalForm().replaceAll("/+$", "");
            } catch (Exception e) {
                throw new IllegalArgumentException("Enter the HTTPS URL of your CurseForge API relay.");
            }
        }
        context.getSharedPreferences("skylauncher_android", Context.MODE_PRIVATE)
                .edit().putString(RELAY_SETTING, value).apply();
    }

    static String api(Context ctx, String path) throws Exception {
        String base = relay(ctx);
        return base + path;
    }

    /** Dedicated certificate trust, only for the same private VPS used by desktop. */
    private static SSLSocketFactory vpsTls(Context ctx) throws Exception {
        if (pinnedVpsTls != null) return pinnedVpsTls;
        synchronized (SkyPackInstaller.class) {
            if (pinnedVpsTls != null) return pinnedVpsTls;
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Certificate pinned;
            try (InputStream input = ctx.getResources().openRawResource(
                    net.kdt.pojavlaunch.R.raw.skylauncher_bridge_cert)) {
                pinned = factory.generateCertificate(input);
            }
            KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
            trusted.load(null, null);
            trusted.setCertificateEntry("skylauncher-desktop-vps", pinned);
            TrustManagerFactory managers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trusted);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, managers.getTrustManagers(), null);
            pinnedVpsTls = tls.getSocketFactory();
            return pinnedVpsTls;
        }
    }

    private static boolean sameOrigin(URL a, URL b) {
        int portA = a.getPort() == -1 ? a.getDefaultPort() : a.getPort();
        int portB = b.getPort() == -1 ? b.getDefaultPort() : b.getPort();
        return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && portA == portB;
    }

    private static boolean desktopVps(URL url) throws Exception {
        return sameOrigin(url, new URL(DESKTOP_VPS));
    }

    private static HttpURLConnection connect(Context ctx, String source, boolean github)
            throws Exception {
        URL url = new URL(source);
        URL configuredVps = new URL(relay(ctx));
        boolean relayRequest = sameOrigin(configuredVps, url);
        for (int redirects = 0; redirects < 7; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol()))
                throw new IOException("Only HTTPS downloads are allowed.");
            if (url.getUserInfo() != null)
                throw new IOException("Credentials in download URLs are not allowed.");
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (github && !(host.equals("github.com") || host.equals("codeload.github.com")))
                throw new IOException("Fathom pack download redirected outside GitHub.");
            if (relayRequest && !sameOrigin(configuredVps, url))
                throw new IOException("CurseForge relay redirected outside the selected VPS.");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            if (desktopVps(url)) {
                // Android must not disable TLS verification or accept arbitrary
                // certificates. Use the same pinned public certificate as desktop.
                ((HttpsURLConnection) c).setSSLSocketFactory(vpsTls(ctx));
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(25000);
            c.setReadTimeout(45000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("Missing download redirect.");
                url = new URL(url, location);
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("Download returned HTTP " + code + ".");
            }
            return c;
        }
        throw new IOException("Too many download redirects.");
    }

    private static byte[] readApi(Context ctx, String endpoint) throws Exception {
        HttpURLConnection c = connect(ctx, endpoint, false);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_API) throw new IOException("API response is too large.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject jsonApi(Context ctx, String path) throws Exception {
        return new JSONObject(new String(readApi(ctx, api(ctx, path)), "UTF-8"));
    }

    static List<CurseProject> searchCurseForge(Context ctx, String query) throws Exception {
        // The desktop VPS uses a simplified public API: /v1/packs/search.
        JSONObject result = jsonApi(ctx, "/v1/packs/search?q="
                + URLEncoder.encode(query.trim(), "UTF-8"));
        JSONArray data = result.optJSONArray("data");
        if (data == null) throw new IOException("The desktop VPS returned no modpack results.");
        List<CurseProject> items = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject p = data.optJSONObject(i);
            if (p == null) continue;
            long id = p.optLong("id", 0);
            if (id > 0) items.add(new CurseProject(id, p.optString("name"),
                    p.optString("summary")));
        }
        return items;
    }

    static void installCurseProject(Activity ctx, long projectId, Progress progress)
            throws Exception {
        if (projectId <= 0) throw new IOException("Invalid CurseForge modpack ID.");
        String expectedProfileId = ProfileStore.active(ctx).id;
        progress.update("Finding CurseForge pack files on the SkyLauncher VPS...");
        JSONObject response = jsonApi(ctx, "/v1/packs/" + projectId + "/files");
        JSONArray files = response.optJSONArray("data");
        if (files == null || files.length() == 0)
            throw new IOException("No downloadable CurseForge pack archives were found.");
        JSONObject chosen = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null || !item.optBoolean("downloadable", true)) continue;
            String name = item.optString("name").toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) { chosen = item; break; }
        }
        if (chosen == null) throw new IOException(
                "The author has not made a CurseForge modpack ZIP downloadable.");
        long fileId = chosen.optLong("id", 0);
        if (fileId <= 0) throw new IOException("Invalid CurseForge pack file ID.");
        String url = api(ctx, "/v1/download/" + projectId + "/" + fileId);
        File zip = new File(ctx.getCacheDir(), "sky-cf-pack-" + projectId + ".zip");
        try {
            progress.update("Downloading CurseForge pack through the same VPS as desktop...");
            download(ctx, url, zip, 512L * 1024L * 1024L, progress, false);
            verifySha1(chosen.optString("sha1"), zip);
            installCurseForgeZip(ctx, zip, progress, expectedProfileId);
        } finally {
            zip.delete();
        }
    }

    static void installCurseForgeUri(Activity ctx, Uri selected, Progress progress)
            throws Exception {
        String expectedProfileId = ProfileStore.active(ctx).id;
        File zip = new File(ctx.getCacheDir(), "sky-cf-import.zip");
        try (InputStream in = ctx.getContentResolver().openInputStream(selected)) {
            if (in == null) throw new IOException("Cannot open the selected modpack ZIP.");
            copyToFile(in, zip, MAX_ZIP_BYTES, progress);
            installCurseForgeZip(ctx, zip, progress, expectedProfileId);
        } finally {
            zip.delete();
        }
    }

    private static void installCurseForgeZip(Activity ctx, File archive, Progress progress,
                                            String expectedProfileId)
            throws Exception {
        File stage = new File(ctx.getCacheDir(), "sky-pack-stage");
        deleteTree(stage);
        if (!stage.mkdirs()) throw new IOException("Unable to create install staging directory.");
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null) throw new IOException(
                    "Not a CurseForge export: manifest.json is missing.");
            if (manifestEntry.getSize() > 1_000_000L) throw new IOException("Manifest is too large.");
            byte[] raw = readLimited(zip.getInputStream(manifestEntry), 1_000_000L);
            JSONObject manifest = new JSONObject(new String(raw, "UTF-8"));
            if (!"minecraftModpack".equals(manifest.optString("manifestType")))
                throw new IOException("This ZIP is not a Minecraft CurseForge modpack.");
            JSONObject mc = manifest.getJSONObject("minecraft");
            String gameVersion = mc.getString("version");
            JSONArray loaders = mc.optJSONArray("modLoaders");
            String loader = "Vanilla";
            if (loaders != null && loaders.length() > 0) {
                JSONObject primary = loaders.getJSONObject(0);
                for (int i = 0; i < loaders.length(); i++) {
                    JSONObject item = loaders.getJSONObject(i);
                    if (item.optBoolean("primary", false)) { primary = item; break; }
                }
                String id = primary.optString("id").toLowerCase(Locale.ROOT);
                if (id.startsWith("neoforge-")) loader = "NeoForge";
                else if (id.startsWith("forge-")) loader = "Forge";
                else if (id.startsWith("fabric-")) loader = "Fabric";
                else if (id.startsWith("quilt-")) loader = "Quilt";
                else throw new IOException("Unsupported CurseForge modloader: " + id);
            }
            if (!gameVersion.matches("[0-9A-Za-z._-]{1,60}"))
                throw new IOException("Invalid Minecraft version in modpack.");
            JSONArray files = manifest.optJSONArray("files");
            if (files == null) throw new IOException("CurseForge manifest has no file list.");
            if (files.length() > 1500) throw new IOException("Modpack contains too many mods.");
            int requiredCount = 0;
            for (int i = 0; i < files.length(); i++) {
                if (progress.cancelled()) throw new IOException("Installation cancelled.");
                JSONObject f = files.getJSONObject(i);
                if (!f.optBoolean("required", true)) continue;
                long projectId = f.optLong("projectID"), fileId = f.optLong("fileID");
                if (projectId <= 0 || fileId <= 0) throw new IOException(
                        "Invalid CurseForge project/file ID.");
                // Desktop VPS is configured by default; the API key never enters the APK.
                JSONObject meta = jsonApi(ctx, "/v1/mods/" + projectId + "/files/" + fileId);
                String filename = meta.optString("name");
                if (!filename.matches("[A-Za-z0-9_.+() -]{1,180}\\.jar"))
                    throw new IOException("Unsafe or unsupported mod filename: " + filename);
                File target = safeFile(stage, "mods/" + filename);
                if (target.exists()) throw new IOException("Duplicate mod filename: " + filename);
                String url = api(ctx, "/v1/download/" + projectId + "/" + fileId);
                progress.update("Downloading mod " + (i + 1) + "/" + files.length()
                        + ": " + filename);
                download(ctx, url, target, MAX_SINGLE_FILE, progress, false);
                verifyCurseHashes(meta, target);
                requiredCount++;
            }
            String overrides = manifest.optString("overrides", "overrides");
            if (!"overrides".equals(overrides) && !"overrides/".equals(overrides))
                throw new IOException("Only the standard CurseForge overrides folder is supported.");
            extractAllowed(zip, stage, "overrides/", true, progress);
            ProfileStore.Profile selected = ProfileStore.active(ctx);
            if (!gameVersion.equals(selected.version)
                    || !loader.equals(ProfileStore.LOADERS[selected.loader])) {
                progress.update("Applying pack profile: Minecraft " + gameVersion
                        + " / " + loader + "...");
            }
            commitStage(ctx, stage, progress, expectedProfileId);
            if (!selected.id.equals(expectedProfileId))
                throw new IOException("The selected profile changed before metadata update.");
            selected.version = gameVersion;
            selected.loader = loaderIndex(loader);
            ProfileStore.upsert(ctx, selected);
            progress.completed("CurseForge pack installed: " + requiredCount
                    + " mods plus overrides. Select Play; SkyLauncher will set up "
                    + loader + " automatically if needed.");
        } finally {
            deleteTree(stage);
        }
    }

    static void installFathom(Activity ctx, Progress progress) throws Exception {
        String expectedProfileId = ProfileStore.active(ctx).id;
        File archive = new File(ctx.getCacheDir(), "sky-fathom-client-pack.zip");
        File stage = new File(ctx.getCacheDir(), "sky-pack-stage");
        deleteTree(stage);
        if (!stage.mkdirs()) throw new IOException("Unable to create install staging directory.");
        try {
            progress.update("Downloading the Fathom Studios client pack from GitHub...");
            download(ctx, FATHOM_DOWNLOAD, archive, MAX_ZIP_BYTES, progress, true);
            try (ZipFile zip = new ZipFile(archive)) {
                extractAllowed(zip, stage, null, false, progress);
            }
            File mods = new File(stage, "mods");
            File[] jars = mods.listFiles((folder, name) -> name.endsWith(".jar"));
            if (jars == null || jars.length == 0)
                throw new IOException("The GitHub pack archive contains no client mod JARs.");
            progress.update("Installing " + jars.length
                    + " client mods and configuration files to your Minecraft profile...");
            commitStage(ctx, stage, progress, expectedProfileId);
            ProfileStore.Profile selected = ProfileStore.active(ctx);
            if (!selected.id.equals(expectedProfileId))
                throw new IOException("The selected profile changed before metadata update.");
            selected.version = "1.21.1";
            selected.loader = loaderIndex("NeoForge");
            ProfileStore.upsert(ctx, selected);
            progress.completed("Fathom Studios Minecraft pack installed (" + jars.length
                    + " client mods). Profile set to Minecraft 1.21.1 / NeoForge. "
                    + "Press Play to install the required NeoForge loader if missing.");
        } finally {
            archive.delete();
            deleteTree(stage);
        }
    }

    private static int loaderIndex(String name) throws IOException {
        for (int i = 0; i < ProfileStore.LOADERS.length; i++)
            if (name.equals(ProfileStore.LOADERS[i])) return i;
        throw new IOException("Unsupported loader: " + name);
    }

    private static File gameDir(Context ctx, String expectedProfileId) throws Exception {
        ProfileStore.Profile active = ProfileStore.active(ctx);
        if (!active.id.equals(expectedProfileId))
            throw new IOException("The active Minecraft profile changed during download. "
                    + "The pack was not installed to another profile.");
        return SkyEngineProfileBridge.gameDirectory(ctx, active);
    }

    private static void commitStage(Context ctx, File stage, Progress progress,
                                    String expectedProfileId) throws Exception {
        File root = gameDir(ctx, expectedProfileId);
        List<File> files = new ArrayList<>();
        listFiles(stage, files);
        long totalSize = 0;
        for (File f : files) totalSize += f.length();
        if (root.getUsableSpace() < totalSize + 100_000_000L)
            throw new IOException("Not enough free storage to install this modpack.");
        File backups = new File(ctx.getFilesDir(), "modpack-backups/"
                + System.currentTimeMillis());
        int n = 0;
        for (File source : files) {
            if (progress.cancelled()) throw new IOException("Installation cancelled.");
            if (!ProfileStore.active(ctx).id.equals(expectedProfileId))
                throw new IOException("Profile switched during installation; stopping to protect other worlds.");
            String relative = stage.toPath().relativize(source.toPath()).toString()
                    .replace(File.separatorChar, '/');
            File dest = safeFile(root, relative);
            if (!dest.getParentFile().isDirectory() && !dest.getParentFile().mkdirs())
                throw new IOException("Cannot create Minecraft directory: " + dest.getParent());
            if (dest.exists()) {
                File previous = safeFile(backups, relative);
                if (!previous.getParentFile().isDirectory() && !previous.getParentFile().mkdirs())
                    throw new IOException("Unable to create modpack backup.");
                copyFile(dest, previous);
            }
            copyFile(source, dest);
            if (++n % 20 == 0) progress.update("Installing pack files: " + n + "/" + files.size());
        }
    }

    private static void listFiles(File root, List<File> files) throws Exception {
        File[] entries = root.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) listFiles(f, files);
            else if (f.isFile()) files.add(f);
        }
    }

    private static void extractAllowed(ZipFile archive, File stage, String prefix,
                                       boolean stripPrefix, Progress progress) throws Exception {
        java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
        long total = 0;
        int count = 0;
        String archiveRoot = null;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > MAX_ENTRIES) throw new IOException("Archive has too many files.");
            if (progress.cancelled()) throw new IOException("Installation cancelled.");
            String name = entry.getName().replace('\\', '/');
            if (name.startsWith("/") || name.contains("../") || name.contains("/.."))
                throw new IOException("Unsafe file path in archive.");
            if (entry.isDirectory()) continue;
            String relative = name;
            if (prefix != null) {
                if (!name.startsWith(prefix)) continue;
                relative = stripPrefix ? name.substring(prefix.length()) : name;
            } else {
                if (archiveRoot == null) {
                    int slash = name.indexOf('/');
                    if (slash <= 0) throw new IOException("Unexpected Fathom ZIP archive layout.");
                    archiveRoot = name.substring(0, slash + 1);
                }
                if (!name.startsWith(archiveRoot)) continue;
                relative = name.substring(archiveRoot.length());
            }
            if (!allowedFile(relative)) continue;
            if (entry.getSize() > MAX_SINGLE_FILE)
                throw new IOException("File is too large: " + relative);
            File out = safeFile(stage, relative);
            if (out.exists()) throw new IOException("Duplicate file in modpack: " + relative);
            try (InputStream in = archive.getInputStream(entry)) {
                copyToFile(in, out, MAX_SINGLE_FILE, progress);
            }
            total += out.length();
            if (total > MAX_TOTAL_FILES)
                throw new IOException("Extracted modpack is too large.");
        }
    }

    private static boolean allowedFile(String relative) {
        String p = relative.replace('\\', '/');
        if (p.isEmpty() || p.startsWith("/") || p.startsWith(".")
                || p.contains("..") || p.contains("//") || p.contains(":")) return false;
        int slash = p.indexOf('/');
        if (slash <= 0) return false;
        String folder = p.substring(0, slash).toLowerCase(Locale.ROOT);
        if (!(folder.equals("mods") || folder.equals("config")
                || folder.equals("defaultconfigs") || folder.equals("kubejs")
                || folder.equals("scripts") || folder.equals("resourcepacks")
                || folder.equals("shaderpacks"))) return false;
        String leaf = p.substring(slash + 1);
        if (leaf.isEmpty() || leaf.startsWith(".") || leaf.contains("/."))
            return false;
        String lower = leaf.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")
                || lower.endsWith(".sh") || lower.endsWith(".so")
                || lower.endsWith(".dll") || lower.endsWith(".class")) return false;
        return true;
    }

    private static File safeFile(File root, String relative) throws Exception {
        File resolved = new File(root, relative).getCanonicalFile();
        String base = root.getCanonicalPath() + File.separator;
        if (!resolved.getPath().startsWith(base))
            throw new IOException("Modpack file escapes the target directory.");
        return resolved;
    }

    private static void verifyCurseHashes(JSONObject meta, File file) throws Exception {
        if (!meta.optString("sha1", "").isEmpty()) {
            verifySha1(meta.optString("sha1"), file);
            return;
        }
        JSONArray hashes = meta.optJSONArray("hashes");
        if (hashes == null) return;
        for (int i = 0; i < hashes.length(); i++) {
            JSONObject h = hashes.optJSONObject(i);
            if (h == null) continue;
            int algo = h.optInt("algo");
            if (algo != 1 && algo != 2) continue;
            String expected = h.optString("value");
            if (expected.isEmpty()) continue;
            MessageDigest digest = MessageDigest.getInstance(algo == 1 ? "SHA-1" : "MD5");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            StringBuilder actual = new StringBuilder();
            for (byte b : digest.digest()) actual.append(String.format(Locale.ROOT,
                    "%02x", b & 0xff));
            if (!expected.equalsIgnoreCase(actual.toString()))
                throw new IOException("CurseForge file hash mismatch: " + file.getName());
            return;
        }
    }

    private static void verifySha1(String expected, File file) throws Exception {
        if (expected == null || expected.isEmpty()) return;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] data = new byte[65536];
            int size;
            while ((size = in.read(data)) != -1) sha1.update(data, 0, size);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha1.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        if (!expected.equalsIgnoreCase(hex.toString()))
            throw new IOException("CurseForge file failed SHA-1 verification: " + file.getName());
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (out.size() + n > limit) throw new IOException("File exceeds size limit.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void download(Context ctx, String url, File target, long limit,
                                 Progress progress, boolean github) throws Exception {
        HttpURLConnection c = connect(ctx, url, github);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            copyToFile(in, target, limit, progress);
        } finally {
            c.disconnect();
        }
    }

    private static void copyToFile(InputStream in, File out, long limit, Progress progress)
            throws Exception {
        File parent = out.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Unable to create staging directory.");
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (progress.cancelled()) throw new IOException("Installation cancelled.");
                total += n;
                if (total > limit) throw new IOException("Download or file exceeds safe size limit.");
                output.write(buf, 0, n);
            }
        } catch (Exception e) {
            out.delete();
            throw e;
        }
    }

    private static void copyFile(File source, File target) throws Exception {
        if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
            throw new IOException("Cannot create file directory.");
        File temporary = new File(target.getParentFile(), target.getName() + ".sky-pending");
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(temporary))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (Exception e) {
            temporary.delete();
            throw e;
        }
        if (target.exists() && !target.delete()) throw new IOException(
                "Could not replace " + target.getName());
        if (!temporary.renameTo(target)) throw new IOException(
                "Could not finish writing " + target.getName());
    }

    private static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        dir.delete();
    }
}
