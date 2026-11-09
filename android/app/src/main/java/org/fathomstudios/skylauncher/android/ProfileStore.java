package org.fathomstudios.skylauncher.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Android profile metadata. External launchers retain their own instances. */
final class ProfileStore {
    private static final String PREFS = "skylauncher_android";
    private static final String KEY_PROFILES = "profiles_json";
    private static final String KEY_CURRENT = "selected_profile_id";

    static final String[] LOADERS = {"NeoForge", "Fabric", "Forge", "Quilt", "Vanilla"};
    static final String[] ENGINES = {"Fold Craft Launcher (FCL)", "Amethyst"};

    static final class Profile {
        String id, name, version, folderUri;
        int loader, engine, ramMb;
        Profile() {}
        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("version", version);
                o.put("loader", loader);
                o.put("engine", engine);
                o.put("ram_mb", ramMb);
                o.put("folder_uri", folderUri == null ? "" : folderUri);
            } catch (Exception ignored) { }
            return o;
        }
        static Profile fromJson(JSONObject o) {
            Profile p = new Profile();
            p.id = o.optString("id", UUID.randomUUID().toString());
            p.name = o.optString("name", "Minecraft");
            p.version = o.optString("version", "1.21.1");
            p.loader = Math.max(0, Math.min(LOADERS.length - 1, o.optInt("loader")));
            p.engine = Math.max(0, Math.min(ENGINES.length - 1, o.optInt("engine")));
            p.ramMb = Math.max(512, Math.min(32768, o.optInt("ram_mb", 4096)));
            p.folderUri = o.optString("folder_uri", "");
            return p;
        }
    }

    private ProfileStore() { }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Profile> all(Context context) {
        SharedPreferences p = prefs(context);
        List<Profile> profiles = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(p.getString(KEY_PROFILES, "[]"));
            for (int i = 0; i < list.length(); i++) {
                profiles.add(Profile.fromJson(list.getJSONObject(i)));
            }
        } catch (Exception ignored) { }
        if (profiles.isEmpty()) {
            Profile first = new Profile();
            first.id = UUID.randomUUID().toString();
            first.name = "Minecraft";
            first.version = p.getString("version", "1.21.1");
            first.loader = p.getInt("loader", 0);
            first.engine = p.getInt("engine", 1);
            first.ramMb = p.getInt("generic_memory_mb", 4096);
            first.folderUri = p.getString("mods_folder", "");
            profiles.add(first);
            persist(context, profiles, first.id);
        }
        return profiles;
    }

    static Profile active(Context context) {
        List<Profile> all = all(context);
        String selected = prefs(context).getString(KEY_CURRENT, all.get(0).id);
        for (Profile p : all) if (p.id.equals(selected)) return p;
        setActive(context, all.get(0).id);
        return all.get(0);
    }

    private static void persist(Context context, List<Profile> profiles, String activeId) {
        JSONArray arr = new JSONArray();
        for (Profile p : profiles) arr.put(p.json());
        prefs(context).edit().putString(KEY_PROFILES, arr.toString())
                .putString(KEY_CURRENT, activeId).apply();
    }

    static void upsert(Context context, Profile update) {
        List<Profile> list = all(context);
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(update.id)) {
                list.set(i, update);
                changed = true;
                break;
            }
        }
        if (!changed) list.add(update);
        String selected = prefs(context).getString(KEY_CURRENT, list.get(0).id);
        persist(context, list, selected);
        if (selected.equals(update.id)) syncLegacy(context, update);
    }

    static void setActive(Context context, String id) {
        List<Profile> profiles = all(context);
        for (Profile p : profiles) {
            if (p.id.equals(id)) {
                persist(context, profiles, id);
                syncLegacy(context, p);
                return;
            }
        }
    }

    static void delete(Context context, String id) {
        List<Profile> list = all(context);
        if (list.size() <= 1) return;
        list.removeIf(p -> p.id.equals(id));
        String current = prefs(context).getString(KEY_CURRENT, "");
        if (id.equals(current)) current = list.get(0).id;
        persist(context, list, current);
        setActive(context, current);
    }

    static void syncLegacy(Context context, Profile p) {
        prefs(context).edit()
                .putString("version", p.version)
                .putInt("loader", p.loader)
                .putInt("engine", p.engine)
                .putInt("generic_memory_mb", p.ramMb)
                .putString("mods_folder", p.folderUri == null ? "" : p.folderUri)
                .apply();
    }

    static Profile create(String name, String version, int loader, int engine, int ramMb) {
        Profile p = new Profile();
        p.id = UUID.randomUUID().toString();
        p.name = name;
        p.version = version;
        p.loader = loader;
        p.engine = engine;
        p.ramMb = ramMb;
        p.folderUri = "";
        return p;
    }
}
