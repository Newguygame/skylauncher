package org.fathomstudios.skylauncher.android;

import android.content.Context;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-profile provenance and safe repeatable pack upgrades.
 * World saves and untracked files are never deleted during a pack update.
 */
final class SkyPackUpdateStore {
    static final class Record {
        final String source, version, name;
        Record(String source, String version, String name) {
            this.source = source; this.version = version; this.name = name;
        }
    }

    private SkyPackUpdateStore() { }

    private static File recordFile(Context context, String profileId) throws IOException {
        if (profileId == null || !profileId.matches("[A-Za-z0-9_-]{1,72}"))
            throw new IOException("Invalid profile ID.");
        File folder = new File(context.getFilesDir(), "sky-pack-records");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Unable to create modpack update directory.");
        return new File(folder, profileId + ".json");
    }

    private static JSONObject state(Context context, String profileId) throws Exception {
        File file = recordFile(context, profileId);
        if (!file.isFile()) return new JSONObject();
        if (file.length() > 2_000_000) throw new IOException("Pack tracking file is too large.");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static Record current(Context context) throws Exception {
        JSONObject state = state(context, ProfileStore.active(context).id);
        String source = state.optString("source");
        String version = state.optString("version");
        if (source.isEmpty() || version.isEmpty()) return null;
        return new Record(source, version, state.optString("name", source));
    }

    private static File safe(File root, String relative) throws Exception {
        if (!SkyPackInstaller.allowedFile(relative))
            throw new IOException("Unsafe modpack update file: " + relative);
        File result = new File(root, relative).getCanonicalFile();
        if (!result.toPath().startsWith(root.getCanonicalFile().toPath()))
            throw new IOException("A pack file attempted to leave its profile directory.");
        return result;
    }

    private static String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) hash.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest())
            hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private static void list(File folder, File root, List<File> out) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) throw new IOException("Unable to read staged modpack.");
        for (File f : files) {
            if (Files.isSymbolicLink(f.toPath()))
                throw new IOException("Links are not supported in modpacks.");
            if (f.isDirectory()) list(f, root, out);
            else if (f.isFile()) out.add(f);
        }
    }

    private static String relative(File root, File item) {
        return root.toPath().relativize(item.toPath()).toString()
                .replace(File.separatorChar, '/');
    }

    private static void copy(File from, File to) throws Exception {
        File parent = to.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create destination folder.");
        File pending = new File(parent, to.getName() + ".sky-pending");
        try {
            try (InputStream in = new BufferedInputStream(new FileInputStream(from));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(pending))) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            if (to.exists() && !to.delete())
                throw new IOException("Unable to replace managed file: " + to.getName());
            if (!pending.renameTo(to))
                throw new IOException("Unable to finish file installation.");
        } finally {
            pending.delete();
        }
    }

    private static void save(Context ctx, String profileId, JSONObject data) throws Exception {
        File target = recordFile(ctx, profileId);
        File pending = new File(target.getParentFile(), target.getName() + ".pending");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        // Keep the previous record intact on an I/O error.
        if (target.exists()) {
            File prior = new File(target.getParentFile(), target.getName() + ".previous");
            if (prior.exists() && !prior.delete()) throw new IOException("Unable to rotate pack record.");
            if (!target.renameTo(prior)) throw new IOException("Unable to back up pack record.");
            if (!pending.renameTo(target)) {
                prior.renameTo(target);
                throw new IOException("Unable to save pack update metadata.");
            }
            prior.delete();
        } else if (!pending.renameTo(target)) {
            throw new IOException("Unable to write pack update metadata.");
        }
    }

    static void apply(Context ctx, File stage, SkyPackInstaller.Progress progress,
                      String expectedProfileId, String source, String version,
                      String packName) throws Exception {
        if (source == null || source.isEmpty() || version == null || version.isEmpty())
            throw new IOException("Modpack source version is missing.");
        ProfileStore.Profile profile = ProfileStore.active(ctx);
        if (!expectedProfileId.equals(profile.id))
            throw new IOException("Profile changed during modpack installation.");
        File root = SkyEngineProfileBridge.gameDirectory(ctx, profile);
        JSONObject old = state(ctx, expectedProfileId);
        JSONObject previousFiles = source.equals(old.optString("source"))
                ? old.optJSONObject("files") : null;
        List<File> staged = new ArrayList<>();
        list(stage, stage, staged);
        JSONObject installed = new JSONObject();
        Set<String> incoming = new HashSet<>();
        long bytes = 0;
        for (File entry : staged) {
            String relative = relative(stage, entry);
            safe(root, relative);
            incoming.add(relative);
            bytes += entry.length();
        }
        if (root.getUsableSpace() < bytes + 100_000_000L)
            throw new IOException("Not enough storage for modpack update.");
        File backups = new File(ctx.getFilesDir(), "modpack-backups/"
                + System.currentTimeMillis() + "-" + expectedProfileId);
        List<String> changed = new ArrayList<>();
        Set<String> existed = new HashSet<>();
        int n = 0;
        try {
            for (File entry : staged) {
                if (progress.cancelled()) throw new IOException("Pack update cancelled.");
                if (!expectedProfileId.equals(ProfileStore.active(ctx).id))
                    throw new IOException("Profile switched; stopping update.");
                String path = relative(stage, entry);
                String newHash = digest(entry);
                File target = safe(root, path);
                installed.put(path, newHash);
                if (target.isFile() && digest(target).equals(newHash)) continue;
                if (target.exists()) {
                    if (!target.isFile()) throw new IOException("Pack path is not a file: " + path);
                    copy(target, safe(backups, path));
                    existed.add(path);
                }
                changed.add(path);
                copy(entry, target);
                if (++n % 20 == 0)
                    progress.update("Updating pack files: " + n + "/" + staged.size());
            }
            // Only files from this same previously installed pack may become stale.
            // Keep locally edited files: they may be user customization.
            if (previousFiles != null) {
                java.util.Iterator<String> keys = previousFiles.keys();
                while (keys.hasNext()) {
                    if (progress.cancelled()) throw new IOException("Pack update cancelled.");
                    String path = keys.next();
                    if (incoming.contains(path) || !SkyPackInstaller.allowedFile(path)) continue;
                    File oldFile = safe(root, path);
                    if (!oldFile.isFile()) continue;
                    if (!digest(oldFile).equalsIgnoreCase(previousFiles.optString(path))) continue;
                    copy(oldFile, safe(backups, path));
                    existed.add(path);
                    changed.add(path);
                    if (!oldFile.delete()) throw new IOException("Cannot remove stale managed file.");
                }
            }
            JSONObject updated = new JSONObject();
            updated.put("source", source);
            updated.put("version", version);
            updated.put("name", packName);
            updated.put("files", installed);
            save(ctx, expectedProfileId, updated);
        } catch (Exception failure) {
            // Undo replacements and removals if any step failed; worlds are never touched.
            for (int i = changed.size() - 1; i >= 0; i--) {
                String path = changed.get(i);
                File dest = safe(root, path);
                try {
                    if (existed.contains(path)) copy(safe(backups, path), dest);
                    else if (dest.exists() && !dest.delete())
                        throw new IOException("Unable to undo newly added file.");
                } catch (Exception rollback) {
                    failure.addSuppressed(rollback);
                }
            }
            throw failure;
        }
    }
}
