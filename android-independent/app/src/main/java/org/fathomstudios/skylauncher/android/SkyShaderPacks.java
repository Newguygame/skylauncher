package org.fathomstudios.skylauncher.android;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * SkyLauncher-owned shaderpack importer.
 *
 * Shaderpacks are scoped to the active independent Minecraft profile and
 * validated for safe ZIP paths and sane sizes. This DOES NOT promise runtime
 * Iris shader execution before the independent LWJGL/Zink renderer exists.
 */
final class SkyShaderPacks {
    interface Progress { void update(String message); }
    private static final long MAX_COMPRESSED = 150L * 1024 * 1024;
    private static final long MAX_UNPACKED = 350L * 1024 * 1024;
    private static final long MAX_SINGLE_FILE = 90L * 1024 * 1024;
    private static final int MAX_ENTRIES = 12000;

    static final class ImportResult {
        final String name;
        final int entries, shaderSources;
        final boolean flattened;
        ImportResult(String name, int entries, int shaderSources, boolean flattened) {
            this.name = name; this.entries = entries; this.shaderSources = shaderSources;
            this.flattened = flattened;
        }
        String summary() {
            return "Imported shader pack " + name + " ("
                    + shaderSources + " shader source files, " + entries
                    + " ZIP entries). " + (flattened
                    ? "Removed a wrapping folder. " : "")
                    + "The pack will be usable only when a compatible "
                    + "Minecraft shader renderer is integrated.";
        }
    }

    private SkyShaderPacks() { }

    private static void check(AtomicBoolean cancelled) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new IOException("Shaderpack import cancelled.");
    }

    private static String normalize(String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 750)
            throw new IOException("Invalid ZIP entry path.");
        if (path.startsWith("/") || path.contains("\\") || path.contains(":"))
            throw new IOException("Unsafe shaderpack ZIP entry path.");
        String[] names = path.split("/", -1);
        for (int i = 0; i < names.length; i++) {
            if ((names[i].isEmpty() && i != names.length - 1)
                    || names[i].equals("..") || names[i].equals("."))
                throw new IOException("Unsafe shaderpack ZIP path.");
        }
        return path;
    }

    private static String rootPrefix(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        if (s.startsWith("shaders/")) return "";
        int index = s.indexOf("/shaders/");
        if (index > 0) return name.substring(0, index + 1);
        return null;
    }

    static ImportResult importZip(Context ctx, ProfileStore.Profile profile, Uri source,
                                  AtomicBoolean cancelled, Progress progress)
            throws Exception {
        if (source == null) throw new IOException("No shaderpack ZIP selected.");
        if (!ProfileStore.all(ctx).get(0).id.equals(profile.id))
            throw new IOException("Active profile changed before shaderpack import.");
        File root = ProfileStore.root(ctx, profile);
        File folder = new File(root, "shaderpacks");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Cannot create the shaderpacks directory.");
        File downloaded = File.createTempFile("skyshader-", ".zip", ctx.getCacheDir());
        File candidate = new File(folder, ".skyshader-" + java.util.UUID.randomUUID() + ".zip");
        try {
            progress.update("Reading and validating shader pack ZIP...");
            try (InputStream input = ctx.getContentResolver().openInputStream(source);
                 FileOutputStream output = new FileOutputStream(downloaded)) {
                if (input == null) throw new IOException("Cannot open selected ZIP.");
                byte[] buf = new byte[65536];
                long read = 0;
                int len;
                while ((len = input.read(buf)) != -1) {
                    check(cancelled);
                    read += len;
                    if (read > MAX_COMPRESSED)
                        throw new IOException("Shader pack ZIP is too large.");
                    output.write(buf, 0, len);
                }
                output.getFD().sync();
            }
            int entries = 0;
            int shaderFiles = 0;
            long total = 0L;
            String prefix = null;
            Set<String> seen = new HashSet<>();
            try (ZipFile zip = new ZipFile(downloaded)) {
                Enumeration<? extends ZipEntry> items = zip.entries();
                while (items.hasMoreElements()) {
                    check(cancelled);
                    ZipEntry e = items.nextElement();
                    if (++entries > MAX_ENTRIES)
                        throw new IOException("Shader pack contains too many entries.");
                    String path = normalize(e.getName());
                    if (e.isDirectory()) continue;
                    String key = path.toLowerCase(Locale.ROOT);
                    if (!seen.add(key))
                        throw new IOException("Duplicate shader pack ZIP path: " + path);
                    if (e.getSize() > MAX_SINGLE_FILE)
                        throw new IOException("Shader ZIP includes an oversized entry.");
                    if (e.getSize() > 0) total += e.getSize();
                    if (total > MAX_UNPACKED)
                        throw new IOException("Shader pack expands to too much data.");
                    String candidatePrefix = rootPrefix(path);
                    if (candidatePrefix != null) {
                        if (prefix == null || candidatePrefix.isEmpty())
                            prefix = candidatePrefix;
                        String lower = path.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".fsh") || lower.endsWith(".vsh")
                                || lower.endsWith(".gsh") || lower.endsWith(".csh")
                                || lower.endsWith(".glsl"))
                            shaderFiles++;
                    }
                }
                if (prefix == null || shaderFiles == 0)
                    throw new IOException("ZIP does not contain a shaders/ folder "
                            + "with GLSL shader sources.");
                final String prefixToStrip = prefix;
                progress.update("Validating and staging " + shaderFiles + " GLSL sources...");
                try (FileOutputStream fos = new FileOutputStream(candidate);
                     BufferedOutputStream buffered = new BufferedOutputStream(fos);
                     ZipOutputStream output = new ZipOutputStream(buffered)) {
                    Set<String> destinationNames = new HashSet<>();
                    Enumeration<? extends ZipEntry> all = zip.entries();
                    long expanded = 0;
                    while (all.hasMoreElements()) {
                        check(cancelled);
                        ZipEntry entry = all.nextElement();
                        if (entry.isDirectory()) continue;
                        String original = normalize(entry.getName());
                        if (!prefixToStrip.isEmpty()) {
                            if (!original.startsWith(prefixToStrip)) continue;
                            original = original.substring(prefixToStrip.length());
                        }
                        if (original.isEmpty()) continue;
                        if (!destinationNames.add(original.toLowerCase(Locale.ROOT)))
                            throw new IOException("Duplicate shader pack file: " + original);
                        output.putNextEntry(new ZipEntry(original));
                        try (InputStream input = new BufferedInputStream(
                                zip.getInputStream(entry))) {
                            byte[] buf = new byte[32768];
                            int len;
                            long individual = 0;
                            while ((len = input.read(buf)) != -1) {
                                check(cancelled);
                                individual += len;
                                expanded += len;
                                if (individual > MAX_SINGLE_FILE || expanded > MAX_UNPACKED)
                                    throw new IOException("Oversized shader pack content.");
                                output.write(buf, 0, len);
                            }
                        }
                        output.closeEntry();
                    }
                    output.finish();
                    output.flush();
                    fos.getFD().sync();
                }
            }

            if (!ProfileStore.all(ctx).get(0).id.equals(profile.id))
                throw new IOException("Active profile changed during shaderpack import.");
            String display = "ShaderPack-" + sha256(candidate).substring(0, 16) + ".zip";
            File finalZip = new File(folder, display).getCanonicalFile();
            if (!finalZip.toPath().startsWith(folder.getCanonicalFile().toPath()))
                throw new IOException("Shaderpack destination is unsafe.");
            if (finalZip.exists()) {
                candidate.delete();
            } else if (!candidate.renameTo(finalZip)) {
                throw new IOException("Cannot finish shaderpack import.");
            }
            return new ImportResult(display, entries, shaderFiles,
                    !prefix.isEmpty());
        } finally {
            downloaded.delete();
            candidate.delete();
        }
    }

    static String[] installed(Context ctx, ProfileStore.Profile p) throws Exception {
        File dir = new File(ProfileStore.root(ctx, p), "shaderpacks");
        File[] packs = dir.listFiles(f -> f.isFile()
                && !f.getName().startsWith(".")
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (packs == null) return new String[0];
        java.util.Arrays.sort(packs, java.util.Comparator.comparing(File::getName));
        String[] names = new String[packs.length];
        for (int i = 0; i < packs.length; i++) names[i] = packs[i].getName();
        return names;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest())
            hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }
}
