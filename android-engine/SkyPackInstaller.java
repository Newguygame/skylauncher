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
            if (name.endsWith(".zip") && (chosen == null
                    || item.optLong("id") > chosen.optLong("id"))) chosen = item;
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
            installCurseForgeZip(ctx, zip, progress, expectedProfileId,
                    "curseforge:" + projectId, String.valueOf(fileId));
        } finally {
            zip.delete();
        }
    }


    /**
     * Local ZIP import independent of CurseForge, Modrinth and pack update records.
     * Scans every directory and (one level of) nested ZIP archives rather than
     * looking only for the literal mods/ folder. Keeps imported mods inside
     * the selected SkyLauncher engine profile.
     */
    private static final class LocalJarScan {
        final List<File> files = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final Set<String> seenNames = new HashSet<>();
        int entries, jarsFound, duplicateNames, invalidNames, nestedZips, nestedFailures;
        int manifestReferences;
        long extractedBytes;
    }

    private static void scanLocalJarZip(ZipFile archive, File stage, Progress progress,
                                        LocalJarScan scan, int depth) throws Exception {
        java.util.Enumeration<? extends ZipEntry> zipEntries = archive.entries();
        while (zipEntries.hasMoreElements()) {
            if (progress.cancelled()) throw new IOException("ZIP import cancelled.");
            ZipEntry entry = zipEntries.nextElement();
            if (++scan.entries > MAX_ENTRIES)
                throw new IOException("ZIP contains too many entries (including nested ZIPs).");
            if (entry.isDirectory()) continue;
            String raw = entry.getName().replace('\\', '/');
            if (raw.startsWith("/") || raw.contains(":") || raw.contains("//")) {
                throw new IOException("Unsafe ZIP path: " + raw);
            }
            String[] segments = raw.split("/", -1);
            for (String part : segments) {
                if (part.isEmpty() || part.equals(".") || part.equals(".."))
                    throw new IOException("Unsafe ZIP path: " + raw);
            }
            String filename = segments[segments.length - 1];
            String lower = filename.toLowerCase(Locale.ROOT);

            // Manifest-only CurseForge packs store references, not the actual JARs.
            if (lower.equals("manifest.json") && depth == 0
                    && entry.getSize() <= 1_000_000L) {
                try (InputStream input = archive.getInputStream(entry)) {
                    JSONObject json = new JSONObject(new String(
                            readLimited(input, 1_000_000), "UTF-8"));
                    if ("minecraftModpack".equals(json.optString("manifestType"))) {
                        JSONArray dependencies = json.optJSONArray("files");
                        if (dependencies != null)
                            scan.manifestReferences += dependencies.length();
                    }
                } catch (Exception ignored) {
                    // A non-CurseForge manifest must not block local mod import.
                }
            }

            if (lower.endsWith(".jar")) {
                scan.jarsFound++;
                // Android filenames may contain brackets, Unicode, etc.
                // Do not reject otherwise-valid .jar names just for punctuation.
                boolean valid = filename.length() <= 225 && filename.length() > 4
                        && !filename.startsWith(".") && !filename.equals("..");
                for (int i = 0; valid && i < filename.length(); i++) {
                    char c = filename.charAt(i);
                    if (Character.isISOControl(c) || c == '/' || c == '\\')
                        valid = false;
                }
                if (!valid) {
                    scan.invalidNames++;
                    continue;
                }
                String key = filename.toLowerCase(Locale.ROOT);
                if (!scan.seenNames.add(key)) {
                    // Two different folders may contain the same mod filename.
                    // Never overwrite one silently or create renamed duplicate
                    // mod jars (which can lead to duplicate mod IDs).
                    scan.duplicateNames++;
                    continue;
                }
                if (entry.getSize() > MAX_SINGLE_FILE)
                    throw new IOException("JAR exceeds the size limit: " + filename);
                File extracted = new File(stage, "import-" + scan.files.size() + ".jar");
                try (InputStream input = archive.getInputStream(entry)) {
                    copyToFile(input, extracted, MAX_SINGLE_FILE, progress);
                }
                scan.extractedBytes += extracted.length();
                if (scan.extractedBytes > MAX_TOTAL_FILES)
                    throw new IOException("ZIP contains too much JAR data.");
                scan.files.add(extracted);
                scan.names.add(filename);
                if (scan.files.size() % 20 == 0)
                    progress.update("Found " + scan.jarsFound + " JARs; prepared "
                            + scan.files.size() + " for import...");
            } else if (depth == 0 && lower.endsWith(".zip")
                    && scan.nestedZips < 20
                    && entry.getSize() <= MAX_SINGLE_FILE
                    && !raw.toLowerCase(Locale.ROOT).contains("resourcepacks/")
                    && !raw.toLowerCase(Locale.ROOT).contains("shaderpacks/")) {
                scan.nestedZips++;
                File nested = File.createTempFile("sky-nested-", ".zip", stage);
                try {
                    try (InputStream input = archive.getInputStream(entry)) {
                        copyToFile(input, nested, MAX_SINGLE_FILE, progress);
                    }
                    try (ZipFile inner = new ZipFile(nested)) {
                        scanLocalJarZip(inner, stage, progress, scan, depth + 1);
                    }
                } catch (IOException invalidArchive) {
                    // Unreadable nested ZIPs are reported, not silently treated
                    // as having zero mods.
                    if (progress.cancelled()) throw invalidArchive;
                    scan.nestedFailures++;
                } finally {
                    nested.delete();
                }
            }
        }
    }

    static void importLocalModsZip(Activity ctx, Uri selected,
                                   String expectedProfileId, Progress progress)
            throws Exception {
        if (selected == null) throw new IOException("Select a ZIP file first.");
        if (!expectedProfileId.equals(ProfileStore.active(ctx).id))
            throw new IOException("Active profile changed. Select the ZIP again.");
        File archive = File.createTempFile("sky-local-mods-", ".zip", ctx.getCacheDir());
        File stage = new File(ctx.getCacheDir(),
                "sky-local-mods-stage-" + java.util.UUID.randomUUID());
        if (!stage.mkdirs()) throw new IOException("Cannot create ZIP staging folder.");
        List<File> installed = new ArrayList<>();
        int alreadyInstalled = 0;
        LocalJarScan scan = new LocalJarScan();
        try {
            progress.update("Scanning ZIP for JARs in every folder...");
            try (InputStream input = ctx.getContentResolver().openInputStream(selected)) {
                if (input == null) throw new IOException("Cannot open the selected ZIP.");
                copyToFile(input, archive, MAX_ZIP_BYTES, progress);
            }
            try (ZipFile zip = new ZipFile(archive)) {
                scanLocalJarZip(zip, stage, progress, scan, 0);
            }
            if (scan.files.isEmpty()) {
                throw new IOException("Found " + scan.jarsFound + " JARs inside ZIP; "
                        + scan.manifestReferences + " CurseForge manifest references. "
                        + "No importable JARs. If this is a manifest-only CurseForge "
                        + "export, use Import CurseForge modpack ZIP instead.");
            }
            if (!expectedProfileId.equals(ProfileStore.active(ctx).id))
                throw new IOException("Active profile changed before importing mods.");
            File root = gameDir(ctx, expectedProfileId);
            File mods = new File(root, "mods").getCanonicalFile();
            if (!mods.toPath().startsWith(root.getCanonicalFile().toPath())
                    || java.nio.file.Files.isSymbolicLink(mods.toPath()))
                throw new IOException("Unsafe profile mods directory.");
            if (!mods.isDirectory() && !mods.mkdirs())
                throw new IOException("Could not create Minecraft mods folder.");
            if (mods.getUsableSpace() < scan.extractedBytes + 100_000_000L)
                throw new IOException("Not enough free storage for mods.");
            Set<String> existingNames = new HashSet<>();
            File[] existing = mods.listFiles();
            if (existing == null) throw new IOException("Cannot list mods directory.");
            for (File f : existing)
                existingNames.add(f.getName().toLowerCase(Locale.ROOT));
            try {
                for (int index = 0; index < scan.files.size(); index++) {
                    if (progress.cancelled()) throw new IOException("ZIP import cancelled.");
                    if (!expectedProfileId.equals(ProfileStore.active(ctx).id))
                        throw new IOException("Profile switched during ZIP import.");
                    String filename = scan.names.get(index);
                    if (existingNames.contains(filename.toLowerCase(Locale.ROOT))) {
                        alreadyInstalled++;
                        continue;
                    }
                    File target = new File(mods, filename);
                    File pending = File.createTempFile(".sky-import-", ".part", mods);
                    try {
                        java.nio.file.Files.copy(scan.files.get(index).toPath(),
                                pending.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        if (progress.cancelled()) throw new IOException("ZIP import cancelled.");
                        if (!expectedProfileId.equals(ProfileStore.active(ctx).id))
                            throw new IOException("Profile switched during ZIP import.");
                        java.nio.file.Files.move(pending.toPath(), target.toPath());
                        installed.add(target);
                        existingNames.add(filename.toLowerCase(Locale.ROOT));
                        if (installed.size() % 20 == 0)
                            progress.update("Installed " + installed.size()
                                    + " of " + scan.files.size() + " prepared JARs...");
                    } finally {
                        pending.delete();
                    }
                }
            } catch (Exception failure) {
                for (int i = installed.size() - 1; i >= 0; i--) {
                    if (!installed.get(i).delete())
                        failure.addSuppressed(new IOException(
                                "Could not undo: " + installed.get(i).getName()));
                }
                throw failure;
            }
            String summary = "ZIP JARs found: " + scan.jarsFound
                    + " | imported: " + installed.size()
                    + " | already installed: " + alreadyInstalled
                    + " | duplicate names: " + scan.duplicateNames
                    + " | invalid names: " + scan.invalidNames
                    + " | nested ZIP errors: " + scan.nestedFailures
                    + " | CurseForge manifest references (not included): "
                    + scan.manifestReferences;
            LogBook.write(ctx, "Local ZIP import: " + summary);
            progress.completed(summary + ". Files installed only into profile "
                    + ProfileStore.active(ctx).name + ".");
        } finally {
            archive.delete();
            deleteTree(stage);
        }
    }

    static void installCurseForgeUri(Activity ctx, Uri selected, Progress progress)
            throws Exception {
        String expectedProfileId = ProfileStore.active(ctx).id;
        File zip = new File(ctx.getCacheDir(), "sky-cf-import.zip");
        try (InputStream in = ctx.getContentResolver().openInputStream(selected)) {
            if (in == null) throw new IOException("Cannot open the selected modpack ZIP.");
            copyToFile(in, zip, MAX_ZIP_BYTES, progress);
            installCurseForgeZip(ctx, zip, progress, expectedProfileId,
                    "curseforge-import", fileHash(zip, "SHA-256"));
        } finally {
            zip.delete();
        }
    }

    private static void installCurseForgeZip(Activity ctx, File archive, Progress progress,
                                            String expectedProfileId,
                                            String packSource, String packVersion)
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
            String pinnedLoaderVersion = "";
            if (loaders != null && loaders.length() > 0) {
                JSONObject primary = loaders.getJSONObject(0);
                for (int i = 0; i < loaders.length(); i++) {
                    JSONObject item = loaders.getJSONObject(i);
                    if (item.optBoolean("primary", false)) { primary = item; break; }
                }
                String id = primary.optString("id").toLowerCase(Locale.ROOT);
                int separator = id.indexOf('-');
                if (separator >= 0) pinnedLoaderVersion = id.substring(separator + 1);
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
            SkyPackUpdateStore.apply(ctx, stage, progress, expectedProfileId,
                    packSource, packVersion, manifest.optString("name", "CurseForge pack"));
            if (!selected.id.equals(expectedProfileId))
                throw new IOException("The selected profile changed before metadata update.");
            selected.version = gameVersion;
            selected.loader = loaderIndex(loader);
            selected.loaderVersion = pinnedLoaderVersion;
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
            SkyPackUpdateStore.apply(ctx, stage, progress, expectedProfileId,
                    "fathom", latestFathomCommit(ctx), "Fathom Studios MC Pack");
            ProfileStore.Profile selected = ProfileStore.active(ctx);
            if (!selected.id.equals(expectedProfileId))
                throw new IOException("The selected profile changed before metadata update.");
            selected.version = "1.21.1";
            selected.loader = loaderIndex("NeoForge");
            selected.loaderVersion = "";
            ProfileStore.upsert(ctx, selected);
            progress.completed("Fathom Studios Minecraft pack installed (" + jars.length
                    + " client mods). Profile set to Minecraft 1.21.1 / NeoForge. "
                    + "Press Play to install the required NeoForge loader if missing.");
        } finally {
            archive.delete();
            deleteTree(stage);
        }
    }

    /** Repository head SHA changes whenever Fathom publishes a new pack revision. */
    private static String latestFathomCommit(Context ctx) throws Exception {
        JSONObject result = new JSONObject(new String(readApi(ctx,
                "https://api.github.com/repos/" + FATHOM_REPOSITORY + "/commits/main"),
                "UTF-8"));
        String sha = result.optString("sha");
        if (!sha.matches("[0-9a-fA-F]{40}"))
            throw new IOException("Could not verify Fathom pack revision.");
        return sha;
    }

    private static String fileHash(File file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[65536];
            int count;
            while ((count = in.read(buf)) != -1) digest.update(buf, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private static JSONObject latestModrinthVersion(Context ctx, String projectId)
            throws Exception {
        if (!projectId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IOException("Invalid Modrinth project ID.");
        JSONArray versions = new JSONArray(new String(readApi(ctx,
                "https://api.modrinth.com/v2/project/" + projectId
                        + "/version?include_changelog=false"), "UTF-8"));
        for (int i = 0; i < versions.length(); i++) {
            JSONObject v = versions.getJSONObject(i);
            JSONArray files = v.optJSONArray("files");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                String filename = files.getJSONObject(j).optString("filename");
                if (filename.endsWith(".mrpack")) return v;
            }
        }
        throw new IOException("No downloadable Modrinth .mrpack release exists.");
    }

    static String remotePackVersion(Context ctx, String source) throws Exception {
        if ("fathom".equals(source)) return latestFathomCommit(ctx);
        if (source.startsWith("modrinth:")) {
            return latestModrinthVersion(ctx, source.substring(9)).getString("id");
        }
        if (source.startsWith("curseforge:")) {
            long projectId = Long.parseLong(source.substring(11));
            JSONObject response = jsonApi(ctx, "/v1/packs/" + projectId + "/files");
            JSONArray files = response.optJSONArray("data");
            long best = 0;
            if (files != null) for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file != null && file.optString("name").toLowerCase(Locale.ROOT)
                        .endsWith(".zip")) best = Math.max(best, file.optLong("id"));
            }
            if (best <= 0) throw new IOException("No downloadable CurseForge pack update.");
            return String.valueOf(best);
        }
        throw new IOException("This imported pack has no trackable online source.");
    }

    static void updatePack(Activity ctx, String source, Progress progress) throws Exception {
        if ("fathom".equals(source)) installFathom(ctx, progress);
        else if (source.startsWith("modrinth:"))
            installModrinthProject(ctx, source.substring(9), progress);
        else if (source.startsWith("curseforge:"))
            installCurseProject(ctx, Long.parseLong(source.substring(11)), progress);
        else throw new IOException("Imported packs have no automatic source updates.");
    }

    private static boolean allowedModrinthDownload(String raw) {
        try {
            URL url = new URL(raw);
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || url.getUserInfo() != null || url.getRef() != null) return false;
            String host = url.getHost().toLowerCase(Locale.ROOT);
            return host.equals("cdn.modrinth.com")
                    || host.equals("github.com")
                    || host.equals("raw.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com");
        } catch (Exception invalid) {
            return false;
        }
    }

    private static void downloadModrinthFile(Context ctx, String url, File target,
                                             Progress progress) throws Exception {
        if (!allowedModrinthDownload(url))
            throw new IOException("Unsupported modpack file host: " + url);
        // Do not allow a trusted URL to redirect to an arbitrary host.
        URL current = new URL(url);
        for (int redirects = 0; redirects < 7; redirects++) {
            if (!allowedModrinthDownload(current.toExternalForm()))
                throw new IOException("Untrusted modpack download redirect.");
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(25000);
            c.setReadTimeout(45000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303
                        || code == 307 || code == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing download redirect.");
                    current = new URL(current, location);
                    continue;
                }
                if (code != 200) throw new IOException("Mod download returned HTTP " + code);
                try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                    copyToFile(in, target, MAX_SINGLE_FILE, progress);
                }
                return;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many mod download redirects.");
    }

    static void installModrinthProject(Activity ctx, String projectId, Progress progress)
            throws Exception {
        String expectedProfileId = ProfileStore.active(ctx).id;
        progress.update("Checking Modrinth for the newest pack...");
        JSONObject release = latestModrinthVersion(ctx, projectId);
        JSONArray releaseFiles = release.getJSONArray("files");
        JSONObject archive = null;
        for (int i = 0; i < releaseFiles.length(); i++) {
            JSONObject item = releaseFiles.getJSONObject(i);
            if (!item.optString("filename").endsWith(".mrpack")) continue;
            if (archive == null || item.optBoolean("primary")) archive = item;
        }
        if (archive == null) throw new IOException("Modrinth release has no .mrpack file.");
        String versionId = release.getString("id");
        File pack = new File(ctx.getCacheDir(), "sky-modrinth-pack.mrpack");
        File stage = new File(ctx.getCacheDir(), "sky-pack-stage");
        deleteTree(stage);
        if (!stage.mkdirs()) throw new IOException("Cannot create pack staging directory.");
        try {
            String archiveUrl = archive.getString("url");
            progress.update("Downloading verified Modrinth .mrpack...");
            downloadModrinthFile(ctx, archiveUrl, pack, progress);
            JSONObject archiveHashes = archive.optJSONObject("hashes");
            if (archiveHashes != null) verifyExpectedHash(
                    pack, archiveHashes.optString("sha512"),
                    archiveHashes.optString("sha1"));
            try (ZipFile zip = new ZipFile(pack)) {
                ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
                if (indexEntry == null || indexEntry.getSize() > 3_000_000L)
                    throw new IOException("Modrinth pack index is missing or too large.");
                JSONObject index = new JSONObject(new String(
                        readLimited(zip.getInputStream(indexEntry), 3_000_000), "UTF-8"));
                if (index.optInt("formatVersion") != 1 || !"minecraft".equals(
                        index.optString("game"))) throw new IOException("Unsupported .mrpack format.");
                JSONObject dependencies = index.getJSONObject("dependencies");
                String minecraft = dependencies.getString("minecraft");
                if (!minecraft.matches("[A-Za-z0-9_.-]{1,64}"))
                    throw new IOException("Invalid Minecraft version in pack.");
                String loader = "Vanilla";
                String pinnedLoaderVersion = "";
                int foundLoaders = 0;
                for (String id : new String[]{"neoforge", "forge", "fabric-loader", "quilt-loader"}) {
                    if (dependencies.has(id)) {
                        foundLoaders++;
                        pinnedLoaderVersion = dependencies.getString(id);
                        loader = id.equals("neoforge") ? "NeoForge" : id.equals("forge")
                                ? "Forge" : id.equals("fabric-loader") ? "Fabric" : "Quilt";
                    }
                }
                if (foundLoaders > 1)
                    throw new IOException("Modrinth pack specifies multiple primary loaders.");
                JSONArray files = index.getJSONArray("files");
                if (files.length() > MAX_ENTRIES)
                    throw new IOException("Modrinth pack contains too many files.");
                long downloaded = 0;
                for (int i = 0; i < files.length(); i++) {
                    if (progress.cancelled()) throw new IOException("Pack installation cancelled.");
                    JSONObject file = files.getJSONObject(i);
                    JSONObject env = file.optJSONObject("env");
                    if (env != null && "unsupported".equals(env.optString("client"))) continue;
                    String path = file.getString("path");
                    if (!allowedFile(path))
                        throw new IOException("Unsupported modpack path: " + path);
                    JSONArray urls = file.getJSONArray("downloads");
                    File target = safeFile(stage, path);
                    if (target.exists()) throw new IOException("Duplicate modpack file: " + path);
                    Exception last = null;
                    for (int u = 0; u < urls.length(); u++) {
                        String link = urls.getString(u);
                        if (!allowedModrinthDownload(link)) continue;
                        try {
                            progress.update("Installing Modrinth file " + (i + 1)
                                    + "/" + files.length());
                            downloadModrinthFile(ctx, link, target, progress);
                            JSONObject hashes = file.getJSONObject("hashes");
                            verifyExpectedHash(target, hashes.optString("sha512"),
                                    hashes.optString("sha1"));
                            last = null;
                            break;
                        } catch (Exception failure) {
                            last = failure;
                            target.delete();
                        }
                    }
                    if (!target.isFile())
                        throw new IOException("Unable to download verified pack file: " + path
                                + (last == null ? "" : " (" + last.getMessage() + ")"));
                    downloaded += target.length();
                    if (downloaded > MAX_TOTAL_FILES)
                        throw new IOException("Modrinth pack exceeds the storage limit.");
                }
                extractAllowed(zip, stage, "overrides/", true, progress);
                extractAllowed(zip, stage, "client-overrides/", true, progress);
                SkyPackUpdateStore.apply(ctx, stage, progress, expectedProfileId,
                        "modrinth:" + projectId, versionId, index.optString("name", "Modrinth pack"));
                ProfileStore.Profile selected = ProfileStore.active(ctx);
                if (!expectedProfileId.equals(selected.id))
                    throw new IOException("Active profile changed.");
                selected.version = minecraft;
                selected.loader = loaderIndex(loader);
                selected.loaderVersion = pinnedLoaderVersion;
                ProfileStore.upsert(ctx, selected);
                progress.completed("Modrinth pack installed: " + files.length()
                        + " entries. Minecraft " + minecraft + " / " + loader
                        + " is selected. Press Play to install the loader if needed.");
            }
        } finally {
            pack.delete();
            deleteTree(stage);
        }
    }

    private static void verifyExpectedHash(File file, String sha512, String sha1)
            throws Exception {
        if (sha512 != null && sha512.matches("[0-9a-fA-F]{128}")) {
            if (!sha512.equalsIgnoreCase(fileHash(file, "SHA-512")))
                throw new IOException("Modrinth SHA-512 check failed: " + file.getName());
        } else if (sha1 != null && sha1.matches("[0-9a-fA-F]{40}")) {
            if (!sha1.equalsIgnoreCase(fileHash(file, "SHA-1")))
                throw new IOException("Modrinth SHA-1 check failed: " + file.getName());
        } else throw new IOException("Modrinth file has no valid security checksum.");
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

    static boolean allowedFile(String relative) {
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
