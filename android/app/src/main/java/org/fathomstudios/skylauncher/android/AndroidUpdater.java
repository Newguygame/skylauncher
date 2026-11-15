package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.content.pm.Signature;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * Update check for official SkyLauncher Android release APKs.
 * Rejects APKs whose package or signing identity does not match this installation.
 */
final class AndroidUpdater {
    private static final String RELEASE_API =
            "https://api.github.com/repos/Newguygame/skylauncher/releases?per_page=30";
    private static final long MAX_APK_BYTES = 260L * 1024L * 1024L;

    static final class Release {
        String tag;
        String assetUrl;
        String sha256;
        long size;
    }

    interface Result<T> {
        void success(T data);
        void failure(String error);
    }

    interface Progress {
        void onProgress(String text);
    }

    private AndroidUpdater() { }

    static void check(Activity activity, Result<Release> callback) {
        new Thread(() -> {
            try {
                JSONArray releases = new JSONArray(readJson(RELEASE_API));
                Release release = null;
                for (int ri = 0; ri < releases.length() && release == null; ri++) {
                    JSONObject json = releases.getJSONObject(ri);
                    String tag = json.optString("tag_name", "");
                    if (!tag.startsWith("android-v") || json.optBoolean("draft")
                            || json.optBoolean("prerelease")) continue;
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
                        if (!name.endsWith(".apk") || !name.contains("skylauncher")
                                || !name.contains("android")) continue;
                        release = new Release();
                        release.tag = tag;
                        release.assetUrl = asset.getString("browser_download_url");
                        release.size = asset.optLong("size", -1);
                        String digest = asset.optString("digest", "");
                        release.sha256 = digest.startsWith("sha256:")
                                ? digest.substring("sha256:".length()) : "";
                        break;
                    }
                }
                if (release == null) {
                    activity.runOnUiThread(() -> callback.failure(
                            "Er is nog geen Android-APK gepubliceerd in de nieuwste officiële GitHub-release."));
                    return;
                }
                if (release.size <= 0 || release.size > MAX_APK_BYTES) {
                    throw new Exception("De publicatie heeft geen geldige APK-grootte.");
                }
                Release ready = release;
                activity.runOnUiThread(() -> callback.success(ready));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Onbekende netwerkfout.";
                activity.runOnUiThread(() -> callback.failure(msg));
            }
        }, "SkyLauncher-Update-Check").start();
    }

    static void download(Activity activity, Release release, Progress progress,
                         Result<File> callback) {
        new Thread(() -> {
            File partial = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(release.assetUrl);
                if (!"https".equalsIgnoreCase(url.getProtocol())
                        || !"github.com".equalsIgnoreCase(url.getHost())) {
                    throw new Exception("De update is niet van GitHub afkomstig.");
                }
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || (!dir.exists() && !dir.mkdirs()))
                    throw new Exception("Er is geen downloadmap beschikbaar.");
                partial = new File(dir, "SkyLauncher-update.partial");
                File apk = new File(dir, "SkyLauncher-Android-update.apk");
                if (partial.exists() && !partial.delete())
                    throw new Exception("Tijdelijke update kon niet worden verwijderd.");

                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(35000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "SkyLauncherAndroid/0.4");
                if (connection.getResponseCode() != 200) {
                    throw new Exception("Downloadserver gaf HTTP " + connection.getResponseCode());
                }
                URL endpoint = connection.getURL();
                String host = endpoint.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equalsIgnoreCase(endpoint.getProtocol())
                        || !(host.equals("github.com") || host.endsWith(".githubusercontent.com"))) {
                    throw new Exception("Onbekende downloadserver voor update.");
                }

                long size = 0;
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    int last = -1;
                    while ((n = input.read(buffer)) != -1) {
                        size += n;
                        if (size > MAX_APK_BYTES)
                            throw new Exception("De update is groter dan toegestaan.");
                        sha.update(buffer, 0, n);
                        output.write(buffer, 0, n);
                        int percent = (int) (size * 100 / release.size);
                        if (percent >= last + 10) {
                            last = percent;
                            activity.runOnUiThread(() ->
                                    progress.onProgress("SkyLauncher downloaden: " + percent + "%"));
                        }
                    }
                    output.getFD().sync();
                }
                if (size != release.size) throw new Exception("De APK is onvolledig gedownload.");
                String digest = hex(sha.digest());
                if (!release.sha256.isEmpty() &&
                        !release.sha256.equalsIgnoreCase(digest)) {
                    throw new Exception("SHA-256-controle mislukt: update geweigerd.");
                }
                validatePackageAndCertificate(activity, partial);
                if (apk.exists() && !apk.delete())
                    throw new Exception("Vorige updatebestand kon niet vervangen worden.");
                if (!partial.renameTo(apk))
                    throw new Exception("APK kon niet worden klaargezet.");
                activity.runOnUiThread(() -> callback.success(apk));
            } catch (Exception e) {
                if (partial != null) partial.delete();
                String msg = e.getMessage() != null ? e.getMessage() : "Download mislukt";
                activity.runOnUiThread(() -> callback.failure(msg));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "SkyLauncher-Update-Download").start();
    }

    private static void validatePackageAndCertificate(Activity activity, File apk) throws Exception {
        PackageManager pm = activity.getPackageManager();
        final int flags = Build.VERSION.SDK_INT >= 28 ?
                PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null || !activity.getPackageName().equals(archive.packageName))
            throw new Exception("Download is geen SkyLauncher Android APK.");
        PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), flags);
        if (archive.getLongVersionCode() <= installed.getLongVersionCode())
            throw new Exception("Deze versie is niet nieuwer dan de geïnstalleerde SkyLauncher.");
        Signature[] current = signatures(installed);
        Signature[] next = signatures(archive);
        if (current == null || next == null || current.length == 0 ||
                next.length == 0 || !sameSigners(current, next)) {
            throw new Exception("Update is anders ondertekend. Android accepteert geen update "
                    + "met een andere handtekening. Er is één vaste signing key nodig.");
        }
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return null;
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    private static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a.length != b.length) return false;
        for (Signature signature : a) {
            boolean found = false;
            for (Signature match : b) {
                if (signature.equals(match)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String readJson(String src) throws Exception {
        URL endpoint = new URL(src);
        if (!"https".equalsIgnoreCase(endpoint.getProtocol()) ||
                !"api.github.com".equalsIgnoreCase(endpoint.getHost())) {
            throw new Exception("Geen vertrouwde update-API.");
        }
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(18000);
        connection.setReadTimeout(25000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "SkyLauncherAndroid/0.4");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("GitHub antwoordde HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 1_000_000)
                        throw new Exception("Release-informatie is te groot.");
                    out.write(buffer, 0, n);
                }
            }
            return out.toString("UTF-8");
        } finally { connection.disconnect(); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) builder.append(String.format(Locale.ROOT, "%02x", b & 255));
        return builder.toString();
    }
}
