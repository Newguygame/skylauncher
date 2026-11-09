package org.fathomstudios.skylauncher.android;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Environment;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Scoped-storage-compatible folder discovery and mod management. */
final class ModFolderManager {
    static final class Folder {
        final DocumentFile document;
        final File local;
        final String label;
        final boolean external;
        Folder(DocumentFile document, File local, String label, boolean external) {
            this.document = document; this.local = local;
            this.label = label; this.external = external;
        }
    }
    static final class Entry {
        final String name;
        final DocumentFile document;
        final File local;
        Entry(String name, DocumentFile document, File local) {
            this.name = name; this.document = document; this.local = local;
        }
        boolean enabled() { return name.toLowerCase(Locale.ROOT).endsWith(".jar"); }
    }
    private ModFolderManager() { }

    static Folder resolve(Context context) {
        ProfileStore.Profile profile = ProfileStore.active(context);
        if (profile.folderUri != null && !profile.folderUri.isEmpty()) {
            Folder saved = locateGranted(context, Uri.parse(profile.folderUri));
            if (saved != null) return saved;
        }
        // Auto-detect only inside trees the user previously authorized.
        // Ambiguous grants must never silently install mods into the wrong profile.
        Folder one = null;
        Uri oneUri = null;
        boolean multiple = false;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (!permission.isWritePermission()) continue;
            Folder found = locateGranted(context, permission.getUri());
            if (found == null) continue;
            if (one != null) {
                multiple = true;
                break;
            }
            one = found;
            oneUri = permission.getUri();
        }
        if (one != null && !multiple) {
            profile.folderUri = oneUri.toString();
            ProfileStore.upsert(context, profile);
            return one;
        }
        // Always-available fallback: this app owns its own profile's mods directory.
        File root = context.getExternalFilesDir(null);
        if (root == null) root = context.getFilesDir();
        File mods = new File(new File(new File(root, "profiles"), profile.id), "mods");
        if (!mods.isDirectory() && !mods.mkdirs()) {
            return new Folder(null, null, "Appopslag niet beschikbaar", false);
        }
        return new Folder(null, mods,
                "SkyLauncher/" + profile.name + "/mods (eigen appmap; externe launcher ziet deze niet automatisch)"
                + (multiple ? " — meerdere toegestane mods-mappen: kies er één handmatig" : ""),
                false);
    }

    static Folder locateGranted(Context context, Uri rootUri) {
        try {
            boolean granted = false;
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission.isWritePermission()
                        && permission.getUri().equals(rootUri)) {
                    granted = true;
                    break;
                }
            }
            if (!granted) return null;
            DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
            if (root == null || !root.isDirectory() || !root.canWrite()) return null;
            DocumentFile found = findMods(root, 0, new int[]{0});
            if (found == null) return null;
            return new Folder(found, null, "Gevonden: " + found.getName()
                    + " in toegestane Android-map", true);
        } catch (Exception ignored) {
            return null;
        }
    }

    // Deliberately bounded: never traverse an entire SD card or private app storage.
    private static DocumentFile findMods(DocumentFile node, int depth, int[] seen) {
        if (node == null || !node.isDirectory() || !node.canWrite()) return null;
        String name = node.getName();
        if (name != null && name.equalsIgnoreCase("mods")) return node;
        if (depth >= 4 || seen[0] >= 130) return null;
        DocumentFile direct = node.findFile("mods");
        if (direct != null && direct.isDirectory() && direct.canWrite()) return direct;
        if (depth >= 3) return null;
        for (DocumentFile child : node.listFiles()) {
            if (++seen[0] >= 130) break;
            if (!child.isDirectory()) continue;
            String n = child.getName() == null ? "" : child.getName().toLowerCase(Locale.ROOT);
            if (n.equals("android") || n.equals("obb") || n.startsWith("." ) && !n.equals(".minecraft"))
                continue;
            DocumentFile nested = findMods(child, depth + 1, seen);
            if (nested != null) return nested;
        }
        return null;
    }

    static void setSelectedRoot(Context context, Uri uri) throws Exception {
        Folder located = locateGranted(context, uri);
        if (located == null) throw new Exception("Geen mods-map gevonden binnen de gekozen map. Kies de mods-map zelf of een map die een submap 'mods' bevat.");
        ProfileStore.Profile p = ProfileStore.active(context);
        p.folderUri = uri.toString();
        ProfileStore.upsert(context, p);
    }

    static void useInternal(Context context) {
        ProfileStore.Profile p = ProfileStore.active(context);
        p.folderUri = "";
        ProfileStore.upsert(context, p);
    }

    static List<Entry> list(Context context) {
        Folder f = resolve(context);
        List<Entry> mods = new ArrayList<>();
        if (f.document != null) {
            for (DocumentFile doc : f.document.listFiles()) {
                if (!doc.isFile()) continue;
                String name = doc.getName();
                if (isMod(name)) mods.add(new Entry(name, doc, null));
            }
        } else if (f.local != null) {
            File[] children = f.local.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isFile() && isMod(child.getName()))
                        mods.add(new Entry(child.getName(), null, child));
                }
            }
        }
        mods.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return mods;
    }

    private static boolean isMod(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") || n.endsWith(".jar.disabled");
    }

    static void save(Context context, String name, byte[] bytes) throws Exception {
        if (!name.matches("[a-zA-Z0-9_.+() -]{1,180}\\.jar"))
            throw new Exception("Ongeldige modbestandsnaam");
        Folder folder = resolve(context);
        if (folder.document != null) {
            if (folder.document.findFile(name) != null)
                throw new Exception("Deze mod is al geïnstalleerd");
            DocumentFile target = folder.document.createFile("application/java-archive", name);
            if (target == null) throw new Exception("Kan geen bestand aanmaken in de mods-map");
            try (OutputStream out = context.getContentResolver()
                    .openOutputStream(target.getUri(), "w")) {
                if (out == null) throw new Exception("Geen schrijftoegang");
                out.write(bytes);
                out.flush();
            } catch (Exception e) {
                target.delete();
                throw e;
            }
        } else if (folder.local != null) {
            File file = new File(folder.local, name);
            if (file.exists()) throw new Exception("Deze mod is al geïnstalleerd");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
                out.getFD().sync();
            } catch (Exception e) {
                file.delete();
                throw e;
            }
        } else throw new Exception("Geen beschrijfbare mods-map");
    }

    static void delete(Entry entry) throws Exception {
        boolean ok = entry.document != null ? entry.document.delete()
                : entry.local != null && entry.local.delete();
        if (!ok) throw new Exception("Kon mod niet verwijderen");
    }

    static void toggle(Entry entry) throws Exception {
        String next = entry.enabled() ? entry.name + ".disabled"
                : entry.name.substring(0, entry.name.length() - ".disabled".length());
        boolean ok = entry.document != null ? entry.document.renameTo(next)
                : entry.local != null && entry.local.renameTo(new File(entry.local.getParentFile(), next));
        if (!ok) throw new Exception("Kon mod niet hernoemen");
    }
}
