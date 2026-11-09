package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Downloads the stable APK from the Amethyst project's official GitHub releases.
 * The normal Android package installer MUST request user confirmation.
 */
final class AmethystInstaller {
    static final String PACKAGE = "org.angelauramc.amethyst";
    static final String RELEASE_APK =
            "https://github.com/AngelAuraMC/Amethyst-Android/releases/latest/download/Amethyst.apk";
    private static final long MAX_DOWNLOAD_BYTES = 350L * 1024L * 1024L;
    private static final long MIN_DOWNLOAD_BYTES = 100_000L;

    interface Listener {
        void onProgress(String message);
        void onComplete(File apk);
        void onError(String message);
    }

    private AmethystInstaller() { }

    static void download(Activity activity, Listener listener) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            File temporary = null;
            try {
                File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (directory == null || (!directory.exists() && !directory.mkdirs())) {
                    throw new Exception("App-opslag voor downloads is niet beschikbaar.");
                }
                temporary = new File(directory, "Amethyst.partial");
                File destination = new File(directory, "Amethyst.apk");
                if (temporary.exists() && !temporary.delete()) {
                    throw new Exception("Oude tijdelijke download kon niet worden verwijderd.");
                }

                connection = (HttpURLConnection) new URL(RELEASE_APK).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "SkyLauncher-Android/0.1");
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new Exception("GitHub gaf HTTP " + status + ". Probeer later opnieuw.");
                }
                // Only accept GitHub-hosted release assets, never arbitrary third-party APKs.
                URL finalUrl = connection.getURL();
                String host = finalUrl.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equalsIgnoreCase(finalUrl.getProtocol())
                        || !(host.equals("github.com") || host.endsWith(".githubusercontent.com"))) {
                    throw new Exception("De APK komt niet van een vertrouwde GitHub-downloadserver.");
                }
                long declaredLength = connection.getContentLengthLong();
                if (declaredLength > MAX_DOWNLOAD_BYTES) {
                    throw new Exception("APK is onverwacht groot.");
                }
                notifyProgress(activity, listener, "Amethyst wordt gedownload...");
                long downloaded = 0;
                int lastPercent = -1;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    int bytes;
                    while ((bytes = input.read(buffer)) != -1) {
                        downloaded += bytes;
                        if (downloaded > MAX_DOWNLOAD_BYTES) {
                            throw new Exception("Download is te groot.");
                        }
                        output.write(buffer, 0, bytes);
                        if (declaredLength > 0) {
                            int percent = (int) (100 * downloaded / declaredLength);
                            if (percent >= lastPercent + 5) {
                                lastPercent = percent;
                                notifyProgress(activity, listener,
                                        "Amethyst downloaden: " + percent + "%");
                            }
                        }
                    }
                    output.getFD().sync();
                }
                if (downloaded < MIN_DOWNLOAD_BYTES) {
                    throw new Exception("Download is te klein om een geldige APK te zijn.");
                }
                try (ZipFile zip = new ZipFile(temporary)) {
                    if (zip.getEntry("AndroidManifest.xml") == null
                            || zip.getEntry("classes.dex") == null) {
                        throw new Exception("Het gedownloade bestand is geen geldige Android APK.");
                    }
                }
                PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(
                        temporary.getAbsolutePath(), 0);
                if (info == null || !PACKAGE.equals(info.packageName)) {
                    throw new Exception("Het bestand is geen officiële Amethyst-app (pakket-ID klopt niet).");
                }
                if (destination.exists() && !destination.delete()) {
                    throw new Exception("Oude download kon niet vervangen worden.");
                }
                if (!temporary.renameTo(destination)) {
                    throw new Exception("Kon de APK niet klaarzetten voor installatie.");
                }
                activity.runOnUiThread(() -> listener.onComplete(destination));
            } catch (Exception e) {
                if (temporary != null && temporary.exists()) {
                    // Remove incomplete or invalid downloads.
                    temporary.delete();
                }
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                activity.runOnUiThread(() -> listener.onError(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "Amethyst-Official-APK-Download").start();
    }

    private static void notifyProgress(Activity activity, Listener listener, String message) {
        activity.runOnUiThread(() -> listener.onProgress(message));
    }

    static void openInstaller(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(install);
    }
}
