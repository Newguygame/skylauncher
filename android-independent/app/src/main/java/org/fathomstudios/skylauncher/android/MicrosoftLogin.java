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
 * No third-party Android launcher or engine receives tokens. Entitlement is verified
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
    private static final java.util.regex.Pattern MICROSOFT_ERROR_ID =
            java.util.regex.Pattern.compile("AADSTS[0-9]{5,8}");

    interface Listener {
        void deviceCode(String code, String verificationUri);
        void progress(String message);
        void success(String username, String uuid);
        void error(String message);
    }

    private static volatile long currentLoginId = 0;
    private static volatile String sessionMinecraftToken;
    private static volatile long sessionMinecraftExpiresAt;

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
        JSONObject profile = getJson("https://api.minecraftservices.com/minecraft/profile",
                "Bearer " + token);
        // Never persist the Minecraft bearer token as plaintext.
        sessionMinecraftToken = token;
        sessionMinecraftExpiresAt = SystemClock.elapsedRealtime()
                + Math.min(15 * 60 * 1000L, Math.max(60, mc.optLong("expires_in", 900) - 120) * 1000L);
        return profile;
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

    /**
     * Microsoft and Xbox may legitimately return non-2xx JSON errors. Device
     * polling 400 authorization_pending and slow_down are protocol states,
     * not login failures. All other errors are labeled with their failing
     * authorization stage and HTTP code, without logging bearer tokens.
     */
    private static JSONObject request(String endpoint, String method, String contentType,
                                      byte[] body, String authorization) throws Exception {
        URL url = new URL(endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                !isAllowedMicrosoftHost(url.getHost())) {
            throw new Exception("Authentication endpoint is not approved.");
        }
        final String stage = authorizationStage(endpoint);
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
            if (http >= 300 && http < 400)
                throw new Exception(stage + ": unexpected HTTP redirect " + http);
            InputStream stream = http >= 400 ? c.getErrorStream() : c.getInputStream();
            if (stream == null)
                throw new Exception(stage + ": HTTP " + http + " with empty response.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if ((long)out.size() + n > MAX_RESPONSE)
                        throw new Exception(stage + ": response size limit exceeded.");
                    out.write(buffer, 0, n);
                }
            }
            JSONObject json;
            try {
                json = new JSONObject(out.toString("UTF-8"));
            } catch (org.json.JSONException parse) {
                // Never display the raw server payload: it might contain
                // sensitive account data, access tokens or verification codes.
                throw new Exception(stage + ": HTTP " + http
                        + " (server returned invalid JSON).");
            }
            if (http >= 400) {
                String code = json.optString("error", "");
                if ("Microsoft token".equals(stage)
                        && ("authorization_pending".equals(code)
                            || "slow_down".equals(code)))
                    return json;
                String description = json.optString("error_description", "");
                java.util.regex.Matcher match = MICROSOFT_ERROR_ID.matcher(description);
                String aadsts = match.find() ? " " + match.group() : "";
                String xerr = "";
                long xboxError = json.optLong("XErr", -1L);
                if (xboxError > 0) xerr = " Xbox/XSTS=" + xboxError;
                String safeCode = code.isEmpty() ? "" : " [" + cleanError(code) + "]";
                String hint = "";
                if ("Microsoft device code".equals(stage)
                        || "Microsoft token".equals(stage)) {
                    if ("unauthorized_client".equals(code) || "invalid_client".equals(code))
                        hint = " SkyLauncher's Microsoft app registration may not "
                                + "permit public-client device authorization.";
                    else if ("invalid_scope".equals(code))
                        hint = " SkyLauncher's OAuth permission scopes are not allowed.";
                } else if (http == 403 && stage.startsWith("Minecraft")) {
                    hint = " Minecraft Services denied the login. The application "
                            + "may need Minecraft Services authorization.";
                } else if (http == 404 && "Minecraft profile".equals(stage)) {
                    hint = " No owned Minecraft Java profile was found.";
                }
                throw new Exception(stage + ": HTTP " + http + safeCode + aadsts
                        + xerr + hint);
            }
            if (json.has("error")) {
                String error = json.optString("error", "");
                if (!error.isEmpty()) throw new Exception(stage + ": "
                        + cleanError(error));
            }
            return json;
        } catch (java.net.SocketTimeoutException timeout) {
            throw new Exception(stage + ": network timeout.", timeout);
        } catch (java.net.UnknownHostException dns) {
            throw new Exception(stage + ": DNS lookup failed.", dns);
        } finally {
            c.disconnect();
        }
    }

    private static String authorizationStage(String endpoint) {
        if (endpoint.contains("/devicecode")) return "Microsoft device code";
        if (endpoint.contains("/oauth2/v2.0/token")) return "Microsoft token";
        if (endpoint.contains("user.auth.xboxlive.com")) return "Xbox Live";
        if (endpoint.contains("xsts.auth.xboxlive.com")) return "Xbox XSTS";
        if (endpoint.contains("login_with_xbox")) return "Minecraft authentication";
        if (endpoint.contains("minecraft/profile")) return "Minecraft profile";
        return "Microsoft/Minecraft authorization";
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

    /**
     * Refresh the official Minecraft access token from encrypted Microsoft
     * refresh credentials. Called on a worker thread; never log the token.
     * Returns a token only after Minecraft Services profile ownership check.
     */
    static synchronized String getVerifiedMinecraftAccessToken(Context context)
            throws Exception {
        if (sessionMinecraftToken != null && sessionMinecraftExpiresAt
                > SystemClock.elapsedRealtime() + 10_000L)
            return sessionMinecraftToken;
        android.content.SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encrypted = preferences.getString("refresh_enc", "");
        String iv = preferences.getString("refresh_iv", "");
        if (encrypted.isEmpty() || iv.isEmpty())
            throw new Exception("Sign in with your Microsoft account before playing.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(
                128, Base64.decode(iv, Base64.NO_WRAP)));
        String refresh = new String(cipher.doFinal(
                Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        Map<String,String> body = new HashMap<>();
        body.put("client_id", CLIENT_ID);
        body.put("grant_type", "refresh_token");
        body.put("scope", SCOPE);
        body.put("refresh_token", refresh);
        JSONObject renewed = postForm(TOKEN_URL, body);
        if (renewed.has("error"))
            throw new Exception("Microsoft session expired: " +
                    cleanError(renewed.optString("error")));
        JSONObject minecraft = minecraftProfile(renewed.getString("access_token"));
        String name = minecraft.optString("name");
        String uuid = minecraft.optString("id");
        if (name.isEmpty() || uuid.isEmpty())
            throw new Exception("Your account does not have an accessible Minecraft Java profile.");
        storeAccount(context, renewed.optString("refresh_token", refresh), name, uuid);
        return sessionMinecraftToken;
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
        sessionMinecraftToken = null;
        sessionMinecraftExpiresAt = 0;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(KEYSTORE_ALIAS);
        } catch (Exception ignored) { }
    }
}
