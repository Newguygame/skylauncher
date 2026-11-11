package org.fathomstudios.skylauncher.android;

import android.content.Context;
import android.os.Build;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SkyLauncher-owned, SHA-256-verified OpenJDK 21 setup.
 *
 * The test build ships an unmodified OpenJDK runtime distribution as a
 * compressed APK asset; it does not import a third-party launcher engine.
 *
 * WARNING: A valid runtime archive is not proof that Android can load the
 * HotSpot ELF. Such validation requires a real device runtime test.
 */
final class SkyJavaRuntimeInstaller {
    interface Progress { void update(String message); }

    private static final String ASSET = "jre21-android-arm64.tar.xz";
    private static final String ARCHIVE_SHA256 =
            "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe";
    private static final long MAX_EXTRACT_BYTES = 440L * 1024 * 1024;
    private static final long MAX_ONE_FILE_BYTES = 200L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2500;

    private SkyJavaRuntimeInstaller() { }

    static File runtimeHome(Context context) {
        return new File(context.getFilesDir(), "minecraft/runtimes/java21");
    }

    static boolean ready(Context context) {
        File home = runtimeHome(context);
        return new File(home, "lib/server/libjvm.so").isFile()
                && new File(home, "lib/modules").isFile()
                && new File(home, ".skylauncher-jre-sha256").isFile();
    }

    private static void check(AtomicBoolean cancelled) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new IOException("OpenJDK installation was cancelled.");
    }

    private static String checksum(Context context, AtomicBoolean stop) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(ASSET)) {
            byte[] block = new byte[65536];
            int n;
            while ((n = in.read(block)) != -1) {
                check(stop);
                digest.update(block, 0, n);
            }
        }
        StringBuilder b = new StringBuilder(64);
        for (byte c : digest.digest())
            b.append(String.format(Locale.ROOT, "%02x", c & 255));
        return b.toString();
    }

    static File install(Context context, AtomicBoolean stop, Progress progress)
            throws Exception {
        if (Build.SUPPORTED_ABIS.length == 0 ||
                !Build.SUPPORTED_ABIS[0].equals("arm64-v8a"))
            throw new IOException("This OpenJDK test runtime supports Android ARM64 only.");

        File destination = runtimeHome(context);
        if (ready(context)) return destination;
        progress.update("Verifying packaged Android OpenJDK 21 (SHA-256)...");
        if (!ARCHIVE_SHA256.equals(checksum(context, stop)))
            throw new IOException("Packaged OpenJDK runtime failed SHA-256 verification.");

        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create independent runtime directory.");
        File staging = new File(parent, ".java21-" + UUID.randomUUID()).getCanonicalFile();
        if (!staging.mkdirs())
            throw new IOException("Cannot create temporary runtime directory.");
        try {
            progress.update("Installing OpenJDK 21 Java runtime...");
            int totalEntries = 0;
            long extracted = 0;
            String pendingLongPath = null;
            try (InputStream source = context.getAssets().open(ASSET);
                 InputStream decoded = new BufferedInputStream(new XZInputStream(source))) {
                byte[] header = new byte[512];
                while (readHeader(decoded, header)) {
                    check(stop);
                    if (isZero(header)) break;
                    if (++totalEntries > MAX_ENTRIES)
                        throw new IOException("Too many OpenJDK archive entries.");
                    long size = octal(header, 124, 12);
                    if (size < 0 || size > MAX_ONE_FILE_BYTES)
                        throw new IOException("Invalid OpenJDK archive entry size.");
                    char type = (char) header[156];
                    String name = tarString(header, 0, 100);
                    String prefix = tarString(header, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                    if (pendingLongPath != null) {
                        name = pendingLongPath;
                        pendingLongPath = null;
                    }
                    // GNU long-name records are metadata for the following entry.
                    if (type == 'L') {
                        if (size > 8192) throw new IOException("Unsafe OpenJDK long path.");
                        byte[] longPath = readExact(decoded, (int)size);
                        pendingLongPath = new String(longPath, StandardCharsets.UTF_8)
                                .replace("\u0000", "").trim();
                        skipPadding(decoded, size);
                        continue;
                    }
                    if (type == 'x' || type == 'g' || type == 'K') {
                        // PAX/global headers and GNU long-link records are
                        // metadata; this runtime uses normal USTAR file names.
                        skipBytes(decoded, size);
                        skipPadding(decoded, size);
                        continue;
                    }
                    String normalized = name.startsWith("./") ? name.substring(2) : name;
                    if (normalized.equals(".") || normalized.isEmpty()) {
                        skipBytes(decoded, size);
                        skipPadding(decoded, size);
                        continue;
                    }
                    String[] components = normalized.split("/", -1);
                    for (String component : components)
                        if (component.equals("..") || component.equals(".")
                                || component.contains("\\") || component.contains(":"))
                            throw new IOException("Unsafe OpenJDK archive path.");
                    if (normalized.startsWith("/") || normalized.indexOf('\u0000') >= 0)
                        throw new IOException("Unsafe OpenJDK absolute path.");
                    Path path = staging.toPath().resolve(normalized).normalize();
                    if (!path.startsWith(staging.toPath()))
                        throw new IOException("OpenJDK archive path escapes runtime root.");
                    File target = path.toFile();

                    if (type == '5') {
                        if (!target.isDirectory() && !target.mkdirs())
                            throw new IOException("Cannot create runtime folder.");
                        skipBytes(decoded, size);
                    } else if (type == '0' || type == '\u0000') {
                        File dir = target.getParentFile();
                        if (!dir.isDirectory() && !dir.mkdirs())
                            throw new IOException("Cannot create runtime file directory.");
                        try (FileOutputStream output = new FileOutputStream(target)) {
                            long remaining = size;
                            byte[] buffer = new byte[65536];
                            while (remaining > 0) {
                                check(stop);
                                int take = decoded.read(buffer, 0,
                                        (int)Math.min(buffer.length, remaining));
                                if (take < 0) throw new IOException("OpenJDK archive truncated.");
                                output.write(buffer, 0, take);
                                remaining -= take;
                                extracted += take;
                                if (extracted > MAX_EXTRACT_BYTES)
                                    throw new IOException("OpenJDK extraction exceeds limit.");
                            }
                            output.getFD().sync();
                        }
                        // Mark necessary runtime files executable. Android may
                        // still refuse loading libs from app data; the JNI
                        // renderer test reports that linker failure.
                        if (normalized.endsWith(".so") || normalized.startsWith("bin/"))
                            target.setExecutable(true, true);
                    } else if (type == '2' || type == '1') {
                        // This OpenJDK distribution uses links for repeated
                        // legal notices, not runtime ELF files. Preserve the
                        // original archive in APK assets for licence inspection.
                        if (!normalized.startsWith("legal/"))
                            throw new IOException("Unexpected runtime symlink: " + normalized);
                        skipBytes(decoded, size);
                    } else {
                        throw new IOException("Unsupported OpenJDK tar entry type: " + type);
                    }
                    skipPadding(decoded, size);
                    if (totalEntries % 25 == 0)
                        progress.update("Extracted OpenJDK files: " + totalEntries);
                }
            }
            File vm = new File(staging, "lib/server/libjvm.so");
            File modules = new File(staging, "lib/modules");
            if (!vm.isFile() || !modules.isFile())
                throw new IOException("OpenJDK archive does not contain a JVM and modules.");
            File marker = new File(staging, ".skylauncher-jre-sha256");
            try (OutputStream output = new FileOutputStream(marker)) {
                output.write(ARCHIVE_SHA256.getBytes(StandardCharsets.US_ASCII));
            }
            if (destination.exists()) removeTree(destination);
            if (!staging.renameTo(destination))
                throw new IOException("Cannot finalize OpenJDK installation.");
            progress.update("Standalone OpenJDK 21 installed. Checking Minecraft graphics next...");
            return destination;
        } finally {
            if (staging.exists()) removeTree(staging);
        }
    }

    private static boolean readHeader(InputStream input, byte[] header) throws IOException {
        int offset = 0;
        while (offset < header.length) {
            int n = input.read(header, offset, header.length - offset);
            if (n < 0) {
                if (offset == 0) return false;
                throw new IOException("Truncated OpenJDK archive header.");
            }
            offset += n;
        }
        return true;
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static long octal(byte[] data, int start, int count) throws IOException {
        String raw = tarString(data, start, count).trim();
        if (raw.isEmpty()) return 0;
        try { return Long.parseLong(raw, 8); }
        catch (NumberFormatException error) {
            throw new IOException("Invalid OpenJDK archive file size.");
        }
    }

    private static String tarString(byte[] data, int start, int length) {
        int end = start;
        while (end < start + length && data[end] != 0) end++;
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }

    private static byte[] readExact(InputStream input, int count) throws IOException {
        byte[] result = new byte[count];
        int at = 0;
        while (at < count) {
            int n = input.read(result, at, count - at);
            if (n < 0) throw new IOException("Truncated OpenJDK tar metadata.");
            at += n;
        }
        return result;
    }

    private static void skipPadding(InputStream source, long size) throws IOException {
        skipBytes(source, (512 - size % 512) % 512);
    }

    private static void skipBytes(InputStream source, long length) throws IOException {
        byte[] buf = new byte[8192];
        while (length > 0) {
            int n = source.read(buf, 0, (int)Math.min(length, buf.length));
            if (n < 0) throw new IOException("Truncated OpenJDK archive.");
            length -= n;
        }
    }

    private static void removeTree(File folder) throws IOException {
        if (folder.isDirectory()) {
            File[] children = folder.listFiles();
            if (children != null) for (File child : children) removeTree(child);
        }
        if (folder.exists() && !folder.delete())
            throw new IOException("Cannot remove staged runtime file.");
    }
}
