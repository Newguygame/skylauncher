package org.fathomstudios.skylauncher.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.HashMap;
import java.util.Map;

/**
 * Independent Microsoft device-code sign-in for SkyLauncher Android.
 * Tokens are never copied to Amethyst. Minecraft entitlement must be verified
 * through Minecraft Services; the selected launcher handles its own accounts.
 */
final class MicrosoftLogin {
    // Public OAuth client ID also used by the SkyLauncher desktop edition.
    // The app registration must permit device-code public-client OAuth and
    // Minecraft Services access. A 403/unauthorized_client is a real failure.
    static final String CLIENT_ID = "54473e32-df8f-42e9-a649-9419b0dab9d3";
    private static final String SCOPE = "XboxLive.signin offline_access openid profile";
    private static final String DEVICE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String PREFS = "skylauncher_minecraft_account";
    private static final String KEYSTORE_ALIAS = "SkyLauncherAndroidMicrosoftRefresh";
    private static final int MAX_RESPONSE = 512_000;

    interface Listener {
        void deviceCode(String code, String verificationUri);
        void progress(String message);
        void success(String username, String uuid);
        void error(String message);
    }

    private static volatile long currentLoginId = 0;

    private MicrosoftLogin() { }

    static synchronized void cancel() {
        currentLoginId++;
    }

    static synchronized long begin() {
        currentLoginId++;
        return currentLoginId;
    }

    private static boolean cancelled(long run) {
        return currentLoginId != run;
    }

    static void login(Context context, Listener listener) {
        long run = begin();
        new Thread(() -> {
            try {
                Map<String,String> request = new HashMap<>();
                request.put("client_id", CLIENT_ID);
                request.put("scope", SCOPE);
                JSONObject device = postForm(DEVICE_URL, request);
                String code = device.getString("user_code");
                String url = device.getString("verification_uri");
                String deviceCode = device.getString("device_code");
                int interval = Math.max(5, device.optInt("interval", 5));
                int expires = Math.min(1200, Math.max(60, device.optInt("expires_in", 900)));
                if (!url.startsWith("https://microsoft.com/")
                        && !url.startsWith("https://www.microsoft.com/")) {
                    throw new Exception("Microsoft gaf een onbekende aanmeldwebsite terug.");
                }
                if (cancelled(run)) return;
                deliver(context, () -> listener.deviceCode(code, url));
                long end = SystemClock.elapsedRealtime() + expires * 1000L;
                while (SystemClock.elapsedRealtime() < end && !cancelled(run)) {
                    Thread.sleep(interval * 1000L);
                    if (cancelled(run)) return;
                    Map<String,String> values = new HashMap<>();
                    values.put("client_id", CLIENT_ID);
                    values.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                    values.put("device_code", deviceCode);
                    JSONObject response = postForm(TOKEN_URL, values);
                    String error = response.optString("error", "");
                    if ("authorization_pending".equals(error)) continue;
                    if ("slow_down".equals(error)) { interval = Math.min(30, interval + 5); continue; }
                    if (!error.isEmpty()) {
                        throw new Exception("Microsoft-aanmelding: " + cleanError(error));
                    }
                    String accessToken = response.getString("access_token");
                    String refreshToken = response.optString("refresh_token", "");
                    if (cancelled(run)) return;
                    deliver(context, () -> listener.progress("Microsoft verbonden. Minecraft-licentie controleren..."));
                    JSONObject profile = minecraftProfile(accessToken);
                    String name = profile.optString("name", "");
                    String uuid = profile.optString("id", "");
                    if (name.isEmpty() || uuid.isEmpty()) {
                        throw new Exception("Geen geldig Minecraft Java-profiel aangetroffen.");
                    }
                    if (cancelled(run)) return;
                    if (!refreshToken.isEmpty()) storeAccount(context, refreshToken, name, uuid);
                    else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("username", name).putString("uuid", uuid).apply();
                    deliver(context, () -> listener.success(name, uuid));
                    return;
                }
                if (!cancelled(run)) throw new Exception("De Microsoft-aanmeldcode is verlopen.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (!cancelled(run)) {
                    String msg = e.getMessage() == null ? "Onbekende aanmeldfout" : e.getMessage();
                    deliver(context, () -> listener.error(msg));
                }
            }
        }, "SkyLauncher-Microsoft-Device-Code").start();
    }

    private static JSONObject minecraftProfile(String microsoftAccessToken) throws Exception {
        JSONObject props = new JSONObject()
                .put("AuthMethod", "RPS")
                .put("SiteName", "user.auth.xboxlive.com")
                .put("RpsTicket", "d=" + microsoftAccessToken);
        JSONObject xbl = postJson("https://user.auth.xboxlive.com/user/authenticate",
                new JSONObject().put("Properties", props)
                        .put("RelyingParty", "http://auth.xboxlive.com")
                        .put("TokenType", "JWT"));
        JSONObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize",
                new JSONObject().put("Properties",
                                new JSONObject().put("SandboxId", "RETAIL")
                                        .put("UserTokens", new JSONArray().put(xbl.getString("Token"))))
                        .put("RelyingParty", "rp://api.minecraftservices.com/")
                        .put("TokenType", "JWT"));
        String userHash = xsts.getJSONObject("DisplayClaims")
                .getJSONArray("xui").getJSONObject(0).getString("uhs");
        String minecraftIdentity = "XBL3.0 x=" + userHash + ";" + xsts.getString("Token");
        JSONObject mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                new JSONObject().put("identityToken", minecraftIdentity)
                        .put("ensureLegacyEnabled", true));
        String token = mc.getString("access_token");
        return getJson("https://api.minecraftservices.com/minecraft/profile",
                "Bearer " + token);
    }

    private static JSONObject postForm(String endpoint, Map<String,String> values) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String,String> e : values.entrySet()) {
            if (body.length() != 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return request(endpoint, "POST", "application/x-www-form-urlencoded",
                body.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        return request(endpoint, "POST", "application/json",
                payload.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static JSONObject getJson(String endpoint, String authorization) throws Exception {
        return request(endpoint, "GET", null, null, authorization);
    }

    private static JSONObject request(String endpoint, String method, String contentType,
                                       byte[] body, String authorization) throws Exception {
        URL url = new URL(endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                !isAllowedMicrosoftHost(url.getHost())) {
            throw new Exception("Onveilig authenticatie-adres.");
        }
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(18000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (authorization != null) c.setRequestProperty("Authorization", authorization);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
        }
        try {
            int http = c.getResponseCode();
            InputStream stream = http >= 400 ? c.getErrorStream() : c.getInputStream();
            if (stream == null) throw new Exception("Authserver gaf geen antwoord (" + http + ")");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = stream) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) {
                    if (out.size() + n > MAX_RESPONSE)
                        throw new Exception("Authserver-antwoord te groot.");
                    out.write(b, 0, n);
                }
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            if (http >= 400 && !"authorization_pending".equals(json.optString("error")) &&
                    !"slow_down".equals(json.optString("error"))) {
                String safeCode = json.optString("error", "HTTP " + http);
                if (http == 403 && endpoint.contains("minecraftservices.com")) {
                    throw new Exception("Minecraft Services weigerde de app (403). "
                            + "Controleer de Microsoft/Minecraft-toestemming van de SkyLauncher-appregistratie.");
                }
                if (http == 404 && endpoint.contains("minecraft/profile")) {
                    throw new Exception("Dit account heeft geen geldig Minecraft Java-profiel.");
                }
                throw new Exception("Microsoft/Minecraft antwoord: " + cleanError(safeCode));
            }
            return json;
        } finally { c.disconnect(); }
    }

    private static boolean isAllowedMicrosoftHost(String host) {
        return "login.microsoftonline.com".equalsIgnoreCase(host)
                || "user.auth.xboxlive.com".equalsIgnoreCase(host)
                || "xsts.auth.xboxlive.com".equalsIgnoreCase(host)
                || "api.minecraftservices.com".equalsIgnoreCase(host);
    }

    private static String cleanError(String error) {
        return error.replaceAll("[^A-Za-z0-9_ .-]", "").substring(0, Math.min(100,
                error.replaceAll("[^A-Za-z0-9_ .-]", "").length()));
    }

    private static void deliver(Context context, Runnable runnable) {
        new android.os.Handler(context.getMainLooper()).post(runnable);
    }

    private static void storeAccount(Context context, String refreshToken,
                                     String username, String uuid) throws Exception {
        SecretKey key = key();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("refresh_enc", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("refresh_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString("username", username).putString("uuid", uuid).apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry existing = store.getEntry(KEYSTORE_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry)
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    static String username(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("username", "");
    }

    static String uuid(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("uuid", "");
    }

    static void signOut(Context context) {
        cancel();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(KEYSTORE_ALIAS);
        } catch (Exception ignored) { }
    }
}
