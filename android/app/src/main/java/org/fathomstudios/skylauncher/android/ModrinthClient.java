package org.fathomstudios.skylauncher.android;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ModrinthClient {
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 30000;
    private static final long MAX_MOD = 256L * 1024L * 1024L;
    private static final int MAX_JSON = 3_000_000;

    static final class Mod {
        final String id;
        final String title;
        final String description;
        Mod(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }
    }

    private ModrinthClient() { }

    private static HttpURLConnection open(String url, String allowedHost) throws Exception {
        URL endpoint = new URL(url);
        if (!"https".equals(endpoint.getProtocol()) ||
                !allowedHost.equalsIgnoreCase(endpoint.getHost())) {
            throw new Exception("Onbetrouwbaar downloadadres");
        }
        HttpURLConnection c = (HttpURLConnection) endpoint.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "SkyLauncherAndroid/0.2.0 (contact via FathomStudios.org)");
        c.setRequestProperty("Accept", "application/json");
        if (c.getResponseCode() != 200) {
            int status = c.getResponseCode();
            c.disconnect();
            throw new Exception("Modrinth antwoordde HTTP " + status);
        }
        return c;
    }

    private static String readJson(String endpoint) throws Exception {
        HttpURLConnection c = open(endpoint, "api.modrinth.com");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16_384];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_JSON) throw new Exception("Te veel API-data");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static List<Mod> search(String query, String minecraft, String loader) throws Exception {
        return searchProjects(query, minecraft, loader, "mod");
    }

    static List<Mod> searchModpacks(String query, String minecraft, String loader) throws Exception {
        return searchProjects(query, minecraft, loader, "modpack");
    }

    private static List<Mod> searchProjects(String query, String minecraft, String loader, String kind) throws Exception {
        JSONArray facets = new JSONArray()
                .put(new JSONArray().put("project_type:" + kind))
                .put(new JSONArray().put("versions:" + minecraft));
        if (!"vanilla".equalsIgnoreCase(loader)) {
            facets.put(new JSONArray().put("categories:" + loader.toLowerCase(Locale.ROOT)));
        }
        String url = "https://api.modrinth.com/v2/search?limit=18&query="
                + URLEncoder.encode(query, "UTF-8")
                + "&facets=" + URLEncoder.encode(facets.toString(), "UTF-8");
        JSONObject result = new JSONObject(readJson(url));
        JSONArray hits = result.optJSONArray("hits");
        List<Mod> mods = new ArrayList<>();
        if (hits == null) return mods;
        for (int i = 0; i < hits.length(); i++) {
            JSONObject m = hits.getJSONObject(i);
            mods.add(new Mod(m.getString("project_id"), m.optString("title"),
                    m.optString("description")));
        }
        return mods;
    }

    static void install(Context context, Mod mod,
                        String minecraft, String loader) throws Exception {
        if ("vanilla".equalsIgnoreCase(loader)) {
            throw new Exception("Mods vereisen een modloader zoals NeoForge, Fabric of Forge.");
        }
        String endpoint = "https://api.modrinth.com/v2/project/"
                + URLEncoder.encode(mod.id, "UTF-8") + "/version?game_versions="
                + URLEncoder.encode(new JSONArray().put(minecraft).toString(), "UTF-8")
                + "&loaders=" + URLEncoder.encode(
                new JSONArray().put(loader.toLowerCase(Locale.ROOT)).toString(), "UTF-8")
                + "&include_changelog=false";
        JSONArray versions = new JSONArray(readJson(endpoint));
        if (versions.length() == 0)
            throw new Exception("Geen passende modversie gevonden voor Minecraft " + minecraft + " / " + loader);
        JSONObject version = versions.getJSONObject(0);
        JSONArray files = version.optJSONArray("files");
        if (files == null || files.length() == 0) throw new Exception("Mod heeft geen downloadbestand");
        JSONObject chosen = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            if (f.optString("filename").endsWith(".jar")
                    && (f.optBoolean("primary") || chosen == null)) {
                chosen = f;
                if (f.optBoolean("primary")) break;
            }
        }
        if (chosen == null) throw new Exception("Geen geschikte .jar gevonden.");
        String filename = chosen.getString("filename");
        if (!filename.matches("[a-zA-Z0-9_.+() -]{1,180}\\.jar")
                || filename.equals(".") || filename.equals("..")) {
            throw new Exception("Ongeldige modbestandsnaam.");
        }
        long declaredSize = chosen.optLong("size", -1);
        if (declaredSize > MAX_MOD || declaredSize == 0)
            throw new Exception("Het modbestand is te groot of leeg");
        String download = chosen.getString("url");
        String sha512 = chosen.getJSONObject("hashes").getString("sha512");
        HttpURLConnection c = open(download, "cdn.modrinth.com");
        byte[] data;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int count;
            while ((count = in.read(chunk)) != -1) {
                if ((long) buffer.size() + count > MAX_MOD)
                    throw new Exception("Modbestand overschrijdt maximale grootte");
                buffer.write(chunk, 0, count);
            }
            data = buffer.toByteArray();
        } finally {
            c.disconnect();
        }
        if (data.length == 0 || (declaredSize > 0 && declaredSize != data.length))
            throw new Exception("Onvolledige mod-download");
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] hash = md.digest(data);
        StringBuilder digest = new StringBuilder();
        for (byte part : hash) digest.append(String.format(Locale.ROOT, "%02x", part & 0xff));
        if (!digest.toString().equalsIgnoreCase(sha512))
            throw new Exception("Bestand is afgekeurd: SHA-512-controle mislukt.");

        ModFolderManager.save(context, filename, data);
    }
}
