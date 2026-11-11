package org.fathomstudios.skylauncher.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SkyLauncher-only profiles. No Pojav, Amethyst, FCL, shared external
 * launcher profile files, or external launcher data directories.
 */
final class ProfileStore {
    static final String[] LOADERS = {"Vanilla", "NeoForge", "Forge", "Fabric", "Quilt"};
    static final class Profile {
        final String id;
        final String name;
        final String version;
        final String loader;
        Profile(String id, String name, String version, String loader) {
            this.id = id; this.name = name; this.version = version; this.loader = loader;
        }
    }
    private ProfileStore() {}

    static List<Profile> all(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("sky_profiles", Context.MODE_PRIVATE);
        List<Profile> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("profiles", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String id = o.getString("id"), name = o.getString("name"),
                        version = o.getString("version"), loader = o.getString("loader");
                if (id.matches("[a-f0-9-]{36}") && version.matches("[A-Za-z0-9._-]{1,64}"))
                    result.add(new Profile(id, name, version, loader));
            }
        } catch (Exception ignored) { }
        if (result.isEmpty()) {
            result.add(new Profile(UUID.randomUUID().toString(),
                    "Minecraft", "1.21.1", "Vanilla"));
            save(context, result);
        }
        return result;
    }

    static void save(Context context, List<Profile> profiles) {
        JSONArray array = new JSONArray();
        for (Profile p : profiles) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", p.id); o.put("name", p.name);
                o.put("version", p.version); o.put("loader", p.loader);
                array.put(o);
            } catch (Exception ignored) { }
        }
        context.getSharedPreferences("sky_profiles", Context.MODE_PRIVATE)
                .edit().putString("profiles", array.toString()).apply();
    }

    static File root(Context context, Profile p) throws Exception {
        if (!p.id.matches("[a-f0-9-]{36}")) throw new Exception("Invalid profile ID");
        File shared = new File(context.getFilesDir(), "minecraft");
        File dest = new File(new File(shared, "profiles"), p.id).getCanonicalFile();
        if (!dest.toPath().startsWith(shared.getCanonicalFile().toPath()))
            throw new Exception("Profile escapes game storage");
        if (!dest.exists() && !dest.mkdirs())
            throw new Exception("Cannot create game profile directory");
        return dest;
    }
}
