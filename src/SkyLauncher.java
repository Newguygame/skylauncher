/*
 * SkyLauncher (Java edition) - single file, JDK only, no Tk / Python / Pillow.
 * Run:   java SkyLauncher.java          (Java 17+; the JDK, not just a JRE, is needed for this form)
 * Or:    javac SkyLauncher.java && java SkyLauncher     (a JRE is enough afterwards)
 * Package: jar --create --file SkyLauncher.jar --main-class SkyLauncher *.class
 *
 * Uses the SAME data as the Python version: ~/.ghmodlauncher/config.json, instances, runtime, logins.
 * Unknown config keys are preserved, so you can switch between the two versions freely.
 */
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.Certificate;


public class SkyLauncher {
    static final String VERSION = "3.1.7";
    static final Path HOME = Paths.get(System.getProperty("user.home"));
    static final Path BASE = HOME.resolve(".ghmodlauncher");
    static final Path CONFIG = BASE.resolve("config.json");
    static final String[] LOADERS = {"fabric", "quilt", "forge", "neoforge"};
    static final String OS = osName(), ARCH = archName();
    static final String SEP = File.pathSeparator;
    static final String MSA_CLIENT_ID = "54473e32-df8f-42e9-a649-9419b0dab9d3";
    static final String MSA_SCOPE = "XboxLive.signin offline_access openid profile";
    static final String[] PACK_FOLDERS = {"mods", "config", "imported_world_presets", "resourcepacks", "shaderpacks",
            "datapacks", "kubejs", "scripts", "defaultconfigs"};
    static final Set<String> SKIP_DEPS = Set.of("minecraft", "java", "forge", "neoforge", "fabricloader", "fabric-loader",
            "quilt_loader", "quilt_base", "fml", "javafml", "lowcodefml", "mixinextras");
    static final Pattern OTHER_MC = Pattern.compile("(?<![\\d.])1\\.(?:1[4-9]|2\\d)(?:\\.\\d+)?(?!\\d)");

    static String osName() {
        String n = System.getProperty("os.name", "").toLowerCase();
        return n.contains("win") ? "windows" : n.contains("mac") ? "osx" : n.contains("linux") || n.contains("nix") || n.contains("nux") ? "linux" : "unknown";
    }
    static String archName() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        if (a.equals("aarch64") || a.equals("arm64")) return "arm64";
        if (a.equals("x86") || a.equals("i386") || a.equals("i686")) return "x86";
        return "x86_64";
    }

    // ============================================================ JSON (same shapes the Python version wrote)
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked") static List<Object> l(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }
    static String s(Object o) { return o == null ? "" : o instanceof Double d && d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(o); }
    static long n(Object o, long d) { return o instanceof Number x ? x.longValue() : d; }
    static boolean b(Object o) { return o instanceof Boolean x && x; }

    static final class Json {
        final String t; int i;
        Json(String t) { this.t = t.startsWith("\uFEFF") ? t.substring(1) : t; }
        static Object parse(String t) { return new Json(t).val(); }
        void ws() { while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++; }
        Object val() {
            ws(); char c = t.charAt(i);
            if (c == '{') {
                i++; var o = new LinkedHashMap<String, Object>(); ws();
                if (t.charAt(i) == '}') { i++; return o; }
                while (true) { ws(); String k = str(); ws(); i++; o.put(k, val()); ws(); if (t.charAt(i++) == '}') return o; }
            }
            if (c == '[') {
                i++; var a = new ArrayList<Object>(); ws();
                if (t.charAt(i) == ']') { i++; return a; }
                while (true) { a.add(val()); ws(); if (t.charAt(i++) == ']') return a; }
            }
            if (c == '"') return str();
            if (t.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (t.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (t.startsWith("null", i)) { i += 4; return null; }
            int j = i;
            while (i < t.length() && "+-0123456789.eE".indexOf(t.charAt(i)) >= 0) i++;
            String num = t.substring(j, i);
            if (num.matches("-?\\d+")) { try { return Long.parseLong(num); } catch (NumberFormatException e) { return Double.parseDouble(num); } }
            return Double.parseDouble(num);
        }
        String str() {
            i++; StringBuilder b = new StringBuilder();
            while (true) {
                char c = t.charAt(i++);
                if (c == '"') break;
                if (c != '\\') { b.append(c); continue; }
                char e = t.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(t.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
            return b.toString();
        }
        static String write(Object o) { StringBuilder b = new StringBuilder(); w(b, o, 0); return b.toString(); }
        static void w(StringBuilder b, Object o, int d) {
            String pad = "  ".repeat(d + 1), end = "  ".repeat(d);
            if (o == null) b.append("null");
            else if (o instanceof Map<?, ?> mm) {
                if (mm.isEmpty()) { b.append("{}"); return; }
                b.append("{\n"); int k = 0;
                for (var e : mm.entrySet()) { b.append(pad); q(b, String.valueOf(e.getKey())); b.append(": "); w(b, e.getValue(), d + 1); b.append(++k < mm.size() ? ",\n" : "\n"); }
                b.append(end).append('}');
            } else if (o instanceof Collection<?> c) {
                if (c.isEmpty()) { b.append("[]"); return; }
                b.append("[\n"); int k = 0;
                for (Object x : c) { b.append(pad); w(b, x, d + 1); b.append(++k < c.size() ? ",\n" : "\n"); }
                b.append(end).append(']');
            } else if (o instanceof String x) q(b, x);
            else b.append(o);
        }
        static void q(StringBuilder b, String x) {
            b.append('"');
            for (char c : x.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                    default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
                }
            }
            b.append('"');
        }
    }

    // ============================================================ HTTP
    static final HttpClient HC = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30)).build();
    // Only this server and port may use the dedicated certificate trust store.
    // Certificate verification and hostname/IP validation are NEVER disabled.
    static final String PERSONAL_CF_HOST = "185.83.154.171";
    static final int PERSONAL_CF_PORT = 25568;
    static final String PERSONAL_CF_URL = "https://" + PERSONAL_CF_HOST + ":" + PERSONAL_CF_PORT;
    static final HttpClient PERSONAL_CF_CLIENT = createPersonalCfClient();
    static HttpClient createPersonalCfClient() {
        try (InputStream certInput = SkyLauncher.class.getResourceAsStream("/bridge-cert.pem")) {
            if (certInput == null) throw new IllegalStateException("Missing bundled VPS certificate");
            Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(certInput);
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("personal-skylauncher-vps", cert);
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tm.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(context).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(30)).build();
        } catch (Exception ex) { throw new IllegalStateException("Could not load pinned VPS certificate", ex); }
    }
    static boolean personalCfOrigin(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && PERSONAL_CF_HOST.equals(uri.getHost())
                && uri.getPort() == PERSONAL_CF_PORT;
    }

    static final class HttpErr extends IOException {
        final int code; final String body;
        HttpErr(int code, String body, String url) { super("HTTP " + code + " for " + url); this.code = code; this.body = body; }
    }
    static HttpResponse<InputStream> open(String method, String url, Map<String, String> h, byte[] body) throws IOException {
        String cur = url, firstHost = URI.create(url).getHost();
        boolean personalBridge = personalCfOrigin(URI.create(url));
        for (int hop = 0; hop < 8; hop++) {
            if (personalBridge && !personalCfOrigin(URI.create(cur)))
                throw new IOException("Blocked redirect outside the secure personal VPS");
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(cur)).timeout(Duration.ofSeconds(300))
                    .setHeader("User-Agent", "skylauncher-java");
            boolean sameHost = Objects.equals(URI.create(cur).getHost(), firstHost);
            if (h != null) for (var e : h.entrySet()) {
                if (e.getKey().equalsIgnoreCase("Authorization") && !sameHost) continue; // never leak tokens to CDNs
                rb.setHeader(e.getKey(), e.getValue());
            }
            rb.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<InputStream> r;
            try { r = (personalBridge ? PERSONAL_CF_CLIENT : HC).send(rb.build(), HttpResponse.BodyHandlers.ofInputStream()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            int c = r.statusCode();
            Optional<String> loc = r.headers().firstValue("location");
            if (c >= 300 && c < 400 && loc.isPresent()) {
                r.body().close();
                cur = URI.create(cur).resolve(loc.get()).toString();
                if (c == 303) { method = "GET"; body = null; }
                continue;
            }
            if (c >= 400) {
                String bd; try (InputStream in = r.body()) { bd = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                throw new HttpErr(c, bd, cur);
            }
            return r;
        }
        throw new IOException("too many redirects: " + url);
    }
    static byte[] bytes(String url, Map<String, String> h) throws IOException {
        try (InputStream in = open("GET", url, h, null).body()) { return in.readAllBytes(); }
    }
    static Object getJson(String url, Map<String, String> h) throws IOException {
        Map<String, String> hh = new HashMap<>(); hh.put("Accept", "application/json"); if (h != null) hh.putAll(h);
        return Json.parse(new String(bytes(url, hh), StandardCharsets.UTF_8));
    }
    static Object postJson(String url, Object body, Map<String, String> h) throws IOException {
        Map<String, String> hh = new HashMap<>(Map.of("Content-Type", "application/json", "Accept", "application/json"));
        if (h != null) hh.putAll(h);
        try (InputStream in = open("POST", url, hh, Json.write(body).getBytes(StandardCharsets.UTF_8)).body()) {
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    static Object postForm(String url, Map<String, String> f) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var e : f.entrySet()) sb.append(sb.length() > 0 ? "&" : "").append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        try (InputStream in = open("POST", url, Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
                sb.toString().getBytes(StandardCharsets.UTF_8)).body()) {
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    static String enc(String x) { return URLEncoder.encode(x, StandardCharsets.UTF_8).replace("+", "%20"); }

    static String hex(byte[] d) { StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString(); }
    static String digest(String algo, byte[] data) { try { return hex(MessageDigest.getInstance(algo).digest(data)); } catch (Exception e) { throw new RuntimeException(e); } }
    static String fileDigest(String algo, Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance(algo); byte[] buf = new byte[1 << 16]; int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    /** Download url -> dest unless a valid copy already exists. */
    static void fetchFile(String url, Path dest, String sha1, Long size) throws IOException {
        if (Files.exists(dest)) {
            if (size != null) { if (Files.size(dest) == size && (sha1 == null || fileDigest("SHA-1", dest).equalsIgnoreCase(sha1))) return; }
            else if (sha1 != null) { if (fileDigest("SHA-1", dest).equalsIgnoreCase(sha1)) return; }
            else return;
        }
        if (url == null || url.isEmpty()) throw new IOException("missing and no url: " + dest);
        Files.createDirectories(dest.getParent());
        IOException last = null;
        for (int a = 0; a < 3; a++) {
            Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
            try (InputStream in = open("GET", url, null, null).body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                if (size != null && Files.size(tmp) != size) throw new IOException("size mismatch");
                if (sha1 != null && !fileDigest("SHA-1", tmp).equalsIgnoreCase(sha1)) throw new IOException("sha1 mismatch");
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) { last = e; Files.deleteIfExists(tmp); }
        }
        throw new IOException("download failed: " + url + " (" + last + ")");
    }
    interface Log { void log(String s); }
    static void downloadAll(List<Object[]> items, Log log, String label) throws Exception {
        if (items.isEmpty()) return;
        ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Object[] it : items) fs.add(ex.submit(() -> { fetchFile((String) it[0], (Path) it[1], (String) it[2], (Long) it[3]); return null; }));
            int done = 0;
            for (Future<?> f : fs) {
                try { f.get(); } catch (ExecutionException e) { throw new IOException(label + " failed: " + e.getCause().getMessage()); }
                if (++done % 500 == 0) log.log("  " + label + ": " + done + "/" + items.size());
            }
        } finally { ex.shutdownNow(); }
    }

    // ============================================================ config (compatible with the Python version)
    static Map<String, Object> defaultInstance(String name, String mc, String loader) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("mc_version", mc); i.put("loader", loader); i.put("loader_version", "");
        i.put("mods_dir", BASE.resolve("instances").resolve(name).resolve(".minecraft").resolve("mods").toString());
        i.put("java_path", ""); i.put("memory_mb", "4096"); i.put("memory_inherit", true);
        i.put("jvm_args", ""); i.put("launch_cmd", "");
        i.put("version_id", ""); i.put("installed_key", ""); i.put("mods", new LinkedHashMap<String, Object>());
        i.put("pack_repo", ""); i.put("pack_ref", ""); i.put("window_width", ""); i.put("window_height", "");
        i.put("fullscreen", false); i.put("game_args", ""); i.put("env_vars", ""); i.put("pre_launch_cmd", "");
        i.put("launcher", "builtin"); i.put("pack_source", ""); i.put("pack_sha", ""); i.put("pack_url", "");
        i.put("pack_name", ""); i.put("pack_version", ""); i.put("pack_skip_sha", "");
        return i;
    }
    static Map<String, Object> loadConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("token", ""); cfg.put("active", "Default"); cfg.put("instances", new LinkedHashMap<String, Object>());
        cfg.put("dep_map", new LinkedHashMap<String, Object>()); cfg.put("runtime_dir", BASE.resolve("runtime").toString());
        cfg.put("msa_client_id", ""); cfg.put("account", null); cfg.put("curseforge_api_key", ""); cfg.put("modrinth_token", "");
        cfg.put("curseforge_bridge_url", PERSONAL_CF_URL); cfg.put("mod_search_provider", "Modrinth");
        cfg.put("generic_memory_mb", "4096"); cfg.put("generic_jvm_args", "");
        try { cfg.putAll(m(Json.parse(Files.readString(CONFIG)))); } catch (Exception ignored) { }
        Map<String, Object> insts = m(cfg.get("instances"));
        cfg.put("instances", insts);
        if (insts.isEmpty()) insts.put("Default", defaultInstance("Default", "", "fabric"));
        for (var e : insts.entrySet()) {
            Map<String, Object> i = m(e.getValue());
            // Legacy builds wrote 4096 MB into every pack, even when the user did not
            // specify RAM. Treat that legacy default as inherited, and preserve any
            // different old value as a pack-specific override.
            if (!i.containsKey("memory_inherit")) {
                String legacy = s(i.get("memory_mb")).trim();
                i.put("memory_inherit", legacy.isEmpty() || legacy.equals("4096"));
            }
            for (var d : defaultInstance(e.getKey(), "", "fabric").entrySet()) i.putIfAbsent(d.getKey(), d.getValue());
            insts.put(e.getKey(), i);
        }
        if (!insts.containsKey(s(cfg.get("active")))) cfg.put("active", insts.keySet().iterator().next());
        return cfg;
    }
    static synchronized void saveConfig(Map<String, Object> cfg) {
        try {
            Files.createDirectories(BASE);
            Files.writeString(CONFIG, Json.write(cfg), StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(CONFIG, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) { }
        } catch (IOException e) { System.err.println("could not save config: " + e); }
    }

    static int validRamMb(Object value, int fallback) {
        try {
            int mb = Integer.parseInt(s(value).trim());
            return mb >= 512 && mb <= 262144 ? mb : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }
    static int effectiveMemoryMb(Map<String, Object> cfg, Map<String, Object> instance) {
        int general = validRamMb(cfg.get("generic_memory_mb"), 4096);
        if (b(instance.get("memory_inherit"))) return general;
        return validRamMb(instance.get("memory_mb"), general);
    }
    static String effectiveJvmArgs(Map<String, Object> cfg, Map<String, Object> instance) {
        String perPack = s(instance.get("jvm_args")).trim();
        return perPack.isEmpty() ? s(cfg.get("generic_jvm_args")).trim() : perPack;
    }

    // ============================================================ Microsoft / Minecraft auth
    static Map<String, Object> buildAccount(Map<String, Object> msa, String clientId, Log log) throws IOException {
        log.log("Authenticating with Xbox Live...");
        Map<String, Object> props = new LinkedHashMap<>(Map.of("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com", "RpsTicket", "d=" + s(msa.get("access_token"))));
        Map<String, Object> xbl = m(postJson("https://user.auth.xboxlive.com/user/authenticate",
                Map.of("Properties", props, "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT"), null));
        log.log("Getting XSTS token...");
        Map<String, Object> xsts;
        try {
            xsts = m(postJson("https://xsts.auth.xboxlive.com/xsts/authorize", Map.of("Properties",
                    Map.of("SandboxId", "RETAIL", "UserTokens", List.of(s(xbl.get("Token")))),
                    "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT"), null));
        } catch (HttpErr e) {
            String hint = e.body.contains("2148916233") ? "No Xbox account - open xbox.com once and create one"
                    : e.body.contains("2148916238") ? "Child account - must be added to a Microsoft Family by an adult"
                    : e.body.contains("2148916235") ? "Xbox Live not available in this country" : e.body;
            throw new IOException("XSTS failed: " + hint);
        }
        String uhs = s(m(l(m(xsts.get("DisplayClaims")).get("xui")).get(0)).get("uhs"));
        log.log("Logging into Minecraft services...");
        Map<String, Object> mc = m(postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                Map.of("identityToken", "XBL3.0 x=" + uhs + ";" + s(xsts.get("Token")), "ensureLegacyEnabled", true), null));
        String tok = s(mc.get("access_token"));
        Map<String, Object> prof = m(getJson("https://api.minecraftservices.com/minecraft/profile", Map.of("Authorization", "Bearer " + tok)));
        String raw = s(prof.get("id"));
        String dashed = raw.length() == 32 ? raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20) : raw;
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "msa"); a.put("name", prof.get("name")); a.put("uuid", dashed); a.put("uuid_nodash", raw.replace("-", ""));
        a.put("access_token", tok); a.put("mc_expires_at", now + n(mc.get("expires_in"), 86400) - 60);
        a.put("msa_access_token", msa.get("access_token")); a.put("msa_refresh_token", msa.get("refresh_token"));
        a.put("msa_expires_at", now + n(msa.get("expires_in"), 3600) - 60); a.put("client_id", clientId); a.put("xuid", "");
        return a;
    }
    static Map<String, Object> freshAccount(Map<String, Object> cfg, Log log) {
        Map<String, Object> acc = m(cfg.get("account"));
        if (!"msa".equals(s(acc.get("type")))) return null;
        long now = System.currentTimeMillis() / 1000;
        if (n(acc.get("mc_expires_at"), 0) >= now + 120) return acc;
        log.log("Refreshing Microsoft / Minecraft tokens...");
        try {
            String cid = s(acc.get("client_id")).isEmpty() ? clientId(cfg) : s(acc.get("client_id"));
            Map<String, Object> msa;
            if (n(acc.get("msa_expires_at"), 0) < now + 120) {
                msa = m(postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", new LinkedHashMap<>(Map.of(
                        "grant_type", "refresh_token", "client_id", cid, "refresh_token", s(acc.get("msa_refresh_token")), "scope", MSA_SCOPE))));
            } else {
                msa = new LinkedHashMap<>(Map.of("access_token", acc.get("msa_access_token"), "expires_in", Math.max(60, n(acc.get("msa_expires_at"), now) - now)));
            }
            msa.putIfAbsent("refresh_token", acc.get("msa_refresh_token"));
            Map<String, Object> na = buildAccount(msa, cid, log);
            cfg.put("account", na); saveConfig(cfg);
            log.log("Logged in as " + na.get("name"));
            return na;
        } catch (Exception e) { log.log("Token refresh failed: " + e + " - please log in again."); return null; }
    }
    static String clientId(Map<String, Object> cfg) { String c = s(cfg.get("msa_client_id")).trim(); return c.isEmpty() ? MSA_CLIENT_ID : c; }

    // ============================================================ runtime: version json, libraries, assets, Java
    static boolean rulesAllow(Object rules) {
        List<Object> rs = l(rules);
        if (rs.isEmpty()) return true;
        boolean allowed = false;
        for (Object o : rs) {
            Map<String, Object> r = m(o); boolean match = true;
            Map<String, Object> os = m(r.get("os"));
            if (os.containsKey("name") && !s(os.get("name")).equals(OS)) match = false;
            if (os.containsKey("arch") && !s(os.get("arch")).equals(ARCH)) match = false;
            if (r.get("features") != null) match = false;            // demo mode, custom resolution... all off
            if (match) allowed = "allow".equals(s(r.get("action")));
        }
        return allowed;
    }
    static String mvnPath(String name) {
        String ext = "jar"; int at = name.indexOf('@'); if (at >= 0) { ext = name.substring(at + 1); name = name.substring(0, at); }
        String[] p = name.split(":"); String cl = p.length > 3 ? "-" + p[3] : "";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + cl + "." + ext;
    }
    static Map<String, Object> loadVersion(Path rt, String vid) throws IOException {
        Map<String, Object> j = m(Json.parse(Files.readString(rt.resolve("versions").resolve(vid).resolve(vid + ".json"))));
        String parent = s(j.get("inheritsFrom"));
        if (parent.isEmpty()) { j.put("_jar_id", j.get("jar") != null ? j.get("jar") : vid); return j; }
        Map<String, Object> p = loadVersion(rt, parent), out = new LinkedHashMap<>(p);
        for (var e : j.entrySet()) {
            switch (e.getKey()) {
                case "libraries" -> { List<Object> x = new ArrayList<>(l(e.getValue())); x.addAll(l(p.get("libraries"))); out.put("libraries", x); }
                case "arguments" -> {
                    Map<String, Object> pa = m(p.get("arguments")), ca = m(e.getValue()), na = new LinkedHashMap<>();
                    for (String k : new String[]{"game", "jvm"}) { List<Object> x = new ArrayList<>(l(pa.get(k))); x.addAll(l(ca.get(k))); na.put(k, x); }
                    out.put("arguments", na);
                }
                case "inheritsFrom" -> { }
                default -> out.put(e.getKey(), e.getValue());
            }
        }
        out.put("_jar_id", p.get("_jar_id"));
        return out;
    }
    /** -> {libs, natives}; each entry {path,url,sha1,size,exclude} */
    static List<List<Map<String, Object>>> resolveLibs(Map<String, Object> ver) {
        List<Map<String, Object>> libs = new ArrayList<>(), natives = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Object o : l(ver.get("libraries"))) {
            Map<String, Object> lib = m(o);
            if (!rulesAllow(lib.get("rules"))) continue;
            String name = s(lib.get("name")); String[] p = name.split(":");
            String key = p[0] + ":" + p[1] + (p.length > 3 ? ":" + p[3] : "");
            Map<String, Object> dl = m(lib.get("downloads")), nat = m(lib.get("natives"));
            if (nat.containsKey(OS)) {
                String cl = s(nat.get(OS)).replace("${arch}", ARCH.equals("x86") ? "32" : "64");
                Map<String, Object> art = m(m(dl.get("classifiers")).get(cl));
                if (!art.isEmpty()) {
                    Map<String, Object> e = new LinkedHashMap<>(art); e.put("exclude", m(lib.get("extract")).getOrDefault("exclude", new ArrayList<>()));
                    natives.add(e);
                }
            }
            Map<String, Object> entry = null, art = m(dl.get("artifact"));
            if (!art.isEmpty()) {
                entry = new LinkedHashMap<>(art);
                if (s(entry.get("path")).isEmpty()) entry.put("path", mvnPath(name));
            } else if (dl.isEmpty() && nat.isEmpty()) {            // Fabric/Quilt style: name + maven base url
                String base = lib.get("url") != null ? s(lib.get("url")) : "https://libraries.minecraft.net/";
                String path = mvnPath(name);
                entry = new LinkedHashMap<>(); entry.put("path", path); entry.put("url", base.replaceAll("/+$", "") + "/" + path);
            }
            if (entry != null && seen.add(key)) libs.add(entry);
        }
        return List.of(libs, natives);
    }
    static List<String> expandArgs(List<Object> items) {
        List<String> out = new ArrayList<>();
        for (Object it : items) {
            if (it instanceof String x) out.add(x);
            else if (rulesAllow(m(it).get("rules"))) {
                Object v = m(it).get("value");
                if (v instanceof List<?> lv) for (Object z : lv) out.add(s(z)); else out.add(s(v));
            }
        }
        return out;
    }
    static String subst(String x, Map<String, String> vars) {
        Matcher mt = Pattern.compile("\\$\\{(\\w+)\\}").matcher(x); StringBuilder sb = new StringBuilder();
        while (mt.find()) mt.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(mt.group(1), mt.group(0))));
        mt.appendTail(sb); return sb.toString();
    }
    static List<String> shlex(String x) {
        List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); char q = 0; boolean any = false;
        for (char c : x.toCharArray()) {
            if (q != 0) { if (c == q) q = 0; else cur.append(c); }
            else if (c == '"' || c == '\'') { q = c; any = true; }
            else if (Character.isWhitespace(c)) { if (cur.length() > 0 || any) { out.add(cur.toString()); cur.setLength(0); any = false; } }
            else cur.append(c);
        }
        if (cur.length() > 0 || any) out.add(cur.toString());
        return out;
    }
    static Map<String, String> parseEnv(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : (text == null ? "" : text).split("[;\\n]")) {
            int e = part.indexOf('='); if (e > 0 && !part.substring(0, e).trim().isEmpty()) out.put(part.substring(0, e).trim(), part.substring(e + 1).trim());
        }
        return out;
    }
    static void extractNatives(Path rt, List<Map<String, Object>> natives, Path ndir) throws IOException {
        Files.createDirectories(ndir);
        for (var nt : natives) {
            try (ZipFile z = new ZipFile(rt.resolve("libraries").resolve(s(nt.get("path"))).toFile())) {
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement(); String nm = e.getName();
                    boolean excl = nm.startsWith("META-INF");
                    for (Object x : l(nt.get("exclude"))) if (nm.startsWith(s(x))) excl = true;
                    if (e.isDirectory() || excl) continue;
                    try (InputStream in = z.getInputStream(e)) { Files.copy(in, ndir.resolve(Paths.get(nm).getFileName().toString()), StandardCopyOption.REPLACE_EXISTING); }
                }
            }
        }
    }
    static Map<String, Object> ensureVanilla(Path rt, String mc, Log log) throws Exception {
        Path vj = rt.resolve("versions").resolve(mc).resolve(mc + ".json");
        if (!Files.exists(vj)) {
            log.log("Fetching Minecraft " + mc + " metadata...");
            Map<String, Object> man = m(getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", null));
            Map<String, Object> v = null;
            for (Object o : l(man.get("versions"))) if (s(m(o).get("id")).equals(mc)) { v = m(o); break; }
            if (v == null) throw new IOException("Unknown Minecraft version '" + mc + "'");
            fetchFile(s(v.get("url")), vj, v.get("sha1") == null ? null : s(v.get("sha1")), null);
        }
        Map<String, Object> j = m(Json.parse(Files.readString(vj)));
        Map<String, Object> c = m(m(j.get("downloads")).get("client"));
        fetchFile(s(c.get("url")), rt.resolve("versions").resolve(mc).resolve(mc + ".jar"), s(c.get("sha1")), n(c.get("size"), 0));
        return j;
    }
    static Path[] ensureAssets(Path rt, Map<String, Object> ver, Log log) throws Exception {
        Map<String, Object> ai = m(ver.get("assetIndex"));
        Path idxPath = rt.resolve("assets").resolve("indexes").resolve(s(ai.get("id")) + ".json");
        fetchFile(s(ai.get("url")), idxPath, ai.get("sha1") == null ? null : s(ai.get("sha1")), null);
        Map<String, Object> idx = m(Json.parse(Files.readString(idxPath))), objs = m(idx.get("objects"));
        List<Object[]> items = new ArrayList<>();
        for (Object o : objs.values()) {
            String h = s(m(o).get("hash"));
            items.add(new Object[]{"https://resources.download.minecraft.net/" + h.substring(0, 2) + "/" + h,
                    rt.resolve("assets").resolve("objects").resolve(h.substring(0, 2)).resolve(h), h, m(o).get("size") == null ? null : (Long) n(m(o).get("size"), 0)});
        }
        log.log("Checking " + items.size() + " asset files...");
        downloadAll(items, log, "assets");
        Path root = rt.resolve("assets"), game = root;
        if (b(idx.get("virtual"))) {
            game = root.resolve("virtual").resolve(s(ai.get("id")));
            for (var e : objs.entrySet()) {
                Path dst = game.resolve(e.getKey()); String h = s(m(e.getValue()).get("hash"));
                if (!Files.exists(dst)) { Files.createDirectories(dst.getParent()); Files.copy(root.resolve("objects").resolve(h.substring(0, 2)).resolve(h), dst); }
            }
        }
        return new Path[]{root, game};
    }
    static Path javaExe(Path root) {
        for (String rel : new String[]{"bin/java.exe", "bin/java", "jre.bundle/Contents/Home/bin/java"}) { Path p = root.resolve(rel); if (Files.exists(p)) return p; }
        return null;
    }
    static int javaMajor(String java) {
        try {
            Process p = new ProcessBuilder(java, "-version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()); p.waitFor();
            Matcher mt = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(out);
            if (!mt.find()) return 0;
            int a = Integer.parseInt(mt.group(1)); return a == 1 ? (mt.group(2) == null ? 0 : Integer.parseInt(mt.group(2))) : a;
        } catch (Exception e) { return 0; }
    }
    static String mojangPlatform() {
        if (OS.equals("windows")) return ARCH.equals("arm64") ? "windows-arm64" : ARCH.equals("x86_64") ? "windows-x64" : "windows-x86";
        if (OS.equals("osx")) return ARCH.equals("arm64") ? "mac-os-arm64" : "mac-os";
        return ARCH.equals("x86_64") ? "linux" : ARCH.equals("x86") ? "linux-i386" : null;   // Linux ARM64: use system Java
    }
    static String ensureMojangJava(Path rt, Map<String, Object> vjson, Log log) throws Exception {
        Map<String, Object> jv = m(vjson.get("javaVersion"));
        String comp = jv.isEmpty() ? "jre-legacy" : s(jv.get("component")), plat = mojangPlatform();
        if (plat == null) throw new IOException("Mojang publishes no Java runtime for " + OS + " " + ARCH);
        Path root = rt.resolve("runtime").resolve(comp).resolve(plat).resolve(comp);
        Path exe = javaExe(root); if (exe != null) return exe.toString();
        log.log("Downloading Mojang Java runtime " + comp + " for " + plat + "...");
        Map<String, Object> all = m(getJson("https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json", null));
        List<Object> ent = l(m(all.get(plat)).get(comp));
        if (ent.isEmpty()) throw new IOException("Mojang has no Java runtime '" + comp + "' for " + plat);
        Map<String, Object> man = m(getJson(s(m(m(ent.get(0)).get("manifest")).get("url")), null));
        List<Object[]> items = new ArrayList<>(); List<Path> execs = new ArrayList<>(); List<Path[]> links = new ArrayList<>();
        for (var e : m(man.get("files")).entrySet()) {
            Map<String, Object> f = m(e.getValue()); Path dest = root.resolve(e.getKey()); String type = s(f.get("type"));
            if (type.equals("directory")) Files.createDirectories(dest);
            else if (type.equals("file")) {
                Map<String, Object> raw = m(m(f.get("downloads")).get("raw"));
                items.add(new Object[]{s(raw.get("url")), dest, s(raw.get("sha1")), (Long) n(raw.get("size"), 0)});
                if (b(f.get("executable"))) execs.add(dest);
            } else if (type.equals("link")) links.add(new Path[]{dest, Paths.get(s(f.get("target")))});
        }
        downloadAll(items, log, "java files");
        for (Path p : execs) p.toFile().setExecutable(true, false);
        if (!OS.equals("windows")) for (Path[] lk : links) if (!Files.exists(lk[0], LinkOption.NOFOLLOW_LINKS)) { Files.createDirectories(lk[0].getParent()); Files.createSymbolicLink(lk[0], lk[1]); }
        exe = javaExe(root); if (exe == null) throw new IOException("Java runtime downloaded but no java executable found");
        return exe.toString();
    }
    static String resolveJava(Map<String, Object> inst, Path rt, Map<String, Object> vjson, Log log) throws Exception {
        int need = (int) n(m(vjson.get("javaVersion")).get("majorVersion"), 8);
        String custom = s(inst.get("java_path")).trim();
        if (!custom.isEmpty()) return custom;
        try { return ensureMojangJava(rt, vjson, log); }
        catch (Exception e) {
            log.log("Mojang runtime failed (" + e.getMessage() + "); trying system Java");
            List<String> cands = new ArrayList<>();
            String jh = System.getenv("JAVA_HOME"); if (jh != null) cands.add(Paths.get(jh, "bin", OS.equals("windows") ? "java.exe" : "java").toString());
            cands.add(Paths.get(System.getProperty("java.home"), "bin", OS.equals("windows") ? "java.exe" : "java").toString());
            cands.add("java");
            String best = null; int bm = 999;
            for (String c : cands) { int mj = javaMajor(c); if (mj >= need && mj < bm) { bm = mj; best = c; } }
            if (best != null) { log.log("Using system Java " + bm + ": " + best); return best; }
            throw new IOException("No usable Java " + need + "+ found. Install one and set 'Java path'.");
        }
    }

    // ============================================================ loaders
    static int[] vkey(String v) { String[] p = v.split("[.\\-]"); int[] r = new int[p.length]; for (int i = 0; i < p.length; i++) r[i] = p[i].matches("\\d+") ? Integer.parseInt(p[i]) : 0; return r; }
    static int vcmp(String a, String b) { int[] x = vkey(a), y = vkey(b); for (int i = 0; i < Math.max(x.length, y.length); i++) { int d = (i < x.length ? x[i] : 0) - (i < y.length ? y[i] : 0); if (d != 0) return d; } return 0; }
    static String installFabricLike(String kind, Map<String, Object> inst, Path rt, Log log) throws IOException {
        String base = kind.equals("fabric") ? "https://meta.fabricmc.net/v2" : "https://meta.quiltmc.org/v3", mc = s(inst.get("mc_version")), ver = s(inst.get("loader_version")).trim();
        if (ver.isEmpty()) {
            List<Object> lst = l(getJson(base + "/versions/loader/" + enc(mc), null));
            if (lst.isEmpty()) throw new IOException(kind + " has no loader for Minecraft " + mc);
            Object pick = lst.get(0);
            for (Object o : lst) if (!Pattern.compile("beta|pre|alpha|rc", Pattern.CASE_INSENSITIVE).matcher(s(m(m(o).get("loader")).get("version"))).find()) { pick = o; break; }
            ver = s(m(m(pick).get("loader")).get("version"));
        }
        log.log("Installing " + kind + " loader " + ver + " for " + mc + "...");
        byte[] raw = bytes(base + "/versions/loader/" + enc(mc) + "/" + enc(ver) + "/profile/json", null);
        String vid = s(m(Json.parse(new String(raw, StandardCharsets.UTF_8))).get("id"));
        Path d = rt.resolve("versions").resolve(vid); Files.createDirectories(d); Files.write(d.resolve(vid + ".json"), raw);
        return vid;
    }
    static String runInstaller(String java, String url, String ver, Path rt, String loader, Log log) throws Exception {
        Path jar = BASE.resolve("installers").resolve(url.substring(url.lastIndexOf('/') + 1));
        log.log("Downloading installer " + jar.getFileName() + "...");
        fetchFile(url, jar, null, null);
        Path lp = rt.resolve("launcher_profiles.json");
        if (!Files.exists(lp)) { Files.createDirectories(rt); Files.writeString(lp, "{\"profiles\": {}, \"version\": 3}"); }
        Path vdir = rt.resolve("versions"); Files.createDirectories(vdir);
        Set<String> before = new HashSet<>(); try (var st = Files.list(vdir)) { st.forEach(p -> before.add(p.getFileName().toString())); }
        log.log("Running " + loader + " installer (this can take a few minutes)...");
        Process p = new ProcessBuilder(java, "-Djava.awt.headless=true", "-jar", jar.toString(), "--installClient", rt.toString())
                .directory(rt.toFile()).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) { String ln; while ((ln = r.readLine()) != null) lines.add(ln); }
        int rc = p.waitFor();
        for (String ln : lines.subList(Math.max(0, lines.size() - 8), lines.size())) log.log("  | " + ln);
        if (rc != 0) throw new IOException(loader + " installer exited with code " + rc);
        List<String> after = new ArrayList<>(); try (var st = Files.list(vdir)) { st.forEach(q -> after.add(q.getFileName().toString())); }
        String tail = ver.substring(ver.lastIndexOf('-') + 1), best = null; long bt = -1;
        for (String d : after) {
            Path j = vdir.resolve(d).resolve(d + ".json");
            if (!Files.exists(j) || !(!before.contains(d) || d.contains(tail))) continue;
            long t = Files.getLastModifiedTime(j).toMillis(); if (t > bt) { bt = t; best = d; }
        }
        if (best == null) throw new IOException("Installer finished but no new version folder was found");
        return best;
    }
    static String ensureLoader(Map<String, Object> inst, Path rt, String java, Log log) throws Exception {
        String mc = s(inst.get("mc_version")), loader = s(inst.get("loader")), lv = s(inst.get("loader_version")).trim();
        String key = mc + "|" + loader + "|" + lv, vid = s(inst.get("version_id"));
        if (!vid.isEmpty() && key.equals(s(inst.get("installed_key"))) && Files.exists(rt.resolve("versions").resolve(vid).resolve(vid + ".json"))) return vid;
        switch (loader) {
            case "fabric", "quilt" -> vid = installFabricLike(loader, inst, rt, log);
            case "forge" -> {
                String v = lv;
                if (v.isEmpty()) {
                    Map<String, Object> promos = m(m(getJson("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json", null)).get("promos"));
                    v = s(promos.get(mc + "-recommended")); if (v.isEmpty()) v = s(promos.get(mc + "-latest"));
                    if (v.isEmpty()) throw new IOException("No Forge build listed for " + mc);
                }
                String full = mc + "-" + v;
                vid = runInstaller(java, "https://maven.minecraftforge.net/net/minecraftforge/forge/" + full + "/forge-" + full + "-installer.jar", v, rt, loader, log);
            }
            case "neoforge" -> {
                String art, prefix;
                if (mc.equals("1.20.1")) { art = "forge"; prefix = "1.20.1-"; }
                else {
                    Matcher mt = Pattern.compile("1\\.(\\d+)(?:\\.(\\d+))?").matcher(mc);
                    if (!mt.matches()) throw new IOException("Can't map this MC version to NeoForge; fill in 'Loader version'");
                    art = "neoforge"; prefix = mt.group(1) + "." + (mt.group(2) == null ? "0" : mt.group(2)) + ".";
                }
                String v = lv;
                if (v.isEmpty()) {
                    String xml = new String(bytes("https://maven.neoforged.net/releases/net/neoforged/" + art + "/maven-metadata.xml", null));
                    List<String> cand = new ArrayList<>(), stable = new ArrayList<>();
                    Matcher mt = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
                    while (mt.find()) if (mt.group(1).startsWith(prefix)) { cand.add(mt.group(1)); if (!Pattern.compile("alpha|beta|snapshot", Pattern.CASE_INSENSITIVE).matcher(mt.group(1)).find()) stable.add(mt.group(1)); }
                    List<String> pool = stable.isEmpty() ? cand : stable;
                    if (pool.isEmpty()) throw new IOException("No NeoForge build found for " + mc);
                    v = Collections.max(pool, SkyLauncher::vcmp);
                }
                vid = runInstaller(java, "https://maven.neoforged.net/releases/net/neoforged/" + art + "/" + v + "/" + art + "-" + v + "-installer.jar", v, rt, loader, log);
            }
            default -> throw new IOException("Unknown loader " + loader);
        }
        inst.put("version_id", vid); inst.put("installed_key", key);
        log.log("Loader installed: " + vid);
        return vid;
    }

    // ============================================================ mod jar metadata + sources
    /** ids + declared required deps found inside a jar (fabric / quilt / forge / neoforge). */
    static Map<String, Object> readMeta(Path jar) {
        Set<String> ids = new TreeSet<>(), deps = new TreeSet<>(), loaders = new TreeSet<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry fe = z.getEntry("fabric.mod.json");
            if (fe != null) {
                Map<String, Object> j = m(Json.parse(new String(z.getInputStream(fe).readAllBytes(), StandardCharsets.UTF_8)));
                ids.add(s(j.get("id"))); for (Object p : l(j.get("provides"))) ids.add(s(p));
                deps.addAll(m(j.get("depends")).keySet()); loaders.add("fabric");
            }
            ZipEntry qe = z.getEntry("quilt.mod.json");
            if (qe != null) {
                Map<String, Object> q = m(m(Json.parse(new String(z.getInputStream(qe).readAllBytes(), StandardCharsets.UTF_8))).get("quilt_loader"));
                ids.add(s(q.get("id")));
                for (Object d : l(q.get("depends"))) { if (d instanceof String x) deps.add(x); else if (!b(m(d).get("optional"))) deps.add(s(m(d).get("id"))); }
                loaders.add("quilt");
            }
            for (String fn : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                ZipEntry te = z.getEntry(fn); if (te == null) continue;
                String raw = new String(z.getInputStream(te).readAllBytes(), StandardCharsets.UTF_8);
                Matcher mt = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(raw);
                while (mt.find()) ids.add(mt.group(1));
                loaders.add(fn.contains("neoforge") ? "neoforge" : "forge"); break;
            }
        } catch (Exception ignored) { }
        ids.remove("");
        Map<String, Object> r = new LinkedHashMap<>(); r.put("ids", new ArrayList<>(ids)); r.put("deps", new ArrayList<>(deps)); r.put("loaders", new ArrayList<>(loaders));
        return r;
    }
    static String modSource(String key) { return key.startsWith("modrinth:") ? "modrinth" : key.startsWith("curseforge:") ? "curseforge" : key.startsWith("url:") ? "url" : "github"; }
    static Map<String, String> ghHeaders(Map<String, Object> cfg, String accept) {
        Map<String, String> h = new HashMap<>(); h.put("Accept", accept == null ? "application/vnd.github+json" : accept);
        String tok = s(cfg.get("token")); if (tok.isEmpty() && System.getenv("GITHUB_TOKEN") != null) tok = System.getenv("GITHUB_TOKEN");
        if (!tok.isEmpty()) h.put("Authorization", "Bearer " + tok);
        return h;
    }
    static Map<String, String> mrHeaders(Map<String, Object> cfg) {
        String tok = s(cfg.get("modrinth_token")).trim(); return tok.isEmpty() ? new HashMap<>() : new HashMap<>(Map.of("Authorization", tok));
    }
    static Object mr(Map<String, Object> cfg, String path) throws IOException { return getJson("https://api.modrinth.com/v2" + path, mrHeaders(cfg)); }
    // CurseForge API access is performed ONLY by the VPS bridge. The real CurseForge
    // x-api-key must never be stored in this launcher or sent to client computers.
    static String cfConfiguredUrl(Map<String, Object> cfg) {
        String url = s(cfg.get("curseforge_bridge_url")).trim();
        return url.isEmpty() ? PERSONAL_CF_URL : url;
    }
    static String cfBase(Map<String, Object> cfg) throws IOException {
        return validatedCfAddress(cfConfiguredUrl(cfg));
    }
    static String validatedCfAddress(String input) throws IOException {
        URI uri;
        try { uri = URI.create(input); } catch (IllegalArgumentException e) { throw new IOException("Invalid CurseForge VPS URL", e); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                !("https".equalsIgnoreCase(uri.getScheme()) ||
                  ("http".equalsIgnoreCase(uri.getScheme()) &&
                  ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())))))
            throw new IOException("The VPS must use HTTPS (HTTP is only accepted on localhost for testing).");
        if (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))
            throw new IOException("Use only the base VPS URL, without a path.");
        return input.replaceAll("/+$", "");
    }
    static Path launcherDirectory() {
        try {
            Path location = Paths.get(SkyLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (Exception ignored) { return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath(); }
    }
    static Map<String, String> cfHeaders(Map<String, Object> cfg) {
        // Public CurseForge relay. No shared bearer token is ever shipped or required.
        // The separate CurseForge API key is kept exclusively on the VPS.
        return Map.of("Accept", "application/json");
    }
    static Object cf(Map<String, Object> cfg, String path) throws IOException {
        return getJson(cfBase(cfg) + path, cfHeaders(cfg));
    }
    static String cfDownloadUrl(Map<String, Object> cfg, String modId, String fileId) throws IOException {
        if (!modId.matches("[1-9][0-9]{0,12}") || !fileId.matches("[1-9][0-9]{0,12}"))
            throw new IOException("Invalid CurseForge mod or file ID");
        return cfBase(cfg) + "/v1/download/" + modId + "/" + fileId;
    }
    static String ghNameLoaders(String n) {   // loaders a file name mentions
        n = n.toLowerCase(); Set<String> f = new TreeSet<>();
        if (n.contains("neoforge")) { f.add("neoforge"); n = n.replace("neoforge", ""); }
        if (n.contains("forge")) f.add("forge"); if (n.contains("fabric")) f.add("fabric"); if (n.contains("quilt")) f.add("quilt");
        return String.join(",", f);
    }
    /** scored jar assets of a GitHub release that fit this instance */
    static List<Map<String, Object>> ghCandidates(Map<String, Object> rel, Map<String, Object> inst) {
        String mc = s(inst.get("mc_version")), loader = s(inst.get("loader"));
        String relinfo = (s(rel.get("tag_name")) + " " + s(rel.get("name"))).toLowerCase();
        List<Object[]> out = new ArrayList<>();
        for (Object o : l(rel.get("assets"))) {
            Map<String, Object> a = m(o); String nm = s(a.get("name")).toLowerCase();
            if (!nm.endsWith(".jar") || nm.contains("sources") || nm.contains("javadoc") || nm.contains("-dev") || nm.contains("deobf")) continue;
            String nl = ghNameLoaders(nm); Set<String> set = nl.isEmpty() ? Set.of() : Set.of(nl.split(","));
            if (!set.isEmpty() && !set.contains(loader) && !(loader.equals("quilt") && set.contains("fabric"))) continue;
            int score = set.contains(loader) ? 3 : 0;
            if (!mc.isEmpty()) {
                if (nm.contains(mc)) score += 4;
                else if (OTHER_MC.matcher(nm).find()) continue;
                else if (relinfo.contains(mc)) score += 2;
                else if (OTHER_MC.matcher(relinfo).find()) continue;
            }
            out.add(new Object[]{score, a});
        }
        out.sort((x, y) -> (Integer) y[0] - (Integer) x[0]);
        List<Map<String, Object>> r = new ArrayList<>(); for (Object[] x : out) r.add(m(x[1])); return r;
    }
    /** versions of a tracked mod from its own source (newest first). Each: id,label,date,channel,files[],deps[] */
    static List<Map<String, Object>> listVersions(String key, Map<String, Object> inst, Map<String, Object> cfg) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        String loader = s(inst.get("loader")), mc = s(inst.get("mc_version")).trim();
        if (modSource(key).equals("modrinth")) {
            List<String> ls = new ArrayList<>(List.of("\"" + loader + "\"")); if (loader.equals("quilt")) ls.add("\"fabric\"");
            String q = "?loaders=" + enc("[" + String.join(",", ls) + "]") + (mc.isEmpty() ? "" : "&game_versions=" + enc("[\"" + mc + "\"]"));
            for (Object o : l(mr(cfg, "/project/" + enc(key.substring(10)) + "/version" + q))) {
                Map<String, Object> v = m(o); List<Object> files = new ArrayList<>();
                for (Object fo : l(v.get("files"))) {
                    Map<String, Object> f = m(fo); if (!s(f.get("filename")).toLowerCase().endsWith(".jar")) continue;
                    Map<String, Object> e = new LinkedHashMap<>(); e.put("name", f.get("filename")); e.put("url", f.get("url"));
                    e.put("sha1", m(f.get("hashes")).get("sha1")); e.put("sha512", m(f.get("hashes")).get("sha512")); e.put("primary", b(f.get("primary")));
                    if (b(f.get("primary"))) files.add(0, e); else files.add(e);
                }
                if (files.isEmpty()) continue;
                List<Object> deps = new ArrayList<>();
                for (Object d : l(v.get("dependencies"))) if ("required".equals(s(m(d).get("dependency_type"))) && m(d).get("project_id") != null) deps.add(m(d).get("project_id"));
                Map<String, Object> r = new LinkedHashMap<>(); r.put("id", v.get("id")); r.put("label", s(v.get("version_number")).isEmpty() ? v.get("name") : v.get("version_number"));
                r.put("channel", v.get("version_type") == null ? "release" : v.get("version_type")); r.put("files", files); r.put("deps", deps); out.add(r);
            }
        } else if (modSource(key).equals("curseforge")) {
            String modId = key.substring("curseforge:".length());
            if (!modId.matches("[1-9][0-9]{0,12}")) throw new IOException("Invalid CurseForge project ID");
            String query = "?version=" + enc(mc) + "&loader=" + enc(loader);
            for (Object o : l(m(cf(cfg, "/v1/mods/" + modId + "/files" + query)).get("data"))) {
                Map<String, Object> f = m(o);
                if (!s(f.get("name")).toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                String fileId = s(f.get("id"));
                if (!fileId.matches("[1-9][0-9]{0,12}")) continue;
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("name", f.get("name"));
                file.put("url", cfDownloadUrl(cfg, modId, fileId));
                file.put("sha1", f.get("sha1"));
                file.put("size", f.get("size"));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", fileId);
                entry.put("label", s(f.get("name")));
                entry.put("channel", n(f.get("releaseType"), 1) == 1 ? "release" : "beta");
                entry.put("files", List.of(file));
                List<Object> deps = new ArrayList<>();
                for (Object d : l(f.get("required"))) {
                    String dep = s(d);
                    if (dep.matches("[1-9][0-9]{0,12}")) deps.add("curseforge:" + dep);
                }
                entry.put("deps", deps);
                out.add(entry);
            }
        } else {
            for (Object o : l(getJson("https://api.github.com/repos/" + key + "/releases?per_page=100", ghHeaders(cfg, null)))) {
                Map<String, Object> r = m(o); if (b(r.get("draft")) || ghCandidates(r, inst).isEmpty()) continue;
                Map<String, Object> e = new LinkedHashMap<>(); e.put("id", r.get("tag_name")); e.put("label", r.get("tag_name"));
                e.put("channel", b(r.get("prerelease")) ? "beta" : "release"); e.put("rel", r); out.add(e);
            }
        }
        return out;
    }
    static Map<String, Object> pickLatest(List<Map<String, Object>> vs) {
        for (var v : vs) if ("release".equals(s(v.get("channel")))) return v;
        return vs.isEmpty() ? null : vs.get(0);
    }
    static Set<String> installedIds(Map<String, Object> inst) {
        Set<String> ids = new HashSet<>(); Path dir = Paths.get(s(inst.get("mods_dir")));
        if (!Files.isDirectory(dir)) return ids;
        try (var st = Files.list(dir)) {
            st.filter(p -> p.toString().endsWith(".jar")).forEach(p -> { for (Object x : l(readMeta(p).get("ids"))) ids.add(s(x)); });
        } catch (IOException ignored) { }
        return ids;
    }
    static Path gameDir(Map<String, Object> inst) {
        Path d = Paths.get(s(inst.get("mods_dir"))); return d.getFileName() != null && d.getFileName().toString().equals("mods") ? d.getParent() : d;
    }

    // ============================================================ packs
    static Path safeJoin(Path base, String rel) throws IOException {
        rel = rel.replace('\\', '/');
        if (rel.startsWith("/") || rel.matches("^[A-Za-z]:.*") || Arrays.asList(rel.split("/")).contains("..")) throw new IOException("Unsafe path in pack: " + rel);
        Path d = base.resolve(rel).normalize(); if (!d.startsWith(base.normalize())) throw new IOException("Unsafe path in pack: " + rel); return d;
    }
    static void clearMods(Path gd, Log log) throws IOException {
        Path mods = gd.resolve("mods"); if (!Files.isDirectory(mods)) return;
        log.log("Clearing existing mods folder...");
        try (var st = Files.list(mods)) { for (Path p : (Iterable<Path>) st::iterator) { try { deleteTree(p); } catch (IOException e) { log.log("  warning: could not remove " + p.getFileName()); } } }
    }
    static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) try (var st = Files.list(p)) { for (Path c : (Iterable<Path>) st::iterator) deleteTree(c); }
        Files.deleteIfExists(p);
    }
    static void installGithubPack(Map<String, Object> cfg, String repo, String ref, Map<String, Object> inst, boolean clear, Log log) throws Exception {
        String sha = "";
        try { sha = s(m(getJson("https://api.github.com/repos/" + repo + "/commits/" + enc(ref).replace("%2F", "/"), ghHeaders(cfg, null))).get("sha")); }
        catch (Exception e) { log.log("Couldn't read the pack's latest commit (" + e.getMessage() + "); update checks won't work"); }
        Path zip = Files.createTempFile("ghml-pack-", ".zip");
        try {
            log.log("Downloading pack " + repo + "@" + ref + " (this may take a while)...");
            try (InputStream in = open("GET", "https://api.github.com/repos/" + repo + "/zipball/" + (sha.isEmpty() ? ref : sha), ghHeaders(cfg, null), null).body()) { Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING); }
            Path gd = gameDir(inst); Files.createDirectories(gd);
            if (clear) clearMods(gd, log);
            int total = 0;
            try (ZipFile z = new ZipFile(zip.toFile())) {
                // GitHub pack authors can declare exact versions in skylauncher-pack.json
                // (minecraft, loader, loader_version). Also accept CurseForge's manifest.json.
                Map<String,Object> packInfo = new LinkedHashMap<>();
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry entry = en.nextElement();
                    String path = entry.getName();
                    int root = path.indexOf('/');
                    if (root < 0 || entry.isDirectory()) continue;
                    String relative = path.substring(root + 1);
                    if (!relative.equals("skylauncher-pack.json") && !relative.equals("manifest.json")) continue;
                    if (entry.getSize() > 1024 * 1024) continue;
                    try (InputStream input = z.getInputStream(entry)) {
                        byte[] raw = input.readNBytes(1024 * 1024 + 1);
                        if (raw.length <= 1024 * 1024) {
                            Map<String,Object> parsed = m(Json.parse(new String(raw,StandardCharsets.UTF_8)));
                            if (relative.equals("skylauncher-pack.json")) {
                                packInfo = parsed;
                                break;
                            }
                            Map<String,Object> minecraft = m(parsed.get("minecraft"));
                            String mc = s(minecraft.get("version"));
                            String loader = "", loaderVersion = "";
                            for (Object item : l(minecraft.get("modLoaders"))) {
                                String id=s(m(item).get("id")).toLowerCase(Locale.ROOT);
                                for(String supported:LOADERS) if(id.startsWith(supported+"-")) {
                                    if(loader.isBlank() || b(m(item).get("primary"))) {
                                        loader=supported;
                                        loaderVersion=id.substring(supported.length()+1);
                                    }
                                }
                            }
                            if(!mc.isBlank() && !loader.isBlank())
                                packInfo = new LinkedHashMap<>(Map.of("minecraft",mc,"loader",loader,"loader_version",loaderVersion));
                        }
                    }
                }
                String metadataMc = s(packInfo.get("minecraft"));
                String metadataLoader = s(packInfo.get("loader")).toLowerCase(Locale.ROOT);
                if(metadataMc.matches("[0-9]+(?:\\.[0-9A-Za-z_-]+){1,4}") &&
                    Arrays.asList(LOADERS).contains(metadataLoader)) {
                    applyPackVersions(inst,metadataMc,metadataLoader,s(packInfo.get("loader_version")));
                    log.log("Pack selected Minecraft "+metadataMc+" / "+metadataLoader+" "+s(packInfo.get("loader_version")));
                } else log.log("No complete Minecraft/loader metadata in GitHub pack; keeping this profile's versions.");
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement(); if (e.isDirectory()) continue;
                    String nm = e.getName(); int slash = nm.indexOf('/'); if (slash < 0) continue;
                    String rel = nm.substring(slash + 1), top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
                    if (!Arrays.asList(PACK_FOLDERS).contains(top)) continue;
                    Path dest = safeJoin(gd, rel); Files.createDirectories(dest.getParent());
                    try (InputStream in = z.getInputStream(e)) { Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING); }
                    total++;
                }
            }
            if (total == 0) throw new IOException("No pack folders found (expected mods/, config/, ...). Is this a modpack repo?");
            log.log("  extracted " + total + " file(s)");
            inst.put("pack_source", "github"); inst.put("pack_repo", repo); inst.put("pack_ref", ref); inst.put("pack_sha", sha); inst.put("pack_url", "");
            inst.put("pack_name", repo); inst.put("pack_version", sha.length() >= 7 ? sha.substring(0, 7) : sha); inst.put("pack_skip_sha", "");
            log.log("Pack install complete.");
        } finally { Files.deleteIfExists(zip); }
    }
    static void applyPackVersions(Map<String,Object> inst,String minecraft,String loader,String loaderVersion) {
        if(minecraft.isBlank() || loader.isBlank()) throw new IllegalArgumentException("Missing modpack version metadata");
        boolean changed=!minecraft.equals(s(inst.get("mc_version"))) ||
            !loader.equals(s(inst.get("loader"))) ||
            !loaderVersion.equals(s(inst.get("loader_version")));
        inst.put("mc_version",minecraft);
        inst.put("loader",loader);
        inst.put("loader_version",loaderVersion);
        if(changed) {
            inst.put("version_id","");
            inst.put("installed_key","");
        }
    }
    static void installModrinthPack(Map<String, Object> cfg, String slug, String version, Map<String, Object> inst, boolean clear, Log log) throws Exception {
        Map<String, Object> p = m(mr(cfg, "/project/" + enc(slug)));
        if (!"modpack".equals(s(p.get("project_type")))) throw new IOException("'" + slug + "' on Modrinth is a " + p.get("project_type") + ", not a modpack");
        Map<String, Object> v;
        if (!version.isEmpty()) {
            List<Object> vs = l(mr(cfg, "/project/" + p.get("id") + "/version"));
            v = null;
            for (Object o : vs) if (version.equals(s(m(o).get("id"))) || version.equals(s(m(o).get("version_number")))) { v = m(o); break; }
            if (v == null) throw new IOException("Modrinth version not found: " + version);
        } else v = latestModrinthPack(cfg, s(p.get("id")));
        Map<String, Object> f = null;
        for (Object o : l(v.get("files"))) if (b(m(o).get("primary")) || (f == null && s(m(o).get("filename")).endsWith(".mrpack"))) f = m(o);
        if (f == null) throw new IOException("That version has no .mrpack file");
        log.log("Downloading " + p.get("title") + " " + v.get("version_number") + "...");
        Path tmp = Files.createTempFile("ghml-pack-", ".mrpack");
        try {
            fetchFile(s(f.get("url")), tmp.resolveSibling(tmp.getFileName() + ".dl"), null, null);
            Path dl = tmp.resolveSibling(tmp.getFileName() + ".dl");
            Path gd = gameDir(inst); Files.createDirectories(gd);
            try (ZipFile z = new ZipFile(dl.toFile())) {
                Map<String, Object> idx = m(Json.parse(new String(z.getInputStream(z.getEntry("modrinth.index.json")).readAllBytes(), StandardCharsets.UTF_8)));
                Map<String, Object> deps = m(idx.get("dependencies"));
                String[][] keymap = {{"fabric-loader", "fabric"}, {"quilt-loader", "quilt"}, {"forge", "forge"}, {"neoforge", "neoforge"}};
                String loader = null, lver = "";
                for (String[] k : keymap) if (deps.containsKey(k[0])) { loader = k[1]; lver = s(deps.get(k[0])); break; }
                String mc = s(deps.get("minecraft"));
                if (mc.isEmpty() || loader == null) throw new IOException("This .mrpack has no Minecraft version / supported loader");
                if (clear) clearMods(gd, log);
                List<Object[]> items = new ArrayList<>();
                for (Object o : l(idx.get("files"))) {
                    Map<String, Object> e = m(o); if ("unsupported".equals(s(m(e.get("env")).get("client")))) continue;
                    String url = null;
                    for (Object u : l(e.get("downloads"))) { String h = URI.create(s(u)).getHost(); if (Set.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com").contains(h)) { url = s(u); break; } }
                    if (url == null) throw new IOException("No download from an allowed host for " + e.get("path"));
                    items.add(new Object[]{url, safeJoin(gd, s(e.get("path"))), m(e.get("hashes")).get("sha1") == null ? null : s(m(e.get("hashes")).get("sha1")), null});
                }
                log.log("Downloading " + items.size() + " pack file(s)..."); downloadAll(items, log, "pack files");
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement(); if (e.isDirectory()) continue;
                    for (String pre : new String[]{"overrides/", "client-overrides/"}) if (e.getName().startsWith(pre)) {
                        Path dest = safeJoin(gd, e.getName().substring(pre.length())); Files.createDirectories(dest.getParent());
                        try (InputStream in = z.getInputStream(e)) { Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING); }
                    }
                }
                applyPackVersions(inst, mc, loader, lver);
                log.log("Pack needs " + loader + " " + lver + " on Minecraft " + mc);
            } finally { Files.deleteIfExists(dl); }
        } finally { Files.deleteIfExists(tmp); }
        inst.put("pack_source", "modrinth"); inst.put("pack_repo", ""); inst.put("pack_ref", ""); inst.put("pack_sha", ""); inst.put("pack_skip_sha", "");
        inst.put("pack_url", "https://modrinth.com/modpack/" + (p.get("slug") == null ? slug : p.get("slug")));
        inst.put("pack_id", s(p.get("id"))); inst.put("pack_version_id", s(v.get("id")));
        inst.put("pack_name", p.get("title")); inst.put("pack_version", v.get("version_number"));
        log.log("Pack install complete.");
    }


    // CurseForge pack bytes and all referenced mods come through the user's own HTTPS VPS bridge.
    // The developer API key is never handled by the client.
    static Map<String, Object> latestCurseForgePack(Map<String, Object> cfg, String projectId) throws IOException {
        if (!projectId.matches("[1-9][0-9]{0,12}")) throw new IOException("Invalid CurseForge pack ID");
        List<Object> files = l(m(cf(cfg, "/v1/packs/" + projectId + "/files")).get("data"));
        for (Object v : files) if (s(m(v).get("name")).toLowerCase(Locale.ROOT).endsWith(".zip")) return m(v);
        throw new IOException("No downloadable .zip modpack was found on CurseForge");
    }
    static Map<String, Object> latestModrinthPack(Map<String, Object> cfg, String projectId) throws IOException {
        List<Object> vs = l(mr(cfg, "/project/" + enc(projectId) + "/version"));
        if (vs.isEmpty()) throw new IOException("Modrinth pack has no versions");
        Map<String,Object> best = m(vs.get(0));
        for (Object o : vs) if ("release".equals(s(m(o).get("version_type")))) { best = m(o); break; }
        return best;
    }
    static void fetchBridgeFile(Map<String, Object> cfg, String projectId, String fileId,
            Path dest, String sha1, long maxSize) throws IOException {
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        long size = 0;
        try (InputStream input = open("GET", cfDownloadUrl(cfg, projectId, fileId), cfHeaders(cfg), null).body();
             OutputStream output = Files.newOutputStream(part)) {
            byte[] buf = new byte[65536]; int read;
            while ((read = input.read(buf)) != -1) {
                size += read;
                if (size > maxSize) throw new IOException("CurseForge file exceeds the size limit");
                output.write(buf, 0, read);
            }
        } catch (IOException e) { Files.deleteIfExists(part); throw e; }
        if (sha1 != null && !sha1.isBlank() && !fileDigest("SHA-1", part).equalsIgnoreCase(sha1)) {
            Files.deleteIfExists(part); throw new IOException("CurseForge file failed SHA-1 integrity verification");
        }
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    }
    static void extractPackOverrides(ZipFile z, Path stage, String prefix, Log log) throws IOException {
        long total = 0; int count = 0;
        String root = prefix.endsWith("/") ? prefix : prefix + "/";
        for (var entries = z.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(root)) continue;
            String rel = entry.getName().substring(root.length());
            if (rel.isBlank() || rel.startsWith("/")) continue;
            Path dest = safeJoin(stage, rel); Files.createDirectories(dest.getParent());
            if (++count > 5000) throw new IOException("Too many modpack override files");
            try (InputStream in = z.getInputStream(entry); OutputStream out = Files.newOutputStream(dest)) {
                byte[] buf = new byte[32768]; int len;
                while ((len = in.read(buf)) != -1) {
                    total += len;
                    if (total > 512L * 1024 * 1024) throw new IOException("Modpack overrides exceed 512 MiB");
                    out.write(buf, 0, len);
                }
            }
        }
        log.log("Extracted " + count + " modpack override files");
    }
    static void installCurseForgePack(Map<String, Object> cfg, String projectId,
            Map<String, Object> inst, boolean clear, Log log) throws Exception {
        Map<String,Object> project = m(cf(cfg, "/v1/packs/" + projectId));
        Map<String,Object> file = latestCurseForgePack(cfg, projectId);
        String fileId = s(file.get("id")), name = s(project.get("name"));
        String sha1 = s(file.get("sha1"));
        if (!fileId.matches("[1-9][0-9]{0,12}")) throw new IOException("No valid CurseForge modpack file ID");
        Path work = Files.createTempDirectory("sky-cf-pack-");
        try {
            Path zipPath = work.resolve("pack.zip"), stage = work.resolve("payload");
            Files.createDirectories(stage);
            log.log("Downloading CurseForge pack " + name + " through the VPS...");
            fetchBridgeFile(cfg, projectId, fileId, zipPath, sha1, 512L*1024*1024);
            Map<String,Object> manifest;
            String mc = "", loader = "", loaderVersion = "";
            int downloaded = 0;
            try (ZipFile z = new ZipFile(zipPath.toFile())) {
                ZipEntry meta = z.getEntry("manifest.json");
                if (meta == null || meta.getSize() > 4 * 1024 * 1024) throw new IOException("CurseForge zip has no valid manifest.json");
                try (InputStream in = z.getInputStream(meta)) {
                    byte[] bytes = in.readNBytes(4*1024*1024+1);
                    if (bytes.length > 4*1024*1024) throw new IOException("CurseForge manifest is too large");
                    manifest = m(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
                }
                if (!"minecraftModpack".equals(s(manifest.get("manifestType"))))
                    throw new IOException("Unsupported CurseForge pack manifest");
                Map<String,Object> minecraft = m(manifest.get("minecraft"));
                mc = s(minecraft.get("version"));
                if (!mc.matches("[0-9]+(?:\\.[0-9A-Za-z_-]+){1,4}")) throw new IOException("Invalid pack Minecraft version");
                for (Object value : l(minecraft.get("modLoaders"))) {
                    String id = s(m(value).get("id"));
                    for (String type : LOADERS) if (id.toLowerCase(Locale.ROOT).startsWith(type + "-")) {
                        if (loader.isEmpty() || b(m(value).get("primary"))) {
                            loader = type; loaderVersion = id.substring(type.length()+1);
                        }
                    }
                }
                if (loader.isEmpty()) throw new IOException("The pack requires an unsupported Minecraft loader");
                List<Object> mods = l(manifest.get("files"));
                if (mods.size() > 2000) throw new IOException("Pack contains too many mod entries");
                for (Object value : mods) {
                    Map<String,Object> entry = m(value);
                    if (Boolean.FALSE.equals(entry.get("required"))) continue;
                    String modId = s(entry.get("projectID")), modFileId = s(entry.get("fileID"));
                    if (!modId.matches("[1-9][0-9]{0,12}") || !modFileId.matches("[1-9][0-9]{0,12}"))
                        throw new IOException("Invalid project/file ID in CurseForge pack manifest");
                    Map<String,Object> info = m(cf(cfg, "/v1/mods/" + modId + "/files/" + modFileId));
                    String filename = s(info.get("name"));
                    if (!filename.matches("[A-Za-z0-9][A-Za-z0-9_.+() -]{0,180}\\.jar"))
                        throw new IOException("Invalid mod filename from CurseForge: " + filename);
                    Path dest = safeJoin(stage, "mods/" + filename);
                    fetchBridgeFile(cfg, modId, modFileId, dest, s(info.get("sha1")), 128L * 1024 * 1024);
                    downloaded++;
                    if (downloaded % 25 == 0) log.log("CurseForge mod files: " + downloaded + "/" + mods.size());
                }
                String overrides = s(manifest.get("overrides"));
                if (overrides.isEmpty()) overrides = "overrides";
                if (!overrides.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("Unsafe overrides folder name");
                extractPackOverrides(z, stage, overrides, log);
            }
            Path gd = gameDir(inst); Files.createDirectories(gd);
            if (clear) clearMods(gd, log);
            try (var paths = Files.walk(stage)) {
                for (Path src : (Iterable<Path>)paths::iterator) {
                    if (Files.isDirectory(src)) continue;
                    String rel = stage.relativize(src).toString();
                    Path dest = safeJoin(gd, rel); Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            applyPackVersions(inst, mc, loader, loaderVersion);
            inst.put("pack_source", "curseforge"); inst.put("pack_id", projectId); inst.put("pack_file_id", fileId);
            inst.put("pack_repo", ""); inst.put("pack_ref", ""); inst.put("pack_sha", ""); inst.put("pack_skip_sha", "");
            inst.put("pack_name", name); inst.put("pack_version", s(file.get("name")));
            inst.put("pack_url", "https://www.curseforge.com/minecraft/modpacks/" + s(project.get("slug")));
            log.log("CurseForge pack ready: " + name + " (" + downloaded + " mods); requires " + mc + " " + loader);
        } finally { deleteTree(work); }
    }

    // ============================================================ local mod imports
    static final long LOCAL_JAR_MAX_BYTES = 256L * 1024 * 1024;
    static final long LOCAL_IMPORT_MAX_BYTES = 1024L * 1024 * 1024;
    static final int LOCAL_ZIP_MAX_ENTRIES = 4096;
    static final int LOCAL_ZIP_MAX_JARS = 1000;

    static final class LocalImportResult {
        int imported, skipped, rejected;
        long bytesRead;
        String summary() {
            return "Imported " + imported + " mod(s); " + skipped + " skipped; " + rejected + " rejected.";
        }
    }

    /** Copy only mod jars into a profile's mods folder. Never extract archive paths. */
    static void importLocalJar(InputStream input, String entryName, Path modsDir, String loader,
                               boolean overwrite, Set<String> tracked, Set<String> seen,
                               LocalImportResult result, Log log) throws IOException {
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("(?i)^[a-z]:.*")
                || Arrays.asList(normalized.split("/")).contains("..")) {
            result.rejected++; log.log("Unsafe archive path rejected: " + entryName); return;
        }
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".jar") || filename.isBlank()) {
            result.rejected++; log.log("Not a jar: " + entryName); return;
        }
        String key = filename.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) {
            result.skipped++; log.log("Duplicate jar filename skipped: " + filename); return;
        }
        Path destination = modsDir.resolve(filename);
        if (tracked.contains(key) || (Files.exists(destination) && !overwrite)) {
            result.skipped++; log.log("Already installed (or managed by mod updater): " + filename); return;
        }

        Path staging = Files.createTempFile(modsDir, ".skylauncher-import-", ".jar");
        try {
            long size = 0;
            try (OutputStream out = Files.newOutputStream(staging)) {
                byte[] chunk = new byte[64 * 1024];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    size += count;
                    result.bytesRead += count;
                    if (size > LOCAL_JAR_MAX_BYTES || result.bytesRead > LOCAL_IMPORT_MAX_BYTES)
                        throw new IOException("Import limit exceeded (256 MiB per jar, 1 GiB per batch)");
                    out.write(chunk, 0, count);
                }
            }
            // Both a real zip/jar and a Minecraft mod descriptor are required.
            try (ZipFile jar = new ZipFile(staging.toFile())) {
                if (jar.getEntry("fabric.mod.json") == null
                        && jar.getEntry("quilt.mod.json") == null
                        && jar.getEntry("META-INF/mods.toml") == null
                        && jar.getEntry("META-INF/neoforge.mods.toml") == null) {
                    result.rejected++; log.log("No Minecraft mod metadata: " + filename); return;
                }
            }
            List<Object> loaders = l(readMeta(staging).get("loaders"));
            if (!loaders.isEmpty() && !loaders.contains(loader)
                    && !(loader.equals("quilt") && loaders.contains("fabric"))) {
                result.rejected++; log.log("Wrong mod loader for " + filename + ": " + loaders + " (profile: " + loader + ")"); return;
            }
            if (overwrite) Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(staging, destination);
            result.imported++;
            log.log("Imported local mod: " + filename);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    static LocalImportResult importLocalMods(List<Path> paths, Map<String, Object> instance,
                                             boolean overwrite, Log log) throws IOException {
        Path modsDir = Paths.get(s(instance.get("mods_dir")));
        Files.createDirectories(modsDir);
        String loader = s(instance.get("loader"));
        Set<String> tracked = new HashSet<>(), seen = new HashSet<>();
        for (Object item : m(instance.get("mods")).values()) {
            String file = s(m(item).get("file"));
            if (!file.isBlank()) tracked.add(file.toLowerCase(Locale.ROOT));
        }
        LocalImportResult result = new LocalImportResult();
        for (Path path : paths) {
            String ext = path.getFileName().toString().toLowerCase(Locale.ROOT);
            try {
                if (!Files.isRegularFile(path)) throw new IOException("Not a regular file");
                if (ext.endsWith(".jar")) {
                    if (Files.size(path) > LOCAL_JAR_MAX_BYTES) throw new IOException("Jar exceeds 256 MiB");
                    try (InputStream input = Files.newInputStream(path)) {
                        importLocalJar(input, path.getFileName().toString(), modsDir, loader,
                                overwrite, tracked, seen, result, log);
                    }
                } else if (ext.endsWith(".zip")) {
                    try (ZipFile zip = new ZipFile(path.toFile())) {
                        if (zip.size() > LOCAL_ZIP_MAX_ENTRIES) throw new IOException("Too many ZIP entries");
                        int jarCount = 0;
                        var entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                            if (++jarCount > LOCAL_ZIP_MAX_JARS) throw new IOException("Too many jars in ZIP");
                            if (entry.getSize() > LOCAL_JAR_MAX_BYTES) {
                                result.rejected++; log.log("Oversized jar skipped: " + entry.getName()); continue;
                            }
                            try (InputStream input = zip.getInputStream(entry)) {
                                importLocalJar(input, entry.getName(), modsDir, loader,
                                        overwrite, tracked, seen, result, log);
                            }
                        }
                        if (jarCount == 0) {
                            result.rejected++; log.log("ZIP contains no mod jars: " + path.getFileName());
                        }
                    }
                } else {
                    result.rejected++; log.log("Unsupported file: " + path.getFileName());
                }
            } catch (IOException exception) {
                result.rejected++; log.log("Could not import " + path.getFileName() + ": " + exception.getMessage());
            }
        }
        return result;
    }

    // ============================================================ the app (Swing)
    final Map<String, Object> cfg = loadConfig();
    final AtomicBoolean busy = new AtomicBoolean();
    static boolean PREVIEW_MODE = false;
    volatile Process game;
    boolean loading;
    volatile Map<String, Object> availableLauncherRelease; JButton launcherUpdateButton;
    JFrame frame; JComboBox<String> instBox; JLabel authLabel, packLabel, statusLabel; JTextArea logBox; JProgressBar progress;
    DefaultTableModel modModel, searchModel; JTable modTable, searchTable; JTextField addField, searchField, filterField;
    final Map<String, JTextField> fields = new LinkedHashMap<>(); JCheckBox fullscreen, useGenericRam;
    JLabel effectiveRamLabel; JTextField tokenF, mrTokenF, runtimeF, versionF;
    JComboBox<String> loaderBox, launcherBox; JComboBox<String> backgroundChoice, searchProviderBox;
    JCheckBox onlyCompatibleSearch;
    final List<Map<String, Object>> searchHits = new ArrayList<>();
    transient Image[] bundledBackgrounds; int selectedBackgroundIndex = 0;
    JPanel backgroundRoot, screenRoot; JTabbedPane mainTabs; CardLayout screenLayout;
    JComboBox<String> visualInstanceBox; JLabel visualProfileLabel;
    final List<JButton> profileAwareButtons = new ArrayList<>();
    JLabel notificationLabel; javax.swing.Timer notificationTimer;
    boolean terminalStyleActive = false;
    static volatile boolean VISUAL_GLASS = true;

    Map<String, Object> insts() { return m(cfg.get("instances")); }
    String active() { return s(cfg.get("active")); }
    Map<String, Object> inst() { return m(insts().get(active())); }
    static final String[] BACKGROUND_LABELS = {"Cherry Blossoms", "Moonlit Ruins", "Castle Lake", "Sunset Forest"};
    static final String[] BACKGROUND_RESOURCES = {"/background-1.jpg", "/background-2.jpg", "/background-3.jpg", "/background-4.jpg"};
    void ensureBackgroundsLoaded() {
        if (bundledBackgrounds != null) return;
        bundledBackgrounds = new Image[BACKGROUND_RESOURCES.length];
        for (int i = 0; i < BACKGROUND_RESOURCES.length; i++) {
            try (InputStream in = SkyLauncher.class.getResourceAsStream(BACKGROUND_RESOURCES[i])) {
                if (in != null) bundledBackgrounds[i] = ImageIO.read(in);
            } catch (Exception e) { log("Background load failed: " + e.getMessage()); }
        }
        try { selectedBackgroundIndex = Math.max(0, Math.min(BACKGROUND_RESOURCES.length - 1, Integer.parseInt(s(cfg.getOrDefault("visual_background_index", "0"))))); }
        catch (Exception ignored) { selectedBackgroundIndex = 0; }
    }
    void setSelectedBackgroundIndex(int index) {
        ensureBackgroundsLoaded();
        selectedBackgroundIndex = Math.max(0, Math.min(BACKGROUND_RESOURCES.length - 1, index));
        cfg.put("visual_background_index", String.valueOf(selectedBackgroundIndex));
        saveConfig(cfg);
        if (backgroundChoice != null && backgroundChoice.getSelectedIndex() != selectedBackgroundIndex) backgroundChoice.setSelectedIndex(selectedBackgroundIndex);
        if (backgroundRoot != null) { backgroundRoot.revalidate(); backgroundRoot.repaint(); }
        if (screenRoot != null) { screenRoot.revalidate(); screenRoot.repaint(); }
        if (frame != null) frame.repaint();
    }
    Image currentBackgroundImage() {
        ensureBackgroundsLoaded();
        return bundledBackgrounds != null && selectedBackgroundIndex >= 0 && selectedBackgroundIndex < bundledBackgrounds.length ? bundledBackgrounds[selectedBackgroundIndex] : null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) { selfTest(); return; }
        PREVIEW_MODE = Arrays.asList(args).contains("--ui-preview");
        if (OS.equals("unknown")) { System.err.println("Unsupported OS: " + System.getProperty("os.name")); return; }
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new SkyLauncher().build());
    }

    static String conciseNotice(String message) {
        if (message == null) return "";
        String clean = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() > 105 ? "Details are available in Tools & Logs." : clean;
    }
    void notifyUser(String message) {
        log(message);
        SwingUtilities.invokeLater(() -> {
            if (frame == null || !frame.isShowing()) return;
            JLayeredPane layer = frame.getLayeredPane();
            if (notificationLabel == null) {
                notificationLabel = new JLabel("", SwingConstants.CENTER) {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D gg = (Graphics2D) g.create();
                        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        gg.setColor(new Color(15,25,44,235));
                        gg.fillRoundRect(0,0,getWidth(),getHeight(),22,22);
                        gg.setColor(new Color(255,255,255,95));
                        gg.drawRoundRect(1,1,getWidth()-3,getHeight()-3,22,22);
                        gg.dispose();
                        super.paintComponent(g);
                    }
                };
                notificationLabel.setOpaque(false);
                notificationLabel.setForeground(Color.WHITE);
                notificationLabel.setFont(new Font("Dialog",Font.PLAIN,13));
                layer.add(notificationLabel,JLayeredPane.POPUP_LAYER);
            }
            String notice=conciseNotice(message);
            notificationLabel.setText(notice);
            int width=Math.min(Math.max(260,notificationLabel.getFontMetrics(notificationLabel.getFont()).stringWidth(notice)+35),Math.max(260,frame.getWidth()-50));
            notificationLabel.setBounds(Math.max(10,(frame.getWidth()-width)/2),Math.max(10,frame.getHeight()-106),width,42);
            notificationLabel.setVisible(true);
            layer.repaint();
            if(notificationTimer!=null)notificationTimer.stop();
            notificationTimer=new javax.swing.Timer(4200,e -> notificationLabel.setVisible(false));
            notificationTimer.setRepeats(false);
            notificationTimer.start();
        });
    }
    void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (logBox == null) return;
            logBox.append(msg + "\n");
            if (logBox.getLineCount() > 4000) { try { logBox.replaceRange("", 0, logBox.getLineStartOffset(1000)); } catch (Exception ignored) { } }
            logBox.setCaretPosition(logBox.getDocument().getLength());
        });
    }
    void setBusy(boolean v) { busy.set(v); SwingUtilities.invokeLater(() -> { if (progress != null) progress.setIndeterminate(v); if (statusLabel != null) statusLabel.setText(v ? "Working..." : "Ready"); }); }
    void async(String what, ThrowingRun r) {
        if (busy.get() || game != null) { log("Busy or game running - wait."); return; }
        setBusy(true); saveSettings();
        Thread t = new Thread(() -> {
            try { r.run(); } catch (HttpErr e) { log(what + " ERROR: " + e.getMessage() + (e.code == 401 || e.code == 403 || e.code == 404 ? " (private repo? set a token)" : "")); }
            catch (Exception e) { notifyUser(what + " failed: " + e.getMessage()); }
            finally { setBusy(false); SwingUtilities.invokeLater(this::refreshAll); }
        }); t.setDaemon(true); t.start();
    }
    interface ThrowingRun { void run() throws Exception; }

    // Swing palette adapted from the Python launcher's default dark glass theme.
    static Color BG = new Color(0x16181f), SURFACE = new Color(0x24262d),
        SURFACE2 = new Color(0x30323a), FG = new Color(0xeef1f8),
        MUTED = new Color(0x8b93a8), ACCENT = new Color(0x3d9eff),
        GREEN = new Color(0x2ee59a), RED = new Color(0xe85d5d);
    // Five neutral grayscale shades, from near-black to white.
    static final String[] SHADE_PRESETS = {"Black", "Charcoal", "Gray", "Light Gray", "White"};
    static final int[][] SHADE_COLORS = {
        {0x101010, 0x1d1d1d, 0x303030, 0xf5f5f5, 0xaaaaaa, 0x3d9eff},
        {0x242424, 0x343434, 0x454545, 0xf4f4f4, 0xbdbdbd, 0x3d9eff},
        {0x666666, 0x777777, 0x888888, 0xffffff, 0xe0e0e0, 0x3d9eff},
        {0xd8d8d8, 0xe4e4e4, 0xf0f0f0, 0x202020, 0x555555, 0x3d9eff},
        {0xffffff, 0xf5f5f5, 0xe8e8e8, 0x171717, 0x666666, 0x3d9eff}
    };
    static boolean validHex(String value) {
        return value != null && value.matches("(?i)#[0-9a-f]{6}");
    }
    static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }
    static Color mix(Color a, Color b, double percent) {
        double keep = 1.0 - percent;
        return new Color((int)Math.round(a.getRed()*keep+b.getRed()*percent),
            (int)Math.round(a.getGreen()*keep+b.getGreen()*percent),
            (int)Math.round(a.getBlue()*keep+b.getBlue()*percent));
    }
    static int brightness(Color c) {
        return (c.getRed()*299 + c.getGreen()*587 + c.getBlue()*114)/1000;
    }
    static void setShade(String name, String customAccent, String customBackground, String customText) {
        int idx = Arrays.asList(SHADE_PRESETS).indexOf(name);
        if (idx < 0) idx = 0;
        int[] c = SHADE_COLORS[idx];
        BG = new Color(c[0]); SURFACE = new Color(c[1]); SURFACE2 = new Color(c[2]);
        FG = new Color(c[3]); MUTED = new Color(c[4]); ACCENT = new Color(c[5]);
        if (validHex(customBackground)) {
            BG = Color.decode(customBackground);
            Color direction = brightness(BG) < 140 ? Color.WHITE : Color.BLACK;
            SURFACE = mix(BG, direction, .075);
            SURFACE2 = mix(BG, direction, .14);
            FG = brightness(BG) < 145 ? new Color(0xf5f5f5) : new Color(0x171717);
            MUTED = mix(FG, BG, .38);
        }
        if (validHex(customText)) {
            FG = Color.decode(customText);
            MUTED = mix(FG, BG, .36);
        }
        if (validHex(customAccent)) ACCENT = Color.decode(customAccent);
    }
    void updateTheme(String name, String customAccent, String customBackground, String customText) {
        Color[] old = {BG, SURFACE, SURFACE2, FG, MUTED, ACCENT};
        setShade(name, customAccent, customBackground, customText);
        cfg.put("ui_shade_preset", name);
        cfg.put("ui_accent_hex", customAccent == null ? "" : customAccent);
        cfg.put("ui_background_hex", customBackground == null ? "" : customBackground);
        cfg.put("ui_text_hex", customText == null ? "" : customText);
        saveConfig(cfg);
        applyPythonTheme();
        if (frame != null) {
            recolor(frame, old, new Color[]{BG, SURFACE, SURFACE2, FG, MUTED, ACCENT});
            SwingUtilities.updateComponentTreeUI(frame);
            frame.repaint();
        }
    }
    static void recolor(Component component, Color[] old, Color[] now) {
        for (int k = 0; k < old.length; k++) {
            if (old[k].equals(component.getBackground())) component.setBackground(now[k]);
            if (old[k].equals(component.getForeground())) component.setForeground(now[k]);
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents()) recolor(child, old, now);
    }
    static void applyPythonTheme() {
        UIManager.put("Panel.background", BG);
        UIManager.put("Viewport.background", BG);
        UIManager.put("Label.foreground", FG);
        UIManager.put("Label.background", BG);
        UIManager.put("Button.background", SURFACE2);
        UIManager.put("Button.foreground", FG);
        UIManager.put("Button.select", new Color(0x414b60));
        UIManager.put("TextField.background", SURFACE2);
        UIManager.put("TextField.foreground", FG);
        UIManager.put("TextField.caretForeground", FG);
        UIManager.put("TextArea.background", SURFACE);
        UIManager.put("TextArea.foreground", FG);
        UIManager.put("TextArea.caretForeground", FG);
        UIManager.put("ComboBox.background", SURFACE2);
        UIManager.put("ComboBox.foreground", FG);
        UIManager.put("ComboBox.selectionBackground", ACCENT);
        UIManager.put("ComboBox.selectionForeground", BG);
        UIManager.put("TabbedPane.background", BG);
        UIManager.put("TabbedPane.foreground", FG);
        UIManager.put("TabbedPane.selected", SURFACE);
        UIManager.put("Table.background", SURFACE);
        UIManager.put("Table.foreground", FG);
        UIManager.put("Table.selectionBackground", new Color(0x235d91));
        UIManager.put("Table.selectionForeground", FG);
        UIManager.put("TableHeader.background", SURFACE2);
        UIManager.put("TableHeader.foreground", FG);
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("SplitPane.background", BG);
        UIManager.put("ProgressBar.foreground", ACCENT);
        UIManager.put("ProgressBar.background", SURFACE2);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.messageForeground", FG);
        UIManager.put("CheckBox.background", BG);
        UIManager.put("CheckBox.foreground", FG);
    }
    // ============================================================ first-run desktop shortcut + bundled app icon
    static final String SHORTCUT_ASKED_KEY = "desktop_shortcut_asked";

    static java.awt.Image appIcon() {
        try (InputStream in = SkyLauncher.class.getResourceAsStream("/icons/SkyLauncher.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) { return null; }
    }

    static Path extractIcon(String extension) throws IOException {
        String resource = "/icons/SkyLauncher." + extension;
        Path dest = BASE.resolve("ui").resolve("SkyLauncher." + extension);
        try (InputStream in = SkyLauncher.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Bundled icon missing: " + resource);
            Files.createDirectories(dest.getParent());
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    static Path optionalIcon(String extension) throws IOException {
        return SkyLauncher.class.getResource("/icons/SkyLauncher." + extension) == null
            ? null : extractIcon(extension);
    }

    static String shellQuote(String arg) { return "'" + arg.replace("'", "'\\''") + "'"; }
    static String psQuote(String arg) { return "'" + arg.replace("'", "''") + "'"; }
    static String desktopQuote(String arg) {
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "").replace("\r", "") + "\"";
    }

    static class ShortcutTarget {
        final Path executable;
        final List<String> args;
        ShortcutTarget(Path executable, String... args) {
            this.executable = executable.toAbsolutePath().normalize();
            this.args = List.of(args);
        }
    }

    // Prefer the native launcher's executable when running as a packaged app.
    // Otherwise point directly to this jar with its current Java runtime.
    static ShortcutTarget shortcutTarget() throws IOException {
        // If installed through SkyLauncher Setup, shortcuts should point to the updater,
        // not directly to a versioned launcher JAR.
        Path bootstrap = BASE.resolve("launcher-app/bootstrap-target.txt");
        if (Files.isRegularFile(bootstrap)) {
            try {
                List<String> lines = Files.readAllLines(bootstrap, StandardCharsets.UTF_8);
                if (!lines.isEmpty() && Files.isRegularFile(Paths.get(lines.get(0))) &&
                    (lines.size() < 3 || Files.isRegularFile(Paths.get(lines.get(2)))))
                    return new ShortcutTarget(Paths.get(lines.get(0)), lines.subList(1, lines.size()).toArray(new String[0]));
            } catch (Exception ignored) { }
        }
        String configured = System.getProperty("jpackage.app-path", "");
        if (!configured.isBlank() && Files.isRegularFile(Paths.get(configured)))
            return new ShortcutTarget(Paths.get(configured));
        Path loc;
        try { loc = Paths.get(SkyLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath(); }
        catch (Exception e) { throw new IOException("Cannot locate SkyLauncher: " + e, e); }
        Path folder = Files.isDirectory(loc) ? loc : loc.getParent();
        if (folder != null) {
            List<Path> candidates = new ArrayList<>();
            if (OS.equals("windows")) {
                candidates.add(folder.resolve("../SkyLauncher.exe"));
                candidates.add(folder.resolve("../../SkyLauncher.exe"));
                candidates.add(folder.resolve("SkyLauncher.exe"));
            } else if (OS.equals("osx")) {
                candidates.add(folder.resolve("../MacOS/SkyLauncher"));
                candidates.add(folder.resolve("../../MacOS/SkyLauncher"));
            } else {
                candidates.add(folder.resolve("../../bin/SkyLauncher"));
                candidates.add(folder.resolve("../bin/SkyLauncher"));
            }
            for (Path c : candidates) {
                c = c.normalize();
                if (Files.isRegularFile(c)) return new ShortcutTarget(c);
            }
        }
        Path jhome = Paths.get(System.getProperty("java.home"));
        Path java = jhome.resolve("bin").resolve(OS.equals("windows") ? "javaw.exe" : "java");
        if (!Files.isRegularFile(java)) java = jhome.resolve("bin/java.exe");
        if (!Files.isRegularFile(java)) throw new IOException("Cannot find the Java executable");
        if (Files.isRegularFile(loc) && loc.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
            return new ShortcutTarget(java, "-jar", loc.toString());
        // Source-file mode (java SkyLauncher.java) is useful for developers.
        Path source = Paths.get(System.getProperty("user.dir"), "SkyLauncher.java").toAbsolutePath();
        if (Files.isRegularFile(source)) return new ShortcutTarget(java, source.toString());
        throw new IOException("Create a portable SkyLauncher.jar or use the native installer first");
    }

    static Path desktopFolder() throws IOException {
        if (OS.equals("windows")) {
            try {
                Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "[Environment]::GetFolderPath('DesktopDirectory')").redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (p.waitFor(12, TimeUnit.SECONDS) && p.exitValue() == 0 && !output.isBlank())
                    return Paths.get(output);
                p.destroyForcibly();
            } catch (Exception ignored) { }
            for (Path candidate : List.of(HOME.resolve("Desktop"), HOME.resolve("OneDrive/Desktop")))
                if (Files.isDirectory(candidate)) return candidate;
        }
        if (OS.equals("linux")) {
            Path dirs = HOME.resolve(".config/user-dirs.dirs");
            if (Files.isRegularFile(dirs)) for (String line : Files.readAllLines(dirs)) {
                Matcher m = Pattern.compile("^\\s*XDG_DESKTOP_DIR=\"([^\"]+)\"").matcher(line);
                if (m.find()) {
                    String value = m.group(1).replace("$HOME", HOME.toString());
                    Path d = Paths.get(value);
                    if (Files.isDirectory(d)) return d;
                }
            }
            for (String name : List.of("Desktop", "Bureaublad", "Bureau", "Schreibtisch", "Escritorio"))
                if (Files.isDirectory(HOME.resolve(name))) return HOME.resolve(name);
        }
        Path d = HOME.resolve("Desktop");
        if (Files.isDirectory(d)) return d;
        // A desktop location may not exist in headless/minimal Linux environments.
        // Do not silently create a new unused Desktop folder.
        throw new IOException("Desktop folder not found. Check your OS desktop folder settings.");
    }

    static Path writeDesktopShortcut() throws Exception {
        ShortcutTarget target = shortcutTarget();
        Path desktop = desktopFolder();
        if (OS.equals("windows")) {
            Path icon = optionalIcon("ico");
            Path shortcut = desktop.resolve("SkyLauncher.lnk");
            StringBuilder arguments = new StringBuilder();
            for (String arg : target.args) {
                if (!arguments.isEmpty()) arguments.append(' ');
                arguments.append('"').append(arg.replace("\"", "\\\"")).append('"');
            }
            String script = "$w=New-Object -ComObject WScript.Shell;" +
                "$s=$w.CreateShortcut(" + psQuote(shortcut.toString()) + ");" +
                "$s.TargetPath=" + psQuote(target.executable.toString()) + ";" +
                "$s.Arguments=" + psQuote(arguments.toString()) + ";" +
                "$s.WorkingDirectory=" + psQuote(target.executable.getParent().toString()) + ";" +
                (icon == null ? "" : "$s.IconLocation=" + psQuote(icon.toString() + ",0") + ";") +
                "$s.Description='SkyLauncher - Minecraft launcher';$s.Save();";
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Shortcut creation timed out"); }
            if (p.exitValue() != 0 || !Files.exists(shortcut)) throw new IOException("Windows could not create shortcut: " + out);
            return shortcut;
        }
        if (OS.equals("osx")) {
            // A small desktop .app launcher displays the custom .icns icon in Finder.
            Path shortcut = desktop.resolve("SkyLauncher.app");
            Path macos = shortcut.resolve("Contents/MacOS");
            Path resources = shortcut.resolve("Contents/Resources");
            Files.createDirectories(macos); Files.createDirectories(resources);
            Path macIcon = optionalIcon("icns");
            if (macIcon != null) Files.copy(macIcon, resources.resolve("SkyLauncher.icns"), StandardCopyOption.REPLACE_EXISTING);
            String script = "#!/bin/sh\nexec " + shellQuote(target.executable.toString());
            for (String arg : target.args) script += " " + shellQuote(arg);
            script += " \"$@\"\n";
            Path run = macos.resolve("SkyLauncher");
            Files.writeString(run, script, StandardCharsets.UTF_8);
            run.toFile().setExecutable(true, false);
            Files.writeString(shortcut.resolve("Contents/Info.plist"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\"><dict>" +
                "<key>CFBundleName</key><string>SkyLauncher</string>" +
                "<key>CFBundleIdentifier</key><string>io.skylauncher.desktopshortcut</string>" +
                "<key>CFBundleExecutable</key><string>SkyLauncher</string>" +
                (macIcon == null ? "" : "<key>CFBundleIconFile</key><string>SkyLauncher</string>") +
                "<key>CFBundlePackageType</key><string>APPL</string>" +
                "</dict></plist>\n", StandardCharsets.UTF_8);
            return shortcut;
        }
        Path icon = optionalIcon("png");
        Path shortcut = desktop.resolve("SkyLauncher.desktop");
        StringBuilder exec = new StringBuilder(desktopQuote(target.executable.toString()));
        for (String arg : target.args) exec.append(' ').append(desktopQuote(arg));
        String entry = "[Desktop Entry]\nType=Application\nName=SkyLauncher\nComment=Minecraft launcher\n" +
            "Exec=" + exec + "\n" + (icon == null ? "" : "Icon=" + icon + "\n") + "Terminal=false\nCategories=Game;\nStartupNotify=true\n";
        Files.writeString(shortcut, entry, StandardCharsets.UTF_8);
        shortcut.toFile().setExecutable(true, false);
        // GNOME desktops additionally need the launcher's trust bit; optional if gio isn't installed.
        try {
            new ProcessBuilder("gio", "set", shortcut.toString(), "metadata::trusted", "true")
                .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        return shortcut;
    }

    void askDesktopShortcut() {
        // The bootstrap installer already asked, and owns its own update-aware shortcut.
        if (Files.isRegularFile(BASE.resolve("launcher-app/shortcut-asked.txt"))) return;
        if (b(cfg.get(SHORTCUT_ASKED_KEY))) return;
        int choice = JOptionPane.showConfirmDialog(frame,
            "Would you like a SkyLauncher shortcut on your desktop?\nIf you add custom icon files, the shortcut can use them.",
            "Create desktop shortcut?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            if (!createDesktopShortcut()) return;
        }
        cfg.put(SHORTCUT_ASKED_KEY, true);
        saveConfig(cfg);
    }

    boolean createDesktopShortcut() {
        try {
            Path path = writeDesktopShortcut();
            JOptionPane.showMessageDialog(frame, "Desktop shortcut created:\n" + path,
                "SkyLauncher", JOptionPane.INFORMATION_MESSAGE);
            log("Desktop shortcut created: " + path);
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Could not create the shortcut:\n" + e.getMessage(),
                "SkyLauncher", JOptionPane.ERROR_MESSAGE);
            log("Desktop shortcut creation failed: " + e);
            return false;
        }
    }

    // Screenshot-inspired Swing chrome. No external look-and-feel plugins or assets.
    static Color panelInk() { return BG.getRed() < 120 ? BG.brighter() : BG.darker(); }
    static Color outline() { return mix(BG, brightness(BG) < 145 ? Color.WHITE : Color.BLACK, .23); }
    static Color contrast(Color c) { return (c.getRed()*299+c.getGreen()*587+c.getBlue()*114)/1000 >= 145 ? Color.BLACK : Color.WHITE; }
    static class RoundedSurface extends JPanel {
        final int arc;
        final boolean line;
        RoundedSurface(java.awt.LayoutManager layout, int arc, boolean line) {
            super(layout); this.arc = arc; this.line = line; setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D gg = (Graphics2D) g.create();
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gg.setColor(new Color(SURFACE.getRed(), SURFACE.getGreen(), SURFACE.getBlue(), VISUAL_GLASS ? 64 : 235));
            gg.fillRoundRect(0,0,getWidth()-1,getHeight()-1,arc,arc);
            gg.setColor(new Color(0, 0, 0, 45));
            gg.drawRoundRect(1,1,getWidth()-3,getHeight()-3,arc,arc);
            if (line) {
                gg.setColor(new Color(outline().getRed(), outline().getGreen(), outline().getBlue(), 190));
                gg.drawRoundRect(0,0,getWidth()-1,getHeight()-1,arc,arc);
            }
            gg.dispose(); super.paintComponent(g);
        }
    }
    static class RoundedAction extends JButton {
        final String role;
        boolean hover;
        RoundedAction(String title, String role) {
            super(title); this.role = role;
            setFont(new Font("Dialog", role.equals("play") ? Font.BOLD : Font.PLAIN, role.equals("play") ? 15 : 12));
            setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
            setPreferredSize(new Dimension(role.equals("play") ? 124 : Math.max(84, title.length()*7+34), role.equals("play") ? 42 : 34));
            setContentAreaFilled(false); setFocusPainted(false); setOpaque(false);
            setForeground(role.equals("accent") || role.equals("danger") || role.equals("play") ? Color.WHITE : FG);
            addMouseListener(new java.awt.event.MouseAdapter(){
                public void mouseEntered(java.awt.event.MouseEvent e){ hover=true; repaint(); }
                public void mouseExited(java.awt.event.MouseEvent e){ hover=false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D gg=(Graphics2D)g.create();
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            Color shade = switch(role) {case "accent" -> ACCENT; case "danger" -> RED; case "play" -> GREEN; default -> SURFACE2;};
            if (hover && isEnabled()) shade = shade.brighter();
            if (!isEnabled()) shade=shade.darker();
            gg.setColor(shade); gg.fillRoundRect(0,0,getWidth()-1,getHeight()-1,24,24);
            gg.setColor(new Color(255,255,255,40)); gg.drawRoundRect(0,0,getWidth()-1,getHeight()-1,24,24);
            gg.dispose();
            setForeground(contrast(shade));
            super.paintComponent(g);
        }
    }
    class BackgroundPanel extends JPanel {
        boolean terminalAware;
        BackgroundPanel(java.awt.LayoutManager layout) { this(layout, false); }
        BackgroundPanel(java.awt.LayoutManager layout, boolean terminalAware) {
            super(layout); this.terminalAware = terminalAware; setOpaque(true);
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D gg = (Graphics2D) g.create();
            gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gg.setColor(new Color(14,18,26)); gg.fillRect(0,0,getWidth(),getHeight());
            if (terminalAware && terminalStyleActive) { gg.dispose(); return; }
            Image art = currentBackgroundImage();
            if (art != null) {
                double scale = Math.max(getWidth() / (double) art.getWidth(null), getHeight() / (double) art.getHeight(null));
                int w = (int) Math.ceil(art.getWidth(null) * scale);
                int h = (int) Math.ceil(art.getHeight(null) * scale);
                gg.drawImage(art, (getWidth()-w)/2, (getHeight()-h)/2, w, h, null);
            }
            gg.setColor(new Color(10, 14, 20, 52)); gg.fillRect(0,0,getWidth(),getHeight());
            gg.dispose();
        }
    }
    static class SmoothTabbedPaneUI extends BasicTabbedPaneUI {
        @Override protected void installDefaults() { super.installDefaults(); tabAreaInsets = new Insets(10,8,0,8); selectedTabPadInsets = new Insets(0,0,0,0); }
        @Override protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) { }
        @Override protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            Graphics2D gg=(Graphics2D)g.create(); gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = isSelected ? new Color(SURFACE.getRed(), SURFACE.getGreen(), SURFACE.getBlue(), VISUAL_GLASS ? 64 : 225) : new Color(SURFACE2.getRed(), SURFACE2.getGreen(), SURFACE2.getBlue(), VISUAL_GLASS ? 48 : 170);
            gg.setColor(base); gg.fillRoundRect(x+1, y+2, w-3, h-3, 20, 20); gg.dispose();
        }
        @Override protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            Graphics2D gg=(Graphics2D)g.create(); gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            gg.setColor(new Color(outline().getRed(), outline().getGreen(), outline().getBlue(), 180)); gg.drawRoundRect(x+1, y+2, w-3, h-3, 20, 20); gg.dispose();
        }
        @Override protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex, Rectangle iconRect, Rectangle textRect, boolean isSelected) { }
    }
    JPanel glassCard(java.awt.LayoutManager layout) {
        JPanel p = new RoundedSurface(layout, 22, true);
        p.setBorder(BorderFactory.createEmptyBorder(13, 14, 13, 14));
        return p;
    }
    JButton actionButton(String title, Runnable action, Color bg) {
        String role = bg.equals(GREEN) ? "play" : bg.equals(RED) ? "danger" : "accent";
        JButton b = new RoundedAction(title, role); b.addActionListener(e -> action.run()); return b;
    }
    JButton btn(String title, Runnable action) {
        JButton b = new RoundedAction(title, "neutral"); b.addActionListener(e -> action.run()); return b;
    }
    JLabel smallHeading(String title) {
        JLabel label = new JLabel(title); label.setForeground(ACCENT);
        label.setFont(new Font("Dialog", Font.BOLD, 11)); return label;
    }
    JPanel paddedRow(int gap) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0)); row.setOpaque(false); return row;
    }
    static void styleScroll(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createLineBorder(outline()));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getViewport().setBackground(new Color(0,0,0,0));
        scroll.getVerticalScrollBar().setUnitIncrement(18);
    }
    static void styleTable(JTable table) {
        table.setRowHeight(27); table.setShowVerticalLines(false); table.setGridColor(outline());
        table.setFillsViewportHeight(true); table.getTableHeader().setReorderingAllowed(false);
    }
    JPanel makeActivityLog() {
        JPanel container = new JPanel(new BorderLayout(0, 7));
        container.setOpaque(false); container.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        JPanel title = new JPanel(new BorderLayout()); title.setOpaque(false);
        title.add(smallHeading("RECENT ACTIVITY"), BorderLayout.WEST);
        title.add(btn("Clear", () -> logBox.setText("")), BorderLayout.EAST);
        container.add(title, BorderLayout.NORTH);
        logBox = new JTextArea(5, 80); logBox.setEditable(false);
        logBox.setBackground(SURFACE); logBox.setForeground(GREEN);
        logBox.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logBox.setLineWrap(true); logBox.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scroll = new JScrollPane(logBox); styleScroll(scroll);
        JPanel card = glassCard(new BorderLayout());
        card.add(scroll, BorderLayout.CENTER);
        container.add(card,BorderLayout.CENTER);
        return container;
    }
    JPanel makeBottomBar() {
        JPanel footer = new RoundedSurface(new BorderLayout(10, 0), 26, true);
        footer.setBorder(BorderFactory.createEmptyBorder(10,14,10,14));
        progress = new JProgressBar(); progress.setPreferredSize(new Dimension(116, 12));
        progress.setStringPainted(false);
        statusLabel = new JLabel("Ready"); statusLabel.setForeground(MUTED);
        JPanel left = paddedRow(12); left.add(progress); left.add(statusLabel); footer.add(left,BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0)); right.setOpaque(false);
        right.add(btn("Add to Minecraft Launcher", this::syncOfficial));
        right.add(btn("Set up Minecraft", () -> run(false)));
        right.add(btn("Stop", this::stopGame));
        right.add(actionButton("PLAY", this::play, GREEN));
        footer.add(right,BorderLayout.EAST);
        return footer;
    }
    // Native Swing UI: image backgrounds on every visual page; text backing is 25% opaque.
    class TextGlass extends JPanel {
        final int radius;
        TextGlass(java.awt.LayoutManager layout, int radius) {
            super(layout); this.radius=radius; setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D gg=(Graphics2D)g.create();
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            gg.setColor(new Color(9,17,29,64)); // 75% transparent
            gg.fillRoundRect(0,0,getWidth()-1,getHeight()-1,radius,radius);
            gg.setColor(new Color(255,255,255,70));
            gg.setStroke(new BasicStroke(1.5f));
            gg.drawRoundRect(1,1,getWidth()-3,getHeight()-3,radius,radius);
            gg.dispose(); super.paintComponent(g);
        }
    }
    static class VisualAction extends JButton {
        final boolean primary;
        boolean hovering;
        VisualAction(String label, boolean primary) {
            super(label); this.primary=primary;
            setFont(new Font("Dialog", Font.BOLD, primary ? 18 : 13));
            setForeground(primary ? new Color(24,18,20) : Color.WHITE);
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(12,14,12,14));
            setPreferredSize(new Dimension(primary ? 280 : 135, primary ? 56 : 44));
            addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) { hovering=true; repaint(); }
                public void mouseExited(java.awt.event.MouseEvent e) { hovering=false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D gg=(Graphics2D)g.create();
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill=primary ? new Color(255,190,96,hovering ? 255 : 240)
                : new Color(10,21,37,hovering ? 225 : 185);
            gg.setColor(fill); gg.fillRoundRect(1,1,getWidth()-2,getHeight()-2,24,24);
            gg.setColor(new Color(255,255,255,primary ? 115 : 90));
            gg.setStroke(new BasicStroke(1.4f)); gg.drawRoundRect(1,1,getWidth()-3,getHeight()-3,24,24);
            gg.dispose(); super.paintComponent(g);
        }
    }
    JButton visualButton(String name, boolean primary, Runnable handler) {
        JButton b=new VisualAction(name,primary);
        b.addActionListener(e -> handler.run());
        return b;
    }
    JLabel visualText(String text, int size, boolean bold) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Dialog", bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(Color.WHITE);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }
    JPanel visualNav() {
        TextGlass nav = new TextGlass(new GridLayout(2,5,8,8),24);
        nav.setBorder(BorderFactory.createEmptyBorder(10,14,10,14));
        String[] items={"Home","Servers","News","Packs","Instances","Skin","Settings","Social","About"};
        for (String item : items) nav.add(visualButton(item,false,()->showVisualPage(item)));
        return nav;
    }
    JPanel visualPage(String title, String subtitle, JPanel centerContent) {
        BackgroundPanel page=new BackgroundPanel(new BorderLayout(16,16));
        page.setBorder(BorderFactory.createEmptyBorder(16,24,18,24));
        JPanel header=new JPanel(new BorderLayout(0,12));header.setOpaque(false);
        header.add(visualNav(),BorderLayout.CENTER);
        page.add(header,BorderLayout.NORTH);
        JPanel center=new JPanel(new GridBagLayout());center.setOpaque(false);
        TextGlass glass=new TextGlass(new BorderLayout(0,18),34);
        glass.setBorder(BorderFactory.createEmptyBorder(25,32,25,32));
        JPanel labels=new JPanel(new GridLayout(0,1,0,9));labels.setOpaque(false);
        labels.add(visualText(title,32,true));
        if (!subtitle.isEmpty())labels.add(visualText(subtitle,15,false));
        glass.add(labels,BorderLayout.NORTH);
        if (centerContent!=null)glass.add(centerContent,BorderLayout.CENTER);
        center.add(glass);
        page.add(center,BorderLayout.CENTER);
        JPanel footer=new JPanel(new FlowLayout(FlowLayout.CENTER,12,4));footer.setOpaque(false);
        footer.add(visualButton("Home",false,()->showVisualPage("Home")));
        footer.add(visualButton("Tools & Logs",false,()->showWorkspace(0,false)));
        page.add(footer,BorderLayout.SOUTH);
        return page;
    }
    static String profileActionLabel(String action,String profile) {
        if(profile == null || profile.isBlank()) return action;
        return switch(action) {
            case "Play Minecraft" -> "Play: " + profile;
            case "Open Game Folder", "Open Instance Folder" -> "Folder: " + profile;
            case "Instance Settings", "Game Settings" -> "Settings: " + profile;
            case "Delete Instance" -> "Delete: " + profile;
            case "Check Active Pack" -> "Check pack: " + profile;
            default -> action;
        };
    }
    JButton profileAction(String action,Runnable callback) {
        JButton button=visualButton(profileActionLabel(action,active()),false,callback);
        button.putClientProperty("profileAction",action);
        profileAwareButtons.add(button);
        return button;
    }
    void updateProfileActions() {
        for(JButton button:profileAwareButtons)
            button.setText(profileActionLabel(s(button.getClientProperty("profileAction")),active()));
    }
    JPanel actionsGrid(String[] names, Runnable[] callbacks) {
        JPanel grid=new JPanel(new GridLayout(0,2,16,16)); grid.setOpaque(false);
        grid.setPreferredSize(new Dimension(630, Math.max(1,(names.length+1)/2)*58+Math.max(0,(names.length-1)/2)*16));
        for (int i=0;i<names.length;i++)grid.add(profileAction(names[i],callbacks[i]));
        if (names.length%2==1)grid.add(new JLabel(""));
        return grid;
    }
    JPanel wallpaperChooser() {
        JPanel panel=new JPanel(new FlowLayout(FlowLayout.CENTER,10,5));panel.setOpaque(false);
        JLabel label=visualText("Background:",14,true);panel.add(label);
        JComboBox<String> choice=new JComboBox<>(BACKGROUND_LABELS);
        choice.setSelectedIndex(selectedBackgroundIndex);
        choice.addActionListener(e->{if(choice.getSelectedIndex()>=0)setSelectedBackgroundIndex(choice.getSelectedIndex());});
        panel.add(choice);
        return panel;
    }
    void createVisualPages() {
        JPanel homeCenter=new JPanel();homeCenter.setOpaque(false);
        homeCenter.setLayout(new BoxLayout(homeCenter,BoxLayout.Y_AXIS));
        visualProfileLabel=visualText("Selected profile: "+active(),16,false);
        homeCenter.add(visualProfileLabel);homeCenter.add(Box.createVerticalStrut(24));
        JButton play=visualButton("PLAY MINECRAFT",true,this::play);
        play.setAlignmentX(Component.CENTER_ALIGNMENT);play.setMaximumSize(new Dimension(360,58));
        homeCenter.add(play);homeCenter.add(Box.createVerticalStrut(14));
        JButton instances=visualButton("Manage Instances",false,()->showVisualPage("Instances"));
        instances.setAlignmentX(Component.CENTER_ALIGNMENT);instances.setMaximumSize(new Dimension(360,48));
        homeCenter.add(instances);homeCenter.add(Box.createVerticalStrut(14));
        JButton login=visualButton("Microsoft Sign In",false,this::login);
        login.setAlignmentX(Component.CENTER_ALIGNMENT);login.setMaximumSize(new Dimension(360,48));
        homeCenter.add(login);homeCenter.add(Box.createVerticalStrut(20));
        homeCenter.add(wallpaperChooser());
        screenRoot.add(visualPage("SKYLAUNCHER", "BY FATHOM STUDIOS  |  v3.1.6",homeCenter),"Home");

        screenRoot.add(visualPage("SERVERS", "Launch Minecraft and join your multiplayer servers",
            actionsGrid(new String[]{"Play Minecraft", "Choose Instance", "Game Settings", "Open Game Folder"},
                new Runnable[]{this::play,()->showVisualPage("Instances"),()->showWorkspace(1,false),this::openFolder})),"Servers");
        screenRoot.add(visualPage("NEWS", "Minecraft and Fathom Studios",
            actionsGrid(new String[]{"Minecraft News", "Fathom Studios", "SkyLauncher Project", "Back Home"},
                new Runnable[]{()->openVisualUrl("https://www.minecraft.net/en-us/articles"),
                    ()->openVisualUrl("https://fathomstudios.org"),
                    ()->openVisualUrl("https://skylauncher.fathomstudios.org"),
                    ()->showVisualPage("Home")})),"News");
        screenRoot.add(visualPage("PACKS", "Manage modpacks and installed mods",
            actionsGrid(new String[]{"Mod Manager", "Import mod JARs / ZIPs", "Install Fathom Pack", "Install Pack from Link", "Check All Pack Updates", "Search Modpacks", "Check Active Pack"},
                new Runnable[]{()->showWorkspace(0,false),this::chooseLocalMods,this::installFathom,this::installPackDialog,this::checkAllPackUpdates,this::searchModpacks,this::checkPackUpdate})),"Packs");

        JPanel instanceContent=new JPanel();instanceContent.setOpaque(false);
        instanceContent.setLayout(new BoxLayout(instanceContent,BoxLayout.Y_AXIS));
        visualInstanceBox=new JComboBox<>();
        for(String k:insts().keySet())visualInstanceBox.addItem(k);
        visualInstanceBox.setSelectedItem(active());
        visualInstanceBox.setMaximumSize(new Dimension(350,40));
        visualInstanceBox.setAlignmentX(Component.CENTER_ALIGNMENT);
        visualInstanceBox.addActionListener(e->{
            String selected=(String)visualInstanceBox.getSelectedItem();
            if(!loading && selected!=null && !selected.equals(active()))selectInstance(selected);
        });
        instanceContent.add(visualText("Choose active Minecraft instance",15,true));
        instanceContent.add(Box.createVerticalStrut(12));instanceContent.add(visualInstanceBox);
        instanceContent.add(Box.createVerticalStrut(20));
        instanceContent.add(actionsGrid(new String[]{"New Instance", "Delete Instance", "Open Instance Folder", "Instance Settings"},
            new Runnable[]{this::newInstance,this::deleteInstance,this::openFolder,()->showWorkspace(1,false)}));
        screenRoot.add(visualPage("INSTANCES", "Create and manage separate Minecraft profiles",instanceContent),"Instances");

        screenRoot.add(visualPage("SKIN", "Manage your Minecraft character skin",
            actionsGrid(new String[]{"Open Minecraft Skin Editor", "Microsoft Sign In", "Game Profiles", "Home"},
                new Runnable[]{()->openVisualUrl("https://www.minecraft.net/msaprofile/mygames/editskin"),this::login,
                    ()->showVisualPage("Instances"),()->showVisualPage("Home")})),"Skin");

        JPanel settingsContent=new JPanel();settingsContent.setOpaque(false);
        settingsContent.setLayout(new BoxLayout(settingsContent,BoxLayout.Y_AXIS));
        settingsContent.add(wallpaperChooser()); settingsContent.add(Box.createVerticalStrut(12));
        settingsContent.add(visualText("GENERAL SETTINGS",15,true));
        settingsContent.add(Box.createVerticalStrut(9));
        settingsContent.add(actionsGrid(new String[]{"Generic Modpack Settings", "CurseForge VPS Bridge", "Minecraft Settings", "Manage Instances", "Mods & Logs", "Search Mods"},
            new Runnable[]{this::showGenericModpackSettings,this::showCurseForgeBridgeSettings,
                ()->showWorkspace(1,false),()->showVisualPage("Instances"),
                ()->showWorkspace(0,false),()->showWorkspace(0,false)}));
        settingsContent.add(Box.createVerticalStrut(18));
        settingsContent.add(visualText("INTERFACE STYLE (SEPARATE FROM GAME SETTINGS)",15,true));
        settingsContent.add(Box.createVerticalStrut(8));
        settingsContent.add(actionsGrid(new String[]{"Visual Style", "Terminal Style"},
            new Runnable[]{()->showVisualPage("Home"),()->showWorkspace(1,true)}));
        screenRoot.add(visualPage("SETTINGS", "Change your wallpaper, generic pack defaults, or interface style",settingsContent),"Settings");

        screenRoot.add(visualPage("SOCIAL PLATFORMS", "Find and support Fathom Studios",
            actionsGrid(new String[]{"Fathom Studios Website", "Support on Patreon", "SkyLauncher Source", "SkyLauncher Website"},
                new Runnable[]{()->openVisualUrl("https://fathomstudios.org"),
                    ()->openVisualUrl("https://patreon.com/FathomStudios"),
                    ()->openVisualUrl("https://github.com/Newguygame/skylauncher"),
                    ()->openVisualUrl("https://skylauncher.fathomstudios.org")})),"Social");

        screenRoot.add(visualPage("ABOUT", "SkyLauncher 3.1.6  •  Fathom Studios",
            actionsGrid(new String[]{"Source on GitHub", "Check Updates", "System & Game Settings", "Style Settings"},
                new Runnable[]{()->openVisualUrl("https://github.com/Newguygame/skylauncher"),
                    this::checkLauncherUpdate,()->showWorkspace(1,false),()->showVisualPage("Settings")})),"About");
    }
    void showCurseForgeBridgeSettings() {
        JTextField address = new JTextField(cfConfiguredUrl(cfg), 36);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(8,8,8,8); c.anchor = GridBagConstraints.WEST;
        c.gridx=0; c.gridy=0; form.add(new JLabel("Public CurseForge VPS (HTTPS)"),c);
        c.gridx=1; c.fill=GridBagConstraints.HORIZONTAL; c.weightx=1; form.add(address,c);
        c.gridy++; c.gridx=0; c.gridwidth=2;
        form.add(new JLabel("No user token is necessary; the developer API key remains on the VPS."),c);
        c.gridy++; form.add(new JLabel("VPS identity is checked using its bundled TLS certificate."),c);
        int choice=JOptionPane.showConfirmDialog(frame,form,"CurseForge VPS Bridge",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
        if(choice!=JOptionPane.OK_OPTION)return;
        try {
            String base=validatedCfAddress(address.getText().trim());
            cfg.put("curseforge_bridge_url",base);
            cfg.remove("curseforge_bridge_token");
            saveConfig(cfg);
            log("Public CurseForge VPS set to " + base);
            async("CurseForge VPS connection", () -> {
                Map<String,Object> result=m(cf(cfg,"/v1/search?q=minecraft"));
                int found=l(result.get("data")).size();
                log("Public CurseForge VPS connected ("+found+" results).");
                SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(frame,"Connected to the public CurseForge VPS.","CurseForge",JOptionPane.INFORMATION_MESSAGE));
            });
        } catch(IOException e) {
            JOptionPane.showMessageDialog(frame,e.getMessage(),"Invalid VPS settings",JOptionPane.ERROR_MESSAGE);
        }
    }
    void showGenericModpackSettings() {
        int current = validRamMb(cfg.get("generic_memory_mb"), 4096);
        JSpinner memoryGb = new JSpinner(new SpinnerNumberModel(current / 1024.0, 0.5, 256.0, 0.5));
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(memoryGb, "0.0");
        memoryGb.setEditor(editor);
        JTextField commonJvm = new JTextField(s(cfg.get("generic_jvm_args")), 28);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(9, 9, 9, 9); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; form.add(new JLabel("Default RAM for all packs (GB)"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(memoryGb, c);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Default JVM arguments (optional)"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(commonJvm, c);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        form.add(new JLabel("Packs inherit these defaults unless their own settings override them."), c);
        int result = JOptionPane.showConfirmDialog(frame, form, "Generic Modpack Settings",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        try {
            memoryGb.commitEdit();
            int newRam = (int) Math.round(((Number)memoryGb.getValue()).doubleValue() * 1024);
            if (newRam < 512 || newRam > 262144) throw new IllegalArgumentException("RAM must be between 0.5 and 256 GB.");
            // Check JVM argument syntax before writing the shared setting.
            shlex(commonJvm.getText().trim());
            cfg.put("generic_memory_mb", String.valueOf(newRam));
            cfg.put("generic_jvm_args", commonJvm.getText().trim());
            saveConfig(cfg);
            updateRamControls();
            log("Generic modpack defaults updated: " + newRam + " MB RAM.");
            JOptionPane.showMessageDialog(frame, "Generic defaults saved. Inherited packs now use "
                + (newRam / 1024.0) + " GB RAM. Pack-specific overrides are unchanged.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Invalid generic setting: " + e.getMessage(),
                "Settings", JOptionPane.ERROR_MESSAGE);
        }
    }
    void updateRamControls() {
        if (useGenericRam != null) {
            JTextField override = fields.get("memory_mb");
            if (override != null) override.setEnabled(!useGenericRam.isSelected());
        }
        if (effectiveRamLabel != null && !inst().isEmpty()) {
            int mb = effectiveMemoryMb(cfg, inst());
            effectiveRamLabel.setText(String.format(Locale.ROOT, "Effective RAM: %.1f GB (%s)",
                mb / 1024.0, b(inst().get("memory_inherit")) ? "generic" : "pack override"));
        }
    }

    void openVisualUrl(String url) {
        try { Desktop.getDesktop().browse(URI.create(url)); }
        catch (Exception e) { JOptionPane.showMessageDialog(frame,"Unable to open: "+url+"\n"+e.getMessage()); }
    }
    void showVisualPage(String page) {
        terminalStyleActive=false; VISUAL_GLASS=true;
        cfg.put("fathom_ui_style","visual");saveConfig(cfg);
        screenLayout.show(screenRoot,page);screenRoot.repaint();
    }
    void showWorkspace(int tab, boolean terminal) {
        terminalStyleActive=terminal; VISUAL_GLASS=!terminal;
        cfg.put("fathom_ui_style",terminal?"terminal":"visual");saveConfig(cfg);
        mainTabs.setSelectedIndex(tab);
        screenLayout.show(screenRoot,"workspace");
        screenRoot.repaint();
    }

    void build() {
        setShade(s(cfg.getOrDefault("ui_shade_preset", "Black")), s(cfg.get("ui_accent_hex")),
            s(cfg.get("ui_background_hex")), s(cfg.get("ui_text_hex")));
        applyPythonTheme(); ensureBackgroundsLoaded();
        frame = new JFrame("SkyLauncher " + VERSION);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(940, 620)); frame.setSize(1360, 760);
        java.awt.Image iconImage = appIcon(); if (iconImage != null) frame.setIconImage(iconImage);
        backgroundRoot = new BackgroundPanel(new BorderLayout(), true);

        JPanel header = new RoundedSurface(new BorderLayout(12, 0), 30, true);
        header.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        JLabel brand = new JLabel("SkyLauncher"); brand.setFont(new Font("Dialog", Font.BOLD, 23));
        brand.setForeground(FG);
        JPanel brandStack = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0)); brandStack.setOpaque(false);
        brandStack.add(brand);
        JLabel versionLabel = new JLabel("v"+VERSION); versionLabel.setForeground(MUTED); versionLabel.setFont(new Font("Dialog",Font.PLAIN,11));
        brandStack.add(versionLabel); header.add(brandStack,BorderLayout.WEST);
        JPanel workspaceNav = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0)); workspaceNav.setOpaque(false);
        workspaceNav.add(btn("← Visual Home", () -> showVisualPage("Home")));
        workspaceNav.add(btn("Wallpaper tabs", () -> showWorkspace(mainTabs.getSelectedIndex(), false)));
        workspaceNav.add(btn("Terminal style", () -> showWorkspace(mainTabs.getSelectedIndex(), true)));
        header.add(workspaceNav, BorderLayout.CENTER);
        JPanel account = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); account.setOpaque(false);
        authLabel = new JLabel(); authLabel.setForeground(MUTED); account.add(authLabel);
        launcherUpdateButton = actionButton("Update launcher", this::offerLauncherUpdate, ACCENT);
        launcherUpdateButton.setVisible(false); account.add(launcherUpdateButton);
        account.add(actionButton("Log in with Microsoft", this::login, ACCENT));
        account.add(btn("Log out", () -> { cfg.put("account", null); saveConfig(cfg); updateAuth(); log("Logged out."); }));
        header.add(account, BorderLayout.EAST);

        JPanel profile = new RoundedSurface(new FlowLayout(FlowLayout.LEFT, 13, 8), 26, true);
        profile.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        profile.add(smallHeading("PROFILE"));
        instBox = new JComboBox<>(); instBox.setPrototypeDisplayValue("xxxxxxxxxxxxxxxxxxxxxxxx");
        instBox.setPreferredSize(new Dimension(228, 32)); profile.add(instBox);
        instBox.addActionListener(e -> { if (!loading && instBox.getSelectedItem() != null) selectInstance((String) instBox.getSelectedItem()); });
        profile.add(btn("New profile", this::newInstance)); profile.add(btn("Open folder", this::openFolder));
        profile.add(actionButton("Delete", this::deleteInstance, RED));
        JPanel top = new JPanel(new BorderLayout(0, 8)); top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(10, 11, 0, 11));
        top.add(header,BorderLayout.NORTH); top.add(profile,BorderLayout.SOUTH);

        mainTabs = new JTabbedPane(); mainTabs.setOpaque(false); mainTabs.setBackground(new Color(0,0,0,0));
        mainTabs.setUI(new SmoothTabbedPaneUI());
        mainTabs.addTab("Mods",modsTab()); mainTabs.addTab("Settings",settingsTab());
        mainTabs.setFont(new Font("Dialog",Font.PLAIN,12));
        JPanel activity = makeActivityLog();
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,mainTabs,activity);
        split.setOpaque(false); split.setBorder(null); split.setResizeWeight(.62); split.setDividerSize(5);
        split.setContinuousLayout(true);
        JPanel footerHolder = new JPanel(new BorderLayout()); footerHolder.setOpaque(false);
        footerHolder.setBorder(BorderFactory.createEmptyBorder(6,11,10,11));
        footerHolder.add(makeBottomBar(),BorderLayout.CENTER);
        backgroundRoot.add(top, BorderLayout.NORTH); backgroundRoot.add(split,BorderLayout.CENTER);
        backgroundRoot.add(footerHolder,BorderLayout.SOUTH);
        screenLayout = new CardLayout(); screenRoot = new JPanel(screenLayout);
        screenRoot.add(backgroundRoot, "workspace");
        createVisualPages();
        frame.setContentPane(screenRoot);
        frame.setLocationRelativeTo(null); frame.setVisible(true);
        selectInstance(active());
        if ("terminal".equals(s(cfg.get("fathom_ui_style")))) showWorkspace(0, true);
        else showVisualPage("Home");
        if (!PREVIEW_MODE) { checkLauncherUpdate(); SwingUtilities.invokeLater(this::askDesktopShortcut); }
    }
    // Default upstream matches the Python launcher; allow user overrides.
    String launcherRepo() {
        String repo = System.getenv("SKYLAUNCHER_UPDATE_REPO");
        if (repo == null || repo.isBlank()) repo = s(cfg.get("update_repo"));
        if (repo.isBlank()) repo = s(cfg.get("launcher_update_repo"));
        if (repo.isBlank()) repo = "Newguygame/skylauncher";
        repo = repo.trim().replaceFirst("^https?://github\\.com/", "").replaceAll("/+$", "");
        return repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") ? repo : "";
    }
    static int compareReleaseVersions(String a, String b) {
        String[] aa = a.replaceFirst("^[vV]", "").split("[.-]"), bb = b.replaceFirst("^[vV]", "").split("[.-]");
        for (int i = 0; i < Math.max(aa.length, bb.length); i++) {
            int x = i < aa.length && aa[i].matches("\\d+") ? Integer.parseInt(aa[i]) : 0;
            int y = i < bb.length && bb[i].matches("\\d+") ? Integer.parseInt(bb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
    // Updates are staged first. The currently running launcher exits before a separate
    // platform helper replaces program files, keeping ~/.ghmodlauncher untouched.
    static final class LauncherUpdate {
        final String mode, name, url, digest;
        LauncherUpdate(String mode, String name, String url, String digest) {
            this.mode=mode; this.name=name; this.url=url; this.digest=digest;
        }
    }
    static Path runningJar() throws IOException {
        try { return Paths.get(SkyLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize(); }
        catch (Exception e) { throw new IOException("Cannot identify running SkyLauncher JAR", e); }
    }
    static boolean isNativeWindows(Path jar) {
        if (!OS.equals("windows")) return false;
        Path p=jar.getParent();
        return p != null && p.getFileName().toString().equalsIgnoreCase("app")
            && Files.isRegularFile(p.getParent().resolve("SkyLauncher.exe"));
    }
    static Path macBundle(Path jar) {
        Path p=jar;
        while (p!=null) { if (p.getFileName()!=null && p.getFileName().toString().endsWith(".app")) return p; p=p.getParent(); }
        return null;
    }
    static boolean isNativeLinux(Path jar) {
        // jpackage's Linux DEB installs to /opt/skylauncher/lib/app/SkyLauncher.jar.
        return OS.equals("linux") && jar.startsWith(Paths.get("/opt/skylauncher"));
    }
    static String updateMode(Path jar) {
        if(isNativeWindows(jar))return "win-installer";
        if(OS.equals("osx") && macBundle(jar)!=null)return "mac-app";
        if(isNativeLinux(jar))return "linux-deb";
        return "portable-jar";
    }
    static LauncherUpdate selectLauncherAsset(Map<String,Object> release, String mode, String os, String arch) {
        String tag=s(release.get("tag_name")).replaceFirst("^[vV]", "");
        if(!tag.matches("\\d+\\.\\d+\\.\\d+"))return null;
        String exactInstaller="SkyLauncher-"+tag+".exe";
        String macArch=arch.equals("arm64")?"-arm64.dmg":"-intel.dmg";
        for(Object obj:l(release.get("assets"))) {
            Map<String,Object> a=m(obj);
            String name=s(a.get("name")), lower=name.toLowerCase(Locale.ROOT);
            boolean matches=switch(mode) {
                case "portable-jar" -> name.equals("SkyLauncher.jar");
                case "win-installer" -> name.equalsIgnoreCase(exactInstaller);
                case "mac-app" -> lower.equals(("SkyLauncher-"+tag+macArch).toLowerCase(Locale.ROOT));
                case "linux-deb" -> lower.matches("skylauncher_"+Pattern.quote(tag)+"_"+(arch.equals("arm64")?"(arm64|aarch64)":"(amd64|x86_64)")+"\\.deb");
                default -> false;
            };
            if(!matches)continue;
            String url=s(a.get("browser_download_url"));
            String expected="https://github.com/Newguygame/skylauncher/releases/download/v"+tag+"/";
            // Never install arbitrary URLs from an alternate repository or mirror.
            if(!url.startsWith(expected) || !url.endsWith("/"+name))continue;
            String digest=s(a.get("digest"));
            if(!digest.matches("sha256:[a-fA-F0-9]{64}"))continue;
            return new LauncherUpdate(mode,name,url,digest.substring(7).toLowerCase(Locale.ROOT));
        }
        return null;
    }
    static String sha256File(Path f) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(f)) { byte[] buf=new byte[65536]; int n; while((n=in.read(buf))!=-1) md.update(buf,0,n); }
        return HexFormat.of().formatHex(md.digest());
    }
    static Path downloadVerifiedUpdate(LauncherUpdate update) throws Exception {
        Path dir=Files.createTempDirectory("skylauncher-update-");
        Path target=dir.resolve(update.name);
        try {
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request=HttpRequest.newBuilder(URI.create(update.url))
                .timeout(Duration.ofMinutes(15)).header("User-Agent", "SkyLauncher-Updater/"+VERSION).GET().build();
            HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()!=200 || !"https".equalsIgnoreCase(response.uri().getScheme())) {
                try(InputStream in=response.body()) { in.transferTo(OutputStream.nullOutputStream()); }
                throw new IOException("Update download failed: HTTP "+response.statusCode());
            }
            try(InputStream in=response.body(); OutputStream out=Files.newOutputStream(target)) {
                byte[] buf=new byte[65536]; int n; long total=0;
                while((n=in.read(buf))!=-1) {
                    total+=n;
                    if(total>1_500_000_000L)throw new IOException("Update exceeds the 1.5 GB limit");
                    out.write(buf,0,n);
                }
            }
            if(!sha256File(target).equals(update.digest))throw new IOException("SHA-256 verification failed: update was not installed");
            if(update.mode.equals("portable-jar")) {
                try(java.util.jar.JarFile jf=new java.util.jar.JarFile(target.toFile())) {
                    if(jf.getEntry("SkyLauncher.class")==null)throw new IOException("Update is not a SkyLauncher JAR");
                }
            }
            return target;
        }catch(Exception ex) {
            Files.deleteIfExists(target); Files.deleteIfExists(dir); throw ex;
        }
    }
    static Path writeUpdaterScript(String resource, Path staging, String extension) throws IOException {
        Path p=staging.resolve("skylauncher-update"+extension);
        try(InputStream in=SkyLauncher.class.getResourceAsStream(resource)) {
            if(in==null)throw new IOException("Updater component missing: "+resource);
            Files.copy(in,p,StandardCopyOption.REPLACE_EXISTING);
        }
        p.toFile().setExecutable(true);
        return p;
    }
    static ProcessBuilder updaterProcess(LauncherUpdate update, Path download, Path source) throws Exception {
        Path dir=download.getParent();
        long pid=ProcessHandle.current().pid();
        String mode=update.mode;
        if(OS.equals("windows")) {
            Path script=writeUpdaterScript("/updater-windows.ps1",dir,".ps1");
            String exe=mode.equals("win-installer")?source.getParent().getParent().resolve("SkyLauncher.exe").toString():"";
            Path java=Paths.get(System.getProperty("java.home"),"bin","javaw.exe");
            if(!Files.isRegularFile(java))java=Paths.get(System.getProperty("java.home"),"bin","java.exe");
            return new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",script.toString(),
                "-Mode",mode,"-Package",download.toString(),"-Target",source.toString(),"-Launcher",exe,
                "-Java",java.toString(),"-ParentPid",String.valueOf(pid));
        }
        Path script=writeUpdaterScript("/updater-unix.sh",dir,".sh");
        Path java=Paths.get(System.getProperty("java.home"),"bin","java");
        String app=mode.equals("mac-app")?macBundle(source).toString():"";
        return new ProcessBuilder("/bin/sh",script.toString(),mode,download.toString(),source.toString(),
            app,java.toString(),String.valueOf(pid));
    }
    void checkLauncherUpdate() {
        String repo=launcherRepo();
        if(repo.isEmpty())return;
        Thread worker=new Thread(()->{
            try {
                Map<String,Object> rel=m(getJson("https://api.github.com/repos/"+repo+"/releases/latest",ghHeaders(cfg,null)));
                String tag=s(rel.get("tag_name"));
                if(compareReleaseVersions(tag,VERSION)<=0)return;
                LauncherUpdate update=selectLauncherAsset(rel,updateMode(runningJar()),OS,ARCH);
                if(update==null) { log("Version "+tag+" is available, but a verified updater package for this platform is not yet available."); return; }
                availableLauncherRelease=rel;
                SwingUtilities.invokeLater(()->{
                    launcherUpdateButton.setVisible(true);
                    launcherUpdateButton.setToolTipText("Install SkyLauncher "+tag+" and replace this version");
                    // All supported installations use the same verified automatic updater.
                    // Linux DEB still requires interactive system authorization via pkexec.
                    // The updater stages and verifies the new package before closing SkyLauncher.
                    log("Automatic "+OS+" update to "+tag+" is available ("+update.mode+").");
                    offerLauncherUpdate();
                });
            }catch(Exception e){log("Launcher update check failed: "+e.getMessage());}
        },"SkyLauncher-update-check");
        worker.setDaemon(true); worker.start();
    }
    void offerLauncherUpdate() {
        Map<String,Object> rel=availableLauncherRelease;
        if(rel==null) {
            JOptionPane.showMessageDialog(frame,"No verified update is available for this platform yet.");
            return;
        }
        if(game!=null || busy.get()) {
            JOptionPane.showMessageDialog(frame,"Please finish the current Minecraft game or download before updating.");
            return;
        }
        LauncherUpdate update;
        Path source;
        try {
            source=runningJar();
            if(!Files.isRegularFile(source))throw new IOException("SkyLauncher must run from its installed JAR to update");
            update=selectLauncherAsset(rel,updateMode(source),OS,ARCH);
            if(update==null)throw new IOException("No verified platform installer is attached to this GitHub release yet");
            if(update.mode.equals("linux-deb") && !Files.isExecutable(Paths.get("/usr/bin/pkexec")))
                throw new IOException("Linux package updates need polkit (pkexec). Install it or update via your package manager.");
        }catch(Exception e) {JOptionPane.showMessageDialog(frame,e.getMessage(),"Updater",JOptionPane.ERROR_MESSAGE);return;}
        launcherUpdateButton.setEnabled(false);
        setBusy(true);
        Thread worker=new Thread(()->{
            try {
                log("Downloading "+update.name+" from GitHub Releases...");
                Path file=downloadVerifiedUpdate(update);
                log("Verified release SHA-256. Updating once SkyLauncher closes...");
                ProcessBuilder pb=updaterProcess(update,file,source);
                pb.directory(file.getParent().toFile());
                pb.redirectOutput(file.getParent().resolve("updater.log").toFile());
                pb.redirectErrorStream(true);
                pb.start();
                SwingUtilities.invokeLater(()->{saveSettings();frame.dispose();System.exit(0);});
            }catch(Exception e){
                log("Update failed; current version was not changed: "+e);
                SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(frame,
                    "Update failed. Your current SkyLauncher is unchanged.\n"+e.getMessage(),"SkyLauncher updater",JOptionPane.ERROR_MESSAGE));
            }finally{setBusy(false);SwingUtilities.invokeLater(()->launcherUpdateButton.setEnabled(true));}
        },"SkyLauncher-install-update");
        worker.setDaemon(true);worker.start();
    }

    JPanel modsTab() {
        JPanel content = new JPanel(); content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));

        JPanel packCard = glassCard(new BorderLayout(0, 9));
        packCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        packCard.add(smallHeading("MODPACK"),BorderLayout.NORTH);
        JPanel packBody = new JPanel(); packBody.setOpaque(false);
        packBody.setLayout(new BoxLayout(packBody,BoxLayout.Y_AXIS));
        packLabel = new JLabel(); packLabel.setFont(new Font("Dialog",Font.BOLD,13)); packLabel.setForeground(FG);
        packLabel.setAlignmentX(Component.LEFT_ALIGNMENT); packBody.add(packLabel);
        packBody.add(Box.createVerticalStrut(12));
        JPanel packActions = paddedRow(14);
        packActions.add(btn("Install Fathom pack", this::installFathom));
        packActions.add(btn("Install modpack from link",this::installPackDialog));
        packActions.add(btn("Check active pack", this::checkPackUpdate));
        packActions.add(btn("Update all packs", this::checkAllPackUpdates));
        packActions.add(btn("Search modpacks", this::searchModpacks));
        packActions.add(btn("Reinstall modpack",this::reinstallPack));
        packActions.setAlignmentX(Component.LEFT_ALIGNMENT); packBody.add(packActions);
        packCard.add(packBody,BorderLayout.CENTER);
        content.add(packCard); content.add(Box.createVerticalStrut(12));

        JPanel addCard = glassCard(new BorderLayout(0, 10));
        addCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        addCard.add(smallHeading("ADD MOD"),BorderLayout.NORTH);
        JPanel addInput = new JPanel(new BorderLayout(12,0)); addInput.setOpaque(false);
        addField = new JTextField(); addField.setPreferredSize(new Dimension(120,32));
        addField.addActionListener(e -> addMod());
        addInput.add(addField,BorderLayout.CENTER);
        JPanel modButtons = paddedRow(8);
        modButtons.add(actionButton("Add mod",this::addMod,ACCENT));
        modButtons.add(btn("Import .jar / .zip", this::chooseLocalMods));
        addInput.add(modButtons,BorderLayout.EAST);
        addCard.add(addInput,BorderLayout.CENTER);
        JLabel tip = new JLabel("GitHub URL  ·  Modrinth link  ·  CurseForge mod URL  ·  curseforge:123456  ·  direct .jar URL");
        tip.setForeground(MUTED); tip.setFont(new Font("Dialog",Font.PLAIN,12));
        addCard.add(tip,BorderLayout.SOUTH);
        content.add(addCard); content.add(Box.createVerticalStrut(12));

        JPanel searchCard = glassCard(new BorderLayout(0,10)); searchCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel searchHeading = new JPanel(new FlowLayout(FlowLayout.LEFT,14,0)); searchHeading.setOpaque(false);
        searchHeading.add(smallHeading("SEARCH MODS"));
        searchProviderBox = new JComboBox<>(new String[]{"Modrinth", "CurseForge (VPS)"});
        searchProviderBox.setSelectedItem(s(cfg.getOrDefault("mod_search_provider", "Modrinth")));
        searchProviderBox.addActionListener(e -> { cfg.put("mod_search_provider",searchProviderBox.getSelectedItem()); saveConfig(cfg); });
        searchHeading.add(searchProviderBox);
        onlyCompatibleSearch = new JCheckBox("Only compatible with this profile");
        onlyCompatibleSearch.setOpaque(false);
        onlyCompatibleSearch.setForeground(FG);
        onlyCompatibleSearch.setSelected(!Boolean.FALSE.equals(cfg.get("compatible_mod_filter")));
        onlyCompatibleSearch.addActionListener(e -> {
            cfg.put("compatible_mod_filter", onlyCompatibleSearch.isSelected()); saveConfig(cfg);
        });
        searchHeading.add(onlyCompatibleSearch);
        searchHeading.add(btn("VPS",this::showCurseForgeBridgeSettings));
        searchCard.add(searchHeading,BorderLayout.NORTH);
        JPanel searchBody = new JPanel(new BorderLayout(8,9)); searchBody.setOpaque(false);
        JPanel query = new JPanel(new BorderLayout(12,0)); query.setOpaque(false);
        searchField = new JTextField(); searchField.setPreferredSize(new Dimension(120,33)); searchField.addActionListener(e -> search());
        query.add(searchField,BorderLayout.CENTER); query.add(actionButton("Search",this::search,ACCENT),BorderLayout.EAST);
        searchBody.add(query,BorderLayout.NORTH);
        searchModel = new DefaultTableModel(new String[]{"Name","Downloads","Description"},0){public boolean isCellEditable(int r,int c){return false;}};
        searchTable = new JTable(searchModel); styleTable(searchTable);
        searchTable.getColumnModel().getColumn(0).setPreferredWidth(180); searchTable.getColumnModel().getColumn(1).setPreferredWidth(80); searchTable.getColumnModel().getColumn(2).setPreferredWidth(620);
        JScrollPane searchScroll = new JScrollPane(searchTable); styleScroll(searchScroll);
        searchScroll.setPreferredSize(new Dimension(500,150)); searchBody.add(searchScroll,BorderLayout.CENTER);
        JPanel searchActions = paddedRow(10); searchActions.add(btn("Install selected",this::installSearched));
        searchBody.add(searchActions,BorderLayout.SOUTH);
        searchTable.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2)installSearched();}});
        searchCard.add(searchBody,BorderLayout.CENTER); content.add(searchCard); content.add(Box.createVerticalStrut(12));

        JPanel installedCard = glassCard(new BorderLayout(0,10)); installedCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        installedCard.add(smallHeading("INSTALLED MODS"),BorderLayout.NORTH);
        JPanel tracker = new JPanel(new BorderLayout(8,8)); tracker.setOpaque(false);
        JPanel filter = new JPanel(new BorderLayout(12,0)); filter.setOpaque(false);
        filter.add(new JLabel("Filter mods"), BorderLayout.WEST);
        filterField = new JTextField(25); filter.add(filterField,BorderLayout.CENTER);
        filterField.getDocument().addDocumentListener(simple(this::refreshList)); tracker.add(filter,BorderLayout.NORTH);
        modModel = new DefaultTableModel(new String[]{"Name","Version","Jar file","Source","Role"},0){public boolean isCellEditable(int r,int c){return false;}};
        modTable = new JTable(modModel); styleTable(modTable); modTable.setAutoCreateRowSorter(true);
        JScrollPane installedScroll = new JScrollPane(modTable); styleScroll(installedScroll);
        installedScroll.setPreferredSize(new Dimension(500,165)); tracker.add(installedScroll,BorderLayout.CENTER);
        JPanel ops = paddedRow(10); ops.add(btn("Update selected",()->updateMods(true)));
        ops.add(btn("Update all",()->updateMods(false))); ops.add(btn("Verify hashes",this::verify));
        ops.add(btn("Remove selected",this::removeSelected)); tracker.add(ops,BorderLayout.SOUTH);
        installedCard.add(tracker,BorderLayout.CENTER); content.add(installedCard);
        JScrollPane outer = new JScrollPane(content,ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        outer.setBorder(null); outer.setOpaque(false); outer.getViewport().setOpaque(false);
        outer.getViewport().setBackground(new Color(0,0,0,0));
        outer.getVerticalScrollBar().setUnitIncrement(22);
        JPanel wrapper=new JPanel(new BorderLayout()); wrapper.setOpaque(false); wrapper.add(outer,BorderLayout.CENTER);
        return wrapper;
    }
    // Modpack actions are intentionally on the Mods tab to match the Python launcher.
    /** Settings keep common choices visible; expert options are in a separate dialog. */
    JPanel settingsTab() {
        JPanel page = new JPanel();
        page.setOpaque(false);
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));

        // These invisible controls continue to be populated when switching profiles, so
        // the old configuration schema and installer/launch code stay compatible.
        versionF = new JTextField();
        runtimeF = new JTextField(s(cfg.get("runtime_dir")));
        tokenF = new JTextField(s(cfg.get("token")));
        mrTokenF = new JTextField(s(cfg.get("modrinth_token")));
        fullscreen = new JCheckBox();

        JPanel gameCard = glassCard(new BorderLayout(0, 12));
        gameCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        gameCard.add(smallHeading("MINECRAFT SETTINGS"), BorderLayout.NORTH);
        JPanel gameForm = new JPanel(new GridBagLayout()); gameForm.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 8, 7, 8); c.gridy = 0; c.anchor = GridBagConstraints.WEST;

        addSettingsField(gameForm, c, "Minecraft version", "mc_version",
            "For example: 1.21.1. Each profile can use a different version.");
        c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        gameForm.add(new JLabel("Mod loader"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        loaderBox = new JComboBox<>(LOADERS); loaderBox.addActionListener(e -> saveSettings());
        loaderBox.setToolTipText("Choose NeoForge, Forge, Fabric or Quilt to match your mods.");
        gameForm.add(loaderBox, c); c.gridy++;
        c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        gameForm.add(new JLabel("RAM policy"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        useGenericRam = new JCheckBox("Use generic RAM (otherwise override below)");
        useGenericRam.setOpaque(false);
        useGenericRam.addActionListener(e -> {
            if (loading) return;
            if (!useGenericRam.isSelected()) {
                JTextField override = fields.get("memory_mb");
                if (override != null && b(inst().get("memory_inherit"))) {
                    loading = true; override.setText(String.valueOf(effectiveMemoryMb(cfg, inst()))); loading = false;
                }
            }
            saveSettings(); updateRamControls();
        });
        gameForm.add(useGenericRam,c); c.gridy++;
        addSettingsField(gameForm, c, "Pack RAM override (MB)", "memory_mb",
            "Only used if generic RAM is unchecked. Example: 8192 MB = 8 GB.");
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        effectiveRamLabel = new JLabel("Effective RAM: from generic settings");
        gameForm.add(effectiveRamLabel,c); c.gridy++;
        addSettingsField(gameForm, c, "Pack JVM arguments (optional)", "jvm_args",
            "Leave blank to inherit generic JVM arguments; otherwise this pack overrides them.");
        gameCard.add(gameForm, BorderLayout.CENTER);
        page.add(gameCard); page.add(Box.createVerticalStrut(12));

        JPanel genericCard = glassCard(new BorderLayout(0, 8));
        genericCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        genericCard.add(smallHeading("GENERIC MODPACK SETTINGS"), BorderLayout.NORTH);
        JPanel genericButtons = paddedRow(12);
        genericButtons.add(btn("Edit generic RAM and JVM defaults...", this::showGenericModpackSettings));
        genericButtons.add(btn("CurseForge VPS Bridge...", this::showCurseForgeBridgeSettings));
        genericCard.add(genericButtons,BorderLayout.CENTER);
        JLabel genericExplanation = new JLabel("Shared by all packs unless a pack uses its own override.");
        genericCard.add(genericExplanation,BorderLayout.SOUTH);
        page.add(genericCard); page.add(Box.createVerticalStrut(12));

        JPanel appearanceCard = glassCard(new BorderLayout(0, 12));
        appearanceCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        appearanceCard.add(smallHeading("APPEARANCE"), BorderLayout.NORTH);
        JPanel appearance = new JPanel(new GridBagLayout()); appearance.setOpaque(false);
        GridBagConstraints a = new GridBagConstraints();
        a.insets = new Insets(7, 8, 7, 8); a.gridy = 0; a.anchor = GridBagConstraints.WEST;
        a.gridx = 0; appearance.add(new JLabel("Light / dark preset"), a);
        a.gridx = 1; a.weightx = 1; a.fill = GridBagConstraints.HORIZONTAL;
        JComboBox<String> shades = new JComboBox<>(SHADE_PRESETS);
        shades.setSelectedItem(s(cfg.getOrDefault("ui_shade_preset", "Black")));
        appearance.add(shades, a); a.gridy++;

        JTextField backgroundHex = new JTextField(s(cfg.get("ui_background_hex")), 9);
        JTextField textHex = new JTextField(s(cfg.get("ui_text_hex")), 9);
        JTextField accentHex = new JTextField(s(cfg.get("ui_accent_hex")), 9);
        Runnable applyColors = () -> updateTheme((String)shades.getSelectedItem(),
            accentHex.getText().trim(), backgroundHex.getText().trim(), textHex.getText().trim());
        shades.addActionListener(e -> {
            // Preset resets only background and text; keep the chosen accent color.
            backgroundHex.setText(""); textHex.setText(""); applyColors.run();
        });
        addColorPicker(appearance, a, "Background color", backgroundHex, "Background", () -> BG, applyColors);
        addColorPicker(appearance, a, "Text color", textHex, "Text", () -> FG, applyColors);
        addColorPicker(appearance, a, "Accent color", accentHex, "Accent", () -> ACCENT, applyColors);
        a.gridx = 0; a.weightx = 0; a.fill = GridBagConstraints.NONE;
        appearance.add(new JLabel("Wallpaper"), a);
        a.gridx = 1; a.weightx = 1; a.fill = GridBagConstraints.HORIZONTAL;
        JPanel wallpaperControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0)); wallpaperControls.setOpaque(false);
        backgroundChoice = new JComboBox<>(BACKGROUND_LABELS); backgroundChoice.setSelectedIndex(selectedBackgroundIndex);
        backgroundChoice.addActionListener(e -> { if (backgroundChoice.getSelectedIndex() >= 0) setSelectedBackgroundIndex(backgroundChoice.getSelectedIndex()); });
        wallpaperControls.add(backgroundChoice);
        wallpaperControls.add(btn("Apply to all tabs", () -> { if (backgroundChoice.getSelectedIndex() >= 0) setSelectedBackgroundIndex(backgroundChoice.getSelectedIndex()); }));
        appearance.add(wallpaperControls, a); a.gridy++;
        appearanceCard.add(appearance, BorderLayout.CENTER);
        page.add(appearanceCard); page.add(Box.createVerticalStrut(12));

        JPanel styleCard = glassCard(new BorderLayout(0, 8));
        styleCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        styleCard.add(smallHeading("INTERFACE STYLE (NOT GAME SETTINGS)"), BorderLayout.NORTH);
        JPanel styleButtons = paddedRow(12);
        styleButtons.add(btn("Visual Style / Home", () -> showVisualPage("Home")));
        styleButtons.add(btn("Terminal Style (original)", () -> showWorkspace(1,true)));
        styleCard.add(styleButtons,BorderLayout.CENTER);
        page.add(styleCard); page.add(Box.createVerticalStrut(12));

        JPanel extraCard = glassCard(new BorderLayout(0, 8));
        extraCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        extraCard.add(smallHeading("MORE OPTIONS"), BorderLayout.NORTH);
        JPanel buttons = paddedRow(12);
        buttons.add(btn("Create desktop shortcut", this::createDesktopShortcut));
        JButton advanced = btn("Advanced settings...", this::showAdvancedSettings);
        advanced.setToolTipText("Only needed for custom paths, Java installations or advanced launch options.");
        buttons.add(advanced);
        extraCard.add(buttons, BorderLayout.CENTER);
        JLabel description = new JLabel("You can usually leave advanced settings unchanged.");
        description.setForeground(MUTED); extraCard.add(description, BorderLayout.SOUTH);
        page.add(extraCard);

        JPanel wrapper = new JPanel(new BorderLayout()); wrapper.setOpaque(false);
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(null); styleScroll(scroll);
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }
    void addSettingsField(JPanel panel, GridBagConstraints c, String label, String key, String help) {
        c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        JLabel text = new JLabel(label); text.setToolTipText(help); panel.add(text, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JTextField field = new JTextField(34); field.setToolTipText(help);
        field.getDocument().addDocumentListener(simple(this::saveSettings));
        fields.put(key, field); panel.add(field, c); c.gridy++;
    }
    interface ColorDefault { Color get(); }
    void addColorPicker(JPanel panel, GridBagConstraints c, String label,
        JTextField hexField, String type, ColorDefault fallback, Runnable apply) {
        c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0)); controls.setOpaque(false);
        hexField.setToolTipText("Optional #RRGGBB, such as #202020. Empty = theme default.");
        controls.add(hexField);
        controls.add(btn("Choose...", () -> {
            Color starting = validHex(hexField.getText().trim()) ? Color.decode(hexField.getText().trim()) : fallback.get();
            Color chosen = JColorChooser.showDialog(frame, "Choose " + type.toLowerCase(Locale.ROOT) + " color", starting);
            if (chosen != null) { hexField.setText(hex(chosen)); apply.run(); }
        }));
        controls.add(btn("Apply", () -> {
            String value = hexField.getText().trim();
            if (!value.isEmpty() && !validHex(value)) {
                JOptionPane.showMessageDialog(frame, "Use a 6-digit hex color like #3D9EFF, or leave it blank.",
                    "Invalid color", JOptionPane.WARNING_MESSAGE);
                return;
            }
            apply.run();
        }));
        controls.add(btn("Reset", () -> { hexField.setText(""); apply.run(); }));
        panel.add(controls, c); c.gridy++;
    }
    void showAdvancedSettings() {
        saveSettings();
        Map<String, Object> inst = inst();
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 6, 5, 6); c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        String[][] extra = {
            {"loader_version", "Loader version (blank = latest)"},
            {"java_path", "Custom Java executable"},
            {"mods_dir", "Mods folder"},
            {"game_args", "Extra game arguments"},
            {"window_width", "Window width"}, {"window_height", "Window height"},
            {"env_vars", "Environment variables"},
            {"pre_launch_cmd", "Command before launch"},
            {"launch_cmd", "External launch command"}
        };
        Map<String, JTextField> advancedFields = new LinkedHashMap<>();
        for (String[] item : extra) {
            c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            panel.add(new JLabel(item[1]), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            JTextField field = new JTextField(s(inst.get(item[0])), 28);
            advancedFields.put(item[0], field); panel.add(field, c); c.gridy++;
        }
        c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; panel.add(new JLabel("Fullscreen"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        JCheckBox fullscreenChoice = new JCheckBox(); fullscreenChoice.setSelected(b(inst.get("fullscreen")));
        panel.add(fullscreenChoice, c); c.gridy++;
        String[][] global = {
            {"runtime_dir", "Minecraft runtime / download folder"},
            {"token", "GitHub access token (optional)"},
            {"modrinth_token", "Modrinth token (optional)"}
        };
        Map<String, JTextField> globalFields = new LinkedHashMap<>();
        for (String[] item : global) {
            c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            panel.add(new JLabel(item[1]), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            JTextField field = new JTextField(s(cfg.get(item[0])), 28);
            globalFields.put(item[0], field); panel.add(field, c); c.gridy++;
        }
        c.gridx = 0; c.fill = GridBagConstraints.NONE; panel.add(new JLabel("Installed version ID"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JTextField version = new JTextField(s(inst.get("version_id")), 28); version.setEditable(false);
        panel.add(version, c);
        JScrollPane scroll = new JScrollPane(panel); scroll.setPreferredSize(new Dimension(750, 480));
        int choice = JOptionPane.showConfirmDialog(frame, scroll, "Advanced settings — " + active(),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        for (var e : advancedFields.entrySet()) inst.put(e.getKey(), e.getValue().getText().trim());
        inst.put("fullscreen", fullscreenChoice.isSelected());
        for (var e : globalFields.entrySet()) cfg.put(e.getKey(), e.getValue().getText().trim());
        versionF.setText(s(inst.get("version_id")));
        runtimeF.setText(s(cfg.get("runtime_dir")));
        tokenF.setText(s(cfg.get("token")));
        mrTokenF.setText(s(cfg.get("modrinth_token")));
        fullscreen.setSelected(fullscreenChoice.isSelected());
        saveConfig(cfg);
        log("Advanced settings saved for " + active() + ".");
    }
    static DocumentListener simple(Runnable r) {
        return new DocumentListener() { public void insertUpdate(DocumentEvent e) { r.run(); } public void removeUpdate(DocumentEvent e) { r.run(); } public void changedUpdate(DocumentEvent e) { r.run(); } };
    }

    void saveSettings() {
        if (loading || instBox == null || fields.isEmpty() || runtimeF == null) return;
        Map<String, Object> i = inst();
        for (var e : fields.entrySet()) { String v = e.getValue().getText(); i.put(e.getKey(), Set.of("mods_dir", "launch_cmd", "game_args", "env_vars", "pre_launch_cmd").contains(e.getKey()) ? v : v.trim()); }
        i.put("loader", loaderBox.getSelectedItem()); i.put("fullscreen", fullscreen.isSelected());
        if (useGenericRam != null) i.put("memory_inherit", useGenericRam.isSelected());
        cfg.put("runtime_dir", runtimeF.getText().trim()); cfg.put("token", tokenF.getText().trim()); cfg.put("modrinth_token", mrTokenF.getText().trim());
        saveConfig(cfg); updateRamControls();
    }
    void selectInstance(String name) {
        cfg.put("active", name); loading = true;
        instBox.removeAllItems(); for (String k : insts().keySet()) instBox.addItem(k); instBox.setSelectedItem(name);
        Map<String, Object> i = inst();
        for (var e : fields.entrySet()) e.getValue().setText(s(i.get(e.getKey())));
        loaderBox.setSelectedItem(s(i.get("loader"))); fullscreen.setSelected(b(i.get("fullscreen")));
        if (useGenericRam != null) useGenericRam.setSelected(b(i.get("memory_inherit")));
        versionF.setText(s(i.get("version_id")));
        runtimeF.setText(s(cfg.get("runtime_dir"))); tokenF.setText(s(cfg.get("token"))); mrTokenF.setText(s(cfg.get("modrinth_token")));
        loading = false; saveConfig(cfg); refreshAll(); updateAuth(); updateRamControls();
        if (visualProfileLabel != null) visualProfileLabel.setText("Selected profile: " + active());
        updateProfileActions();
        if (visualInstanceBox != null) {
            boolean previousLoading=loading; loading=true;
            visualInstanceBox.removeAllItems();
            for (String k : insts().keySet()) visualInstanceBox.addItem(k);
            visualInstanceBox.setSelectedItem(active());
            loading=previousLoading;
        }
    }
    void refreshAll() { selectLoadFieldsOnly(); refreshList(); }
    void selectLoadFieldsOnly() {   // after a pack changed MC version / loader / version id
        boolean was = loading; loading = true; Map<String, Object> i = inst();
        for (var e : fields.entrySet()) e.getValue().setText(s(i.get(e.getKey())));
        loaderBox.setSelectedItem(s(i.get("loader"))); versionF.setText(s(i.get("version_id"))); loading = was;
    }
    void updateAuth() { Map<String, Object> a = m(cfg.get("account")); authLabel.setText("msa".equals(s(a.get("type"))) ? "Microsoft: " + a.get("name") : "Not logged in"); }

    void refreshList() {
        if (modModel == null) return;
        modModel.setRowCount(0); Map<String, Object> i = inst(); String q = filterField.getText().trim().toLowerCase();
        Set<String> trackedFiles = new HashSet<>(); int total = 0;
        for (var e : m(i.get("mods")).entrySet()) {
            Map<String, Object> mi = m(e.getValue()); trackedFiles.add(s(mi.get("file")));
            StringBuilder role = new StringBuilder();
            if (b(mi.get("auto"))) { role.append("dependency of "); for (Object r : l(mi.get("required_by"))) role.append(s(m(m(i.get("mods")).get(s(r))).get("name")).isEmpty() ? s(r) : s(m(m(i.get("mods")).get(s(r))).get("name"))).append(", "); }
            else role.append("added by you");
            addRow(q, new Object[]{s(mi.get("name")).isEmpty() ? e.getKey() : mi.get("name"), s(mi.get("tag")).isEmpty() ? "-" : mi.get("tag"), s(mi.get("file")), modSource(e.getKey()), role.toString().replaceAll(", $", "")}); total++;
        }
        Path dir = Paths.get(s(i.get("mods_dir")));
        if (Files.isDirectory(dir)) try (var st = Files.list(dir)) {
            for (Path p : (Iterable<Path>) st.sorted()::iterator) {
                String f = p.getFileName().toString(); if (!f.toLowerCase().endsWith(".jar") || trackedFiles.contains(f)) continue;
                List<Object> ids = l(readMeta(p).get("ids"));
                addRow(q, new Object[]{ids.isEmpty() ? f : String.join(", ", ids.stream().limit(2).map(SkyLauncher::s).toList()), "-", f, "-", "pack / manual"}); total++;
            }
        } catch (IOException ignored) { }
        Map<String, Object> inst = inst();
        packLabel.setText(!s(inst.get("pack_name")).isEmpty() || !s(inst.get("pack_repo")).isEmpty() ? "Modpack: " + (s(inst.get("pack_name")).isEmpty() ? inst.get("pack_repo") : inst.get("pack_name")) + "  " + s(inst.get("pack_version")) + "  (" + s(inst.get("pack_source")) + ")   -   " + total + " jar(s)" : "No modpack installed - " + total + " jar(s)");
    }
    void addRow(String q, Object[] row) { if (q.isEmpty() || String.join(" ", Arrays.stream(row).map(SkyLauncher::s).toList()).toLowerCase().contains(q)) modModel.addRow(row); }

    // ---- profiles
    void newInstance() {
        String name = JOptionPane.showInputDialog(frame, "Profile name:"); if (name == null || name.isBlank() || insts().containsKey(name) || name.matches(".*[\\\\/:*?\"<>|].*")) return;
        String mc = JOptionPane.showInputDialog(frame, "Minecraft version (e.g. 1.21.1):"); if (mc == null) return;
        Object loader = JOptionPane.showInputDialog(frame, "Loader:", "New profile", JOptionPane.PLAIN_MESSAGE, null, LOADERS, "neoforge"); if (loader == null) return;
        insts().put(name, defaultInstance(name, mc.trim(), (String) loader)); selectInstance(name);
    }
    void deleteInstance() {
        if (insts().size() < 2) { notifyUser("Keep at least one profile."); return; }
        String name = active(); if (JOptionPane.showConfirmDialog(frame, "Delete profile '" + name + "'?", "Delete", JOptionPane.YES_NO_OPTION) != 0) return;
        Map<String, Object> i = m(insts().remove(name)); Path gd = gameDir(i);
        if (gd.startsWith(BASE) && Files.isDirectory(gd) && JOptionPane.showConfirmDialog(frame, "Also delete " + gd + "?", "Delete files", JOptionPane.YES_NO_OPTION) == 0) { try { deleteTree(gd); } catch (IOException e) { log("Could not delete: " + e); } }
        selectInstance(insts().keySet().iterator().next());
    }
    void openFolder() {
        try { Files.createDirectories(gameDir(inst())); Desktop.getDesktop().open(gameDir(inst()).toFile()); }
        catch (Exception e) { JOptionPane.showMessageDialog(frame, "Couldn't open the folder automatically (" + e.getMessage() + ").\n\n" + gameDir(inst())); }
    }

    // Copy this profile to Mojang's launcher_profiles.json, as in Python's Sync action.
    // No network requests or unofficial launcher modifications are required.
    Path officialProfilesPath() {
        if (OS.equals("windows")) {
            String appdata = System.getenv("APPDATA");
            return Paths.get(appdata == null || appdata.isBlank() ? HOME.toString() : appdata)
                .resolve(".minecraft").resolve("launcher_profiles.json");
        }
        if (OS.equals("osx")) return HOME.resolve("Library/Application Support/minecraft/launcher_profiles.json");
        return HOME.resolve(".minecraft/launcher_profiles.json");
    }
    void syncOfficial() {
        saveSettings();
        try {
            Path config = officialProfilesPath();
            if (!Files.isRegularFile(config)) {
                JOptionPane.showMessageDialog(frame, "Could not find the official Minecraft launcher profile file:\n" + config
                    + "\n\nRun the official Minecraft Launcher once before syncing.", "Official launcher not found", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Map<String, Object> instance = inst();
            String vid = s(instance.get("version_id"));
            if (vid.isBlank()) {
                JOptionPane.showMessageDialog(frame, "Press Install / prepare first so the Minecraft loader is installed.",
                    "Not installed", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Path officialRoot = config.getParent();
            Path installedVersion = officialRoot.resolve("versions").resolve(vid).resolve(vid+".json");
            if (!Files.isRegularFile(installedVersion)) {
                JOptionPane.showMessageDialog(frame, "This version isn't installed in the official Minecraft directory:\n" + installedVersion
                    + "\n\nSet the Runtime dir in Settings to the official .minecraft folder, then use Install / prepare.",
                    "Loader missing", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int answer = JOptionPane.showConfirmDialog(frame,
                "Close the official Minecraft Launcher before syncing, or it may overwrite the profile.\n\n"
                    + "Create a backup and sync '" + active() + "'?",
                "Add to Minecraft Launcher", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            Map<String, Object> data = m(Json.parse(Files.readString(config)));
            Map<String, Object> profiles = m(data.get("profiles")); data.put("profiles",profiles);
            String key = "ghml-" + active().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            Map<String, Object> profile = new LinkedHashMap<>(m(profiles.get(key)));
            String now = java.time.Instant.now().toString(); profile.putIfAbsent("created",now);
            profile.put("name", "SkyLauncher: " + active()); profile.put("type","custom");
            profile.put("lastUsed",now); profile.put("lastVersionId",vid);
            profile.put("gameDir",gameDir(instance).toString());
            profile.put("javaArgs", "-Xmx"+effectiveMemoryMb(cfg, instance)+"M " + effectiveJvmArgs(cfg, instance));
            if (!s(instance.get("java_path")).isBlank()) profile.put("javaDir",s(instance.get("java_path")));
            else profile.remove("javaDir");
            String w=s(instance.get("window_width")), h=s(instance.get("window_height"));
            if (w.matches("\\d+") && h.matches("\\d+"))
                profile.put("resolution",Map.of("width",Integer.parseInt(w),"height",Integer.parseInt(h)));
            else profile.remove("resolution");
            profiles.put(key,profile);
            Path backup=config.resolveSibling(config.getFileName()+".ghml.bak");
            Files.copy(config,backup,StandardCopyOption.REPLACE_EXISTING);
            Path tmp=Files.createTempFile(config.getParent(),"skylauncher-profile-",".tmp");
            try {
                Files.writeString(tmp, Json.write(data), StandardCharsets.UTF_8);
                Files.move(tmp,config,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp,config,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(tmp); }
            log("Official launcher profile 'SkyLauncher: " + active() + "' synced; backup: " + backup);
            JOptionPane.showMessageDialog(frame, "Synced. Open the official Minecraft Launcher and select 'SkyLauncher: "
                + active() + "'.", "Done", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) { log("Official launcher sync error: " + e); JOptionPane.showMessageDialog(frame,
            "Could not sync: " + e.getMessage(), "Sync failed", JOptionPane.ERROR_MESSAGE); }
    }

    // ---- login
    void login() {
        if (busy.get()) { log("Busy - wait for the current job."); return; }
        setBusy(true);
        Thread t = new Thread(() -> {
            try {
                String cid = clientId(cfg); log("Starting Microsoft device-code login...");
                Map<String, Object> dc = m(postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", new LinkedHashMap<>(Map.of("client_id", cid, "scope", MSA_SCOPE))));
                String uri = dc.get("verification_uri") == null ? "https://www.microsoft.com/link" : s(dc.get("verification_uri")), code = s(dc.get("user_code"));
                log("Open " + uri + " and enter code: " + code);
                SwingUtilities.invokeLater(() -> {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
                    JOptionPane op = new JOptionPane("<html>1. Open <b>" + uri + "</b> (the Open button does this)<br>2. Enter this code (already copied):<br><br><font size=+3><b>" + code
                            + "</b></font><br><br>3. Sign in with the account that owns Minecraft.<br>This window can stay open; login continues automatically.</html>",
                            JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{"Open page", "Close"});
                    JDialog d = op.createDialog(frame, "Microsoft login"); d.setModal(false); d.setVisible(true);
                    op.addPropertyChangeListener(ev -> { if ("value".equals(ev.getPropertyName())) { if ("Open page".equals(op.getValue())) { try { Desktop.getDesktop().browse(URI.create(uri)); } catch (Exception ignored) { } op.setValue(JOptionPane.UNINITIALIZED_VALUE); } else d.dispose(); } });
                });
                long deadline = System.currentTimeMillis() + Math.max(30, n(dc.get("expires_in"), 900) - 5) * 1000; long interval = Math.max(3, n(dc.get("interval"), 5));
                Map<String, Object> tok = null;
                while (System.currentTimeMillis() < deadline && tok == null) {
                    Thread.sleep(interval * 1000);
                    try { tok = m(postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", new LinkedHashMap<>(Map.of(
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", cid, "device_code", s(dc.get("device_code")))))); }
                    catch (HttpErr e) {
                        String err = s(m(Json.parse(e.body)).get("error"));
                        if (err.equals("slow_down")) interval += 2; else if (!err.equals("authorization_pending")) throw new IOException("MSA token error: " + e.body);
                    }
                }
                if (tok == null) throw new IOException("Timed out waiting for Microsoft login");
                Map<String, Object> acc = buildAccount(tok, cid, this::log); cfg.put("account", acc); saveConfig(cfg);
                log("Logged in as " + acc.get("name") + " (" + acc.get("uuid") + ")");
                SwingUtilities.invokeLater(this::updateAuth);
            } catch (Exception e) { log("Microsoft login failed: " + e); } finally { setBusy(false); }
        }); t.setDaemon(true); t.start();
    }

    // ---- mods
    void chooseLocalMods() {
        if (busy.get() || game != null) {
            notifyUser("Close Minecraft and finish any active download before importing mods.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import local mod JARs or ZIP archives");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Minecraft mods (*.jar, *.zip)", "jar", "zip"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        List<Path> selected = Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList();
        if (selected.isEmpty()) return;
        int choice = JOptionPane.showConfirmDialog(frame,
                "Import mods into profile '" + active() + "'?\n\n"
                + "ZIPs: only contained JAR mods will be imported.\n"
                + "Existing mods downloaded by SkyLauncher will not be replaced.\n\n"
                + "Replace other existing JARs when filenames match?\n"
                + "(No = skip duplicates; Cancel = don't import)",
                "Import local mods", JOptionPane.YES_NO_CANCEL_OPTION);
        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return;
        Map<String, Object> target = inst();
        async("Import local mods", () -> {
            LocalImportResult result = importLocalMods(selected, target, choice == JOptionPane.YES_OPTION, this::log);
            log(result.summary());
            notifyUser(result.summary());
        });
    }
    String modLabel(Map<String, Object> inst, String key) { String nm = s(m(m(inst.get("mods")).get(key)).get("name")); return nm.isEmpty() ? key : nm; }
    void addMod() {
        String text = addField.getText().trim();
        if (text.isEmpty()) return;
        String ref;
        Matcher mm = Pattern.compile("(?i)(?:https?://)?(?:www\\.)?modrinth\\.com/(?:mod|plugin|datapack)/([^/?#\\s]+).*").matcher(text);
        Matcher cfLink = Pattern.compile("(?i)(?:https?://)?(?:www\\.)?curseforge\\.com/minecraft/mc-mods/([a-z0-9_-]+)(?:[/?#].*)?").matcher(text);
        if (text.matches("https?://\\S+\\.jar(?:\\?\\S*)?")) ref = "url:" + text;
        else if (mm.matches()) ref = "modrinth:" + mm.group(1);
        else if (text.toLowerCase(Locale.ROOT).matches("modrinth:[^\\s]+")) ref = "modrinth:" + text.substring(9);
        else if (cfLink.matches()) ref = "curseforge-slug:" + cfLink.group(1);
        else if (text.toLowerCase(Locale.ROOT).matches("curseforge:[1-9][0-9]*")) ref = "curseforge:" + text.substring(11);
        else if (text.toLowerCase(Locale.ROOT).contains("curseforge")) {
            JOptionPane.showMessageDialog(frame,"Use a CurseForge Minecraft mod link or curseforge:PROJECT_ID."); return;
        } else {
            String repo = text.replaceAll("^https?://github\\.com/", "").replaceAll("^/+|/+$", "").replaceAll("\\.git$", "");
            String[] pp = repo.split("/");
            if (pp.length < 2 || !(pp[0] + "/" + pp[1]).matches("[\\w.-]+/[\\w.-]+")) {
                JOptionPane.showMessageDialog(frame,"Use a GitHub project, Modrinth link, CurseForge mod link/ID or direct JAR URL."); return;
            }
            ref = pp[0] + "/" + pp[1];
        }
        addField.setText(""); final String fref = ref;
        async("Add mod", () -> {
            Map<String, Object> i = inst(); String k = fref, label = fref;
            if (fref.startsWith("modrinth:")) {
                Map<String, Object> p = m(mr(cfg, "/project/" + enc(fref.substring(9))));
                k = "modrinth:" + p.get("id"); label = s(p.get("title"));
            } else if (fref.startsWith("curseforge-slug:")) {
                Map<String,Object> p = m(cf(cfg,"/v1/resolve?slug=" + enc(fref.substring("curseforge-slug:".length()))));
                k = "curseforge:" + p.get("id"); label = s(p.get("name"));
            } else if (fref.startsWith("curseforge:")) {
                Map<String,Object> p = m(cf(cfg,"/v1/mods/" + fref.substring(11)));
                label = s(p.get("name"));
            } else if (fref.startsWith("url:")) {
                String u = fref.substring(4); k = "url:" + digest("SHA-1",u.getBytes(StandardCharsets.UTF_8)).substring(0,12);
                installUrl(k,u,i); log("Done."); return;
            }
            log("[" + label + "] checking source...");
            installMod(k, label, i, false, null, new HashSet<>(Set.of(k)), null, null);
            log("Done.");
        });
    }
    void search() {
        String q = searchField.getText().trim(); if (q.isEmpty()) return;
        Map<String,Object> current = inst();
        boolean useCurseForge = searchProviderBox != null && "CurseForge (VPS)".equals(searchProviderBox.getSelectedItem());
        boolean compatible = onlyCompatibleSearch != null && onlyCompatibleSearch.isSelected();
        async("Search mods", () -> {
            List<Map<String, Object>> hits = new ArrayList<>();
            if (useCurseForge) {
                String url="/v1/search?q=" + enc(q) + "&version=" + enc(compatible ? s(current.get("mc_version")) : "") +
                    "&loader=" + enc(compatible ? s(current.get("loader")) : "");
                for(Object o:l(m(cf(cfg,url)).get("data"))) {
                    Map<String,Object> raw=m(o), hit=new LinkedHashMap<>();
                    hit.put("title",raw.get("name"));hit.put("project_id",raw.get("id"));
                    hit.put("downloads",raw.get("downloads"));hit.put("description",raw.get("summary"));
                    hit.put("provider","curseforge");hits.add(hit);
                }
            } else {
                List<Object> facets = new ArrayList<>(); facets.add(List.of("project_type:mod"));
                if (compatible && !s(current.get("loader")).isEmpty()) facets.add(List.of("categories:" + current.get("loader")));
                if (compatible && !s(current.get("mc_version")).trim().isEmpty()) facets.add(List.of("versions:" + s(current.get("mc_version")).trim()));
                Map<String,Object> result=m(mr(cfg,"/search?query="+enc(q)+"&limit=25&facets="+enc(Json.write(facets).replaceAll("\s+",""))));
                for(Object o:l(result.get("hits"))) {
                    Map<String,Object> h = new LinkedHashMap<>(m(o));h.put("provider","modrinth");hits.add(h);
                }
            }
            SwingUtilities.invokeLater(() -> {
                searchModel.setRowCount(0); searchHits.clear(); searchHits.addAll(hits);
                for (var h : hits) {
                    long dl = n(h.get("downloads"), 0);
                    searchModel.addRow(new Object[]{h.get("title"), dl >= 1_000_000 ? String.format("%.1fM",dl / 1e6) : dl >= 1000 ? dl / 1000 + "K" : dl,
                        s(h.get("description"))});
                }
            });
            log((useCurseForge ? "CurseForge" : "Modrinth") + " search: " + hits.size() + " result(s)");
        });
    }
    void installSearched() {
        int row = searchTable.getSelectedRow();
        if (row < 0) { notifyUser("Select a search result first."); return; }
        int modelRow = searchTable.convertRowIndexToModel(row);
        if (modelRow < 0 || modelRow >= searchHits.size()) return;
        Map<String,Object> h=searchHits.get(modelRow);
        String provider=s(h.get("provider"));
        String key=(provider.equals("curseforge") ? "curseforge:" : "modrinth:") + h.get("project_id");
        Map<String,Object> i=inst();
        async("Install", () -> {
            installMod(key,s(h.get("title")),i,false,null,new HashSet<>(Set.of(key)),null,null);
            log("Done.");
        });
    }
    List<String> selectedKeys() {
        List<String> keys = new ArrayList<>(); Map<String, Object> mods = m(inst().get("mods"));
        for (int r : modTable.getSelectedRows()) { String file = s(modModel.getValueAt(modTable.convertRowIndexToModel(r), 2));
            for (var e : mods.entrySet()) if (s(m(e.getValue()).get("file")).equals(file)) keys.add(e.getKey()); }
        return keys;
    }
    void updateMods(boolean selectedOnly) {
        Map<String, Object> i = inst(); List<String> keys = selectedOnly ? selectedKeys() : new ArrayList<>(m(i.get("mods")).keySet());
        if (keys.isEmpty()) { log("Nothing to update."); return; }
        async("Update", () -> {
            Set<String> seen = new HashSet<>(keys);
            for (String k : keys) { try { installMod(k, modLabel(i, k), i, b(m(m(i.get("mods")).get(k)).get("auto")), null, seen, null, null); } catch (Exception e) { log("[" + k + "] ERROR: " + e); } }
            log("Done.");
        });
    }
    void installUrl(String key, String url, Map<String, Object> inst) throws Exception {
        Path dir = Paths.get(s(inst.get("mods_dir"))); Files.createDirectories(dir);
        String fname = Paths.get(URI.create(url).getPath()).getFileName().toString(); if (!fname.toLowerCase().endsWith(".jar")) fname += ".jar";
        log("[" + fname + "] downloading from direct URL..."); byte[] data = bytes(url, null); Path tmp = dir.resolve(fname + ".part"); Files.write(tmp, data);
        Map<String, Object> meta = readMeta(tmp);
        if (!l(meta.get("loaders")).isEmpty() && !l(meta.get("loaders")).contains(s(inst.get("loader"))) && !(s(inst.get("loader")).equals("quilt") && l(meta.get("loaders")).contains("fabric"))) { Files.delete(tmp); log("[" + fname + "] is a " + meta.get("loaders") + " jar, profile is " + inst.get("loader") + " - rejected"); return; }
        Files.move(tmp, dir.resolve(fname), StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> info = m(m(inst.get("mods")).computeIfAbsent(key, k -> new LinkedHashMap<String, Object>()));
        info.put("name", fname); info.put("tag", "direct"); info.put("version_id", ""); info.put("file", fname); info.put("sha256", digest("SHA-256", data)); info.put("url", url);
        info.put("ids", meta.get("ids")); info.put("loaders", meta.get("loaders")); info.put("auto", false); saveConfig(cfg); log("[" + fname + "] installed " + fname);
    }

    /** GitHub or Modrinth install of one mod (+ dependencies). vid pins a version (tag / Modrinth version id). */
    void installMod(String key, String label, Map<String, Object> inst, boolean auto, String parent, Set<String> seen, String vid, String expectId) throws Exception {
        String src = modSource(key), loader = s(inst.get("loader")); Path dir = Paths.get(s(inst.get("mods_dir")));
        if (src.equals("url")) { installUrl(key, s(m(m(inst.get("mods")).get(key)).get("url")), inst); return; }
        log("[" + label + "] checking " + src + " (" + loader + " " + (s(inst.get("mc_version")).isEmpty() ? "any MC" : inst.get("mc_version")) + ")...");
        List<Map<String, Object>> versions = listVersions(key, inst, cfg); Map<String, Object> v = null;
        if (vid != null) { for (var x : versions) if (s(x.get("id")).equals(vid)) v = x; } else v = pickLatest(versions);
        if (v == null) { log("[" + label + "] no version with a jar for " + loader + " " + inst.get("mc_version")); return; }
        Files.createDirectories(dir);
        // resolve file + expected hash
        String url, fname, sha1 = null, sha512 = null, sha256 = null, tag = s(v.get("label")), versionId = s(v.get("id")); Map<String, String> dlHeaders = null;
        if (src.equals("modrinth") || src.equals("curseforge")) {
            Map<String, Object> f = m(l(v.get("files")).get(0)); url = s(f.get("url")); fname = Paths.get(s(f.get("name"))).getFileName().toString();
            sha1 = f.get("sha1") == null || s(f.get("sha1")).isBlank() ? null : s(f.get("sha1"));
            sha512 = f.get("sha512") == null || s(f.get("sha512")).isBlank() ? null : s(f.get("sha512"));
            if (src.equals("curseforge")) dlHeaders = cfHeaders(cfg);
        } else {
            Map<String, Object> rel = m(v.get("rel")); Map<String, Object> a = ghCandidates(rel, inst).get(0);
            url = s(a.get("url")); fname = Paths.get(s(a.get("name"))).getFileName().toString(); dlHeaders = ghHeaders(cfg, "application/octet-stream");
            if (s(a.get("digest")).startsWith("sha256:")) sha256 = s(a.get("digest")).substring(7).toLowerCase();
        }
        Map<String, Object> old = m(m(inst.get("mods")).get(key)); Path dest = dir.resolve(fname); String sha; Map<String, Object> meta;
        if (s(old.get("file")).equals(fname) && Files.exists(dest) && fileDigest("SHA-256", dest).equals(s(old.get("sha256"))) && (src.equals("github") ? tag.equals(s(old.get("tag"))) : versionId.equals(s(old.get("version_id"))))) {
            log("[" + label + "] up to date (" + tag + ")"); sha = s(old.get("sha256")); meta = readMeta(dest);
        } else {
            log("[" + label + "] downloading " + fname + " (" + tag + ")");
            byte[] data = bytes(url, dlHeaders); sha = digest("SHA-256", data);
            if (sha1 != null && !digest("SHA-1", data).equalsIgnoreCase(sha1)) { log("[" + label + "] HASH MISMATCH (sha1) - rejected"); return; }
            if (sha1 == null && sha512 != null && !digest("SHA-512", data).equalsIgnoreCase(sha512)) { log("[" + label + "] HASH MISMATCH (sha512) - rejected"); return; }
            if (sha256 != null && !sha256.equals(sha)) { log("[" + label + "] HASH MISMATCH (sha256) - rejected"); return; }
            log(sha1 != null || sha512 != null || sha256 != null ? "[" + label + "] hash verified" : "[" + label + "] no published hash; recorded sha256 " + sha.substring(0, 12) + ".. (trust on first use)");
            Path tmp = dir.resolve(fname + ".part"); Files.write(tmp, data); meta = readMeta(tmp);
            List<Object> ml = l(meta.get("loaders"));
            if (!ml.isEmpty() && !ml.contains(loader) && !(loader.equals("quilt") && ml.contains("fabric"))) { log("[" + label + "] " + fname + " is a " + ml + " jar, profile is " + loader + " - rejected"); Files.delete(tmp); return; }
            if (expectId != null && !l(meta.get("ids")).isEmpty() && !l(meta.get("ids")).contains(expectId)) { log("[" + label + "] doesn't provide mod id '" + expectId + "' (wrong project) - rejected"); Files.delete(tmp); return; }
            if (auto && !m(inst.get("mods")).containsKey(key) && !l(meta.get("ids")).isEmpty() && installedIds(inst).containsAll(l(meta.get("ids")).stream().map(SkyLauncher::s).toList())) { log("[" + label + "] already installed (provided by another jar) - skipped"); Files.delete(tmp); return; }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            if (!s(old.get("file")).isEmpty() && !s(old.get("file")).equals(fname)) Files.deleteIfExists(dir.resolve(s(old.get("file"))));
            log("[" + label + "] installed " + fname);
        }
        Map<String, Object> info = m(m(inst.get("mods")).computeIfAbsent(key, k -> new LinkedHashMap<String, Object>()));
        info.put("name", label); info.put("tag", tag); info.put("file", fname); info.put("sha256", sha); info.put("ids", meta.get("ids")); info.put("loaders", meta.get("loaders"));
        if (src.equals("modrinth") || src.equals("curseforge")) info.put("version_id", versionId);
        if (!auto) info.put("auto", false); else info.putIfAbsent("auto", true);
        if (parent != null) { List<Object> rb = l(info.get("required_by")); if (!rb.contains(parent)) rb.add(parent); info.put("required_by", rb); }
        saveConfig(cfg);
        // Install required dependencies advertised by CurseForge or Modrinth.
        for (Object d : l(v.get("deps"))) {
            boolean cfDep = src.equals("curseforge");
            String k2 = cfDep ? s(d) : "modrinth:" + d;
            if (k2.equals(key) || !seen.add(k2)) continue;
            Map<String, Object> have = m(m(inst.get("mods")).get(k2));
            if (!have.isEmpty() && Files.exists(dir.resolve(s(have.get("file"))))) continue;
            String nm;
            if (cfDep) nm = s(m(cf(cfg,"/v1/mods/"+enc(k2.substring(11)))).get("name"));
            else nm = s(m(mr(cfg,"/project/"+enc(s(d)))).get("title"));
            log("[" + label + "] requires " + nm);
            try { installMod(k2,nm,inst,true,key,seen,null,null); }
            catch (Exception e) { log("[" + nm + "] dependency install failed: " + e); }
        }
        // dependencies only visible inside the jar -> look them up on Modrinth by mod id
        Set<String> have = installedIds(inst);
        for (Object d0 : l(meta.get("deps"))) {
            String d = s(d0); if (d.isEmpty() || SKIP_DEPS.contains(d) || have.contains(d)) continue;
            try {
                Map<String, Object> p; try { p = m(mr(cfg, "/project/" + enc(d))); } catch (HttpErr e) { p = null; }
                if (p == null || !"mod".equals(s(p.get("project_type")))) { log("[" + label + "] requires '" + d + "' - couldn't find it on Modrinth; add it yourself"); continue; }
                String k2 = "modrinth:" + p.get("id"); if (!seen.add(k2)) continue;
                log("[" + label + "] requires '" + d + "' -> Modrinth " + p.get("title")); installMod(k2, s(p.get("title")), inst, true, key, seen, null, d);
            } catch (Exception e) { log("[" + label + "] dependency '" + d + "' failed: " + e); }
            have = installedIds(inst);
        }
    }
    void removeSelected() {
        int[] rows = modTable.getSelectedRows(); if (rows.length == 0 || game != null) return;
        Map<String, Object> i = inst(), mods = m(i.get("mods")); Path dir = Paths.get(s(i.get("mods_dir")));
        List<String> files = new ArrayList<>(); for (int r : rows) files.add(s(modModel.getValueAt(modTable.convertRowIndexToModel(r), 2)));
        if (JOptionPane.showConfirmDialog(frame, "Delete " + files.size() + " jar(s)?\n\n" + String.join("\n", files.subList(0, Math.min(10, files.size()))), "Remove", JOptionPane.YES_NO_OPTION) != 0) return;
        for (String f : files) {
            mods.entrySet().removeIf(e -> s(m(e.getValue()).get("file")).equals(f));
            try { Files.deleteIfExists(dir.resolve(f)); log("Removed " + f); } catch (IOException e) { log("Could not remove " + f + ": " + e); }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var e : new ArrayList<>(mods.entrySet())) {
                Map<String, Object> mi = m(e.getValue());
                if (!b(mi.get("auto"))) continue;
                mi.put("required_by", new ArrayList<>(l(mi.get("required_by")).stream().filter(x -> mods.containsKey(s(x))).toList()));
                if (l(mi.get("required_by")).isEmpty() && JOptionPane.showConfirmDialog(frame, s(mi.get("name")) + " was only needed by removed mods. Remove it too?", "Orphaned dependency", JOptionPane.YES_NO_OPTION) == 0) {
                    try { Files.deleteIfExists(dir.resolve(s(mi.get("file")))); } catch (IOException ignored) { }
                    mods.remove(e.getKey()); changed = true;
                }
            }
        }
        saveConfig(cfg); refreshList();
    }
    void verify() {
        Map<String, Object> i = inst(); int bad = 0; Path dir = Paths.get(s(i.get("mods_dir")));
        for (var e : m(i.get("mods")).entrySet()) {
            Map<String, Object> mi = m(e.getValue()); Path p = dir.resolve(s(mi.get("file")));
            try {
                if (!Files.exists(p)) { log("[" + e.getKey() + "] MISSING " + mi.get("file")); bad++; }
                else if (!fileDigest("SHA-256", p).equals(s(mi.get("sha256")))) { log("[" + e.getKey() + "] HASH MISMATCH " + mi.get("file")); bad++; }
                else log("[" + e.getKey() + "] OK");
            } catch (IOException ex) { log("[" + e.getKey() + "] " + ex); bad++; }
        }
        log("Verify finished: " + (bad == 0 ? "all good." : bad + " problem(s)."));
    }

    // ---- packs
    void installFathom() {
        String name = "Fathom Studios";
        if (!insts().containsKey(name)) { insts().put(name, defaultInstance(name, "1.21.1", "neoforge")); log("Created profile '" + name + "'"); }
        selectInstance(name); packJob(() -> installGithubPack(cfg, "Newguygame/fathom-studios-mc-pack", "main", inst(), true, this::log), false);
    }
    void installPackDialog() {
        String text = JOptionPane.showInputDialog(frame,
            "<html>Install a modpack from:<br>CurseForge: https://www.curseforge.com/minecraft/modpacks/name<br>Modrinth: https://modrinth.com/modpack/name<br>GitHub: owner/repo</html>");
        if (text == null || text.isBlank()) return;
        installPackFromText(text.trim());
    }
    void installPackFromText(String text) {
        Matcher mr = Pattern.compile("(?i)(?:https?://)?(?:www\\.)?modrinth\\.com/modpack/([^/?#\\s]+)(?:/version/([^/?#\\s]+))?.*").matcher(text);
        Matcher cf = Pattern.compile("(?i)(?:https?://)?(?:www\\.)?curseforge\\.com/minecraft/modpacks/([a-z0-9_-]+)(?:[/?#].*)?").matcher(text);
        boolean isMr = mr.matches(), isCf = cf.matches() || text.matches("(?i)curseforge-pack:[1-9][0-9]*");
        String slug = isMr ? mr.group(1) : cf.matches() ? cf.group(1) : "";
        String ver = isMr && mr.group(2) != null ? mr.group(2) : "";
        String repo = text.replaceFirst("(?i)^https?://github\\.com/", "").replaceAll("^/+|/+$", "").replaceAll("\\.git$", "");
        boolean isGh = !isMr && !isCf && repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/.*)?");
        if (!isMr && !isCf && !isGh) {
            JOptionPane.showMessageDialog(frame,"Use a CurseForge modpacks link, a Modrinth modpack link or a GitHub owner/repo.");return;
        }
        String cfId = isCf && slug.isEmpty() ? text.substring(text.indexOf(':')+1) : "";
        String ref = "main";
        if (isGh) {
            repo = String.join("/",Arrays.copyOf(repo.split("/"),2));
            String r = JOptionPane.showInputDialog(frame,"Branch or tag:","main");
            if (r == null) return;
            ref = r.trim().isEmpty() ? "main" : r.trim();
        }
        String pn = isGh ? repo.split("/")[1] : !slug.isEmpty() ? slug : "CurseForge-"+cfId;
        if (JOptionPane.showConfirmDialog(frame,
                "Install into a NEW profile?\n(No = replace the active profile's modpack)",
                "New profile?",JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            String base = pn; for (int n=2;insts().containsKey(pn);n++) pn=base+"-"+n;
            Map<String,Object> ni = defaultInstance(pn, s(inst().get("mc_version")).isEmpty()?"1.21.1":s(inst().get("mc_version")),
                s(inst().get("loader")).isEmpty()?"neoforge":s(inst().get("loader")));
            if (isGh) {
                String mc=JOptionPane.showInputDialog(frame,"Minecraft version:",ni.get("mc_version")); if(mc==null)return;
                ni.put("mc_version",mc.trim());
                Object ld=JOptionPane.showInputDialog(frame,"Loader:","Loader",JOptionPane.PLAIN_MESSAGE,null,LOADERS,ni.get("loader"));
                if(ld==null)return;ni.put("loader",ld);
            }
            insts().put(pn,ni);selectInstance(pn);
        }
        Map<String,Object> target = inst();
        String repoFinal=repo,refFinal=ref,slugFinal=slug,versionFinal=ver,cfIdFinal=cfId;
        packJob(()->{
            if (isMr) installModrinthPack(cfg,slugFinal,versionFinal,target,true,this::log);
            else if (isCf) {
                String id=cfIdFinal;
                if (id.isBlank()) id=s(m(cf(cfg,"/v1/packs/resolve?slug="+enc(slugFinal))).get("id"));
                installCurseForgePack(cfg,id,target,true,this::log);
            } else installGithubPack(cfg,repoFinal,refFinal,target,true,this::log);
        },false);
    }
    void searchModpacks() {
        Object provider=JOptionPane.showInputDialog(frame,"Search modpacks on:","Find modpacks",
            JOptionPane.QUESTION_MESSAGE,null,new String[]{"Modrinth","CurseForge (VPS)"},"Modrinth");
        if(provider==null)return;
        String query=JOptionPane.showInputDialog(frame,"Search modpack name:");
        if(query==null || query.trim().length()<2)return;
        String mc = s(inst().get("mc_version")), loader = s(inst().get("loader"));
        boolean compatible = !Boolean.FALSE.equals(cfg.get("compatible_mod_filter"));
        Thread thread = new Thread(()->{
          try {
            List<Object> results=new ArrayList<>();
            boolean useCf=provider.toString().startsWith("CurseForge");
            if(useCf) results.addAll(l(m(cf(cfg,"/v1/packs/search?q="+enc(query.trim())+
                "&version="+enc(compatible?mc:"")+"&loader="+enc(compatible?loader:""))).get("data")));
            else {
                List<Object> facets=new ArrayList<>(); facets.add(List.of("project_type:modpack"));
                if(compatible && !mc.isBlank()) facets.add(List.of("versions:"+mc));
                if(compatible && !loader.isBlank()) facets.add(List.of("categories:"+loader));
                results.addAll(l(m(mr(cfg,"/search?query="+enc(query.trim())+"&limit=20&facets="+
                    enc(Json.write(facets).replaceAll("\\s+","")))).get("hits")));
            }
            if(results.isEmpty()) { SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(frame,"No modpacks found."));return; }
            String[] choices=new String[results.size()];
            for(int n=0;n<results.size();n++) {
                Map<String,Object> item=m(results.get(n));
                choices[n]=s(useCf?item.get("name"):item.get("title"))+"  ("+s(item.get("slug"))+")";
            }
            SwingUtilities.invokeLater(()->{
                Object selected=JOptionPane.showInputDialog(frame,"Select a modpack:","Search modpacks",
                    JOptionPane.QUESTION_MESSAGE,null,choices,choices[0]);
                if(selected!=null) for(int n=0;n<choices.length;n++) if(choices[n].equals(selected)) {
                    String id=s(m(results.get(n)).get(useCf?"id":"slug"));
                    installPackFromText(useCf?"curseforge-pack:"+id:"https://modrinth.com/modpack/"+id);break;
                }
            });
          } catch(Exception ex) {
            log("Modpack search error: " + ex.getMessage());
            SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(frame,"Modpack search failed: " + ex.getMessage()));
          }
        }, "SkyLauncher-search-modpacks");
        thread.setDaemon(true); thread.start();
    }
    void packJob(ThrowingRun r, boolean restore) {
        Map<String, Object> i = inst();
        Map<String, Object> tracked = restore ? new LinkedHashMap<>(m(i.get("mods"))) : null;
        async("Pack install", () -> {
            r.run(); saveConfig(cfg);
            if (tracked != null && !tracked.isEmpty()) {
                log("Restoring mods you added..."); Set<String> have = installedIds(i); i.put("mods", new LinkedHashMap<String, Object>());
                for (var e : tracked.entrySet()) {
                    Map<String, Object> mi = m(e.getValue()); List<Object> ids = l(mi.get("ids"));
                    if (!ids.isEmpty() && have.containsAll(ids.stream().map(SkyLauncher::s).toList())) { log("[" + s(mi.get("name")) + "] the pack already includes this mod; dropping your copy"); continue; }
                    try { installMod(e.getKey(), s(mi.get("name")), i, b(mi.get("auto")), null, new HashSet<>(Set.of(e.getKey())), null, null); } catch (Exception ex) { log("[" + e.getKey() + "] restore failed: " + ex); }
                }
            }
            log("Profile '" + active() + "' is ready. Press 'Install / prepare' then PLAY.");
        });
    }
    String packSource(Map<String,Object> i) {
        String src=s(i.get("pack_source"));
        if(src.isBlank() && "Fathom Studios".equals(s(i.get("name"))) && !s(i.get("pack_sha")).isBlank()) return "github";
        return src;
    }
    static String modrinthPackSlug(Map<String,Object> i) {
        String id=s(i.get("pack_id"));
        if(!id.isBlank())return id;
        String url=s(i.get("pack_url"));return url.replaceAll(".*/modpack/","").replaceAll("[/?#].*$","");
    }
    // All sources report a stable version identifier, not only a display version.
    Map<String,Object> newestPack(Map<String,Object> i) throws Exception {
        String src=s(i.get("pack_source"));
        if(src.equals("github")) {
            String repo=s(i.get("pack_repo"));
            if(repo.isBlank() && "Fathom Studios".equals(s(i.get("name")))) repo="Newguygame/fathom-studios-mc-pack";
            String ref=s(i.get("pack_ref")).isBlank()?"main":s(i.get("pack_ref"));
            if(repo.isBlank())throw new IOException("GitHub repository unknown");
            String sha=s(m(getJson("https://api.github.com/repos/"+repo+"/commits/"+enc(ref).replace("%2F","/"),ghHeaders(cfg,null))).get("sha"));
            return Map.of("id",sha,"label",sha.length()>7?sha.substring(0,7):sha);
        }
        if(src.equals("modrinth")) {
            String id=modrinthPackSlug(i);
            if(id.isBlank())throw new IOException("Modrinth pack ID missing");
            Map<String,Object> ver=latestModrinthPack(cfg,id);
            return Map.of("id",s(ver.get("id")),"label",s(ver.get("version_number")));
        }
        if(src.equals("curseforge")) {
            String id=s(i.get("pack_id"));
            if(id.isBlank())throw new IOException("CurseForge pack ID missing");
            Map<String,Object> ver=latestCurseForgePack(cfg,id);
            return Map.of("id",s(ver.get("id")),"label",s(ver.get("name")));
        }
        throw new IOException("Unknown pack source: "+src);
    }
    String installedPackId(Map<String,Object> i) {
        return switch(s(i.get("pack_source"))) {
            case "github" -> s(i.get("pack_sha"));
            case "modrinth" -> s(i.get("pack_version_id"));
            case "curseforge" -> s(i.get("pack_file_id"));
            default -> "";
        };
    }
    void installNewestPack(Map<String,Object> i, Log logger) throws Exception {
        String src=s(i.get("pack_source"));
        if(src.equals("github")) {
            String repo=s(i.get("pack_repo"));
            if(repo.isBlank() && "Fathom Studios".equals(s(i.get("name")))) repo="Newguygame/fathom-studios-mc-pack";
            installGithubPack(cfg,repo,s(i.get("pack_ref")).isBlank()?"main":s(i.get("pack_ref")),i,true,logger);
        } else if(src.equals("modrinth")) installModrinthPack(cfg,modrinthPackSlug(i),"",i,true,logger);
        else if(src.equals("curseforge")) installCurseForgePack(cfg,s(i.get("pack_id")),i,true,logger);
        else throw new IOException("This profile has no supported pack source");
    }
    void restorePackMods(Map<String,Object> i,Map<String,Object> tracked,Log logger) {
        if(tracked.isEmpty())return;
        logger.log("Restoring separately installed mods...");
        Set<String> have=installedIds(i);
        i.put("mods",new LinkedHashMap<String,Object>());
        for(var entry:tracked.entrySet()) {
            Map<String,Object> mod=m(entry.getValue());
            List<Object> ids=l(mod.get("ids"));
            if(!ids.isEmpty() && have.containsAll(ids.stream().map(SkyLauncher::s).toList()))continue;
            try { installMod(entry.getKey(),s(mod.get("name")),i,b(mod.get("auto")),null,new HashSet<>(Set.of(entry.getKey())),null,null); }
            catch(Exception e){logger.log("Could not restore "+entry.getKey()+": "+e.getMessage());}
        }
    }
    void reinstallPack() {
        Map<String,Object> i=inst();
        if(s(i.get("pack_source")).isEmpty()) {notifyUser("This profile has no modpack installed.");return;}
        if(JOptionPane.showConfirmDialog(frame,"Reinstall the latest " + s(i.get("pack_name"))+
                "?\nThe current mods folder will be replaced; additional mods will be restored.",
                "Reinstall modpack",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        Map<String,Object> tracked=new LinkedHashMap<>(m(i.get("mods")));
        async("Reinstall modpack",()->{
            installNewestPack(i,this::log);restorePackMods(i,tracked,this::log);saveConfig(cfg);
            log("Modpack reinstalled.");
        });
    }
    void checkPackUpdate() { checkPackUpdates(false); }
    void checkAllPackUpdates() { checkPackUpdates(true); }
    void checkPackUpdates(boolean all) {
        if(busy.get()) {notifyUser("Another install is already running.");return;}
        List<Map<String,Object>> targets=new ArrayList<>();
        if(all) targets.addAll(insts().values().stream().map(SkyLauncher::m).toList());
        else targets.add(inst());
        Thread checker=new Thread(()->{
            List<Map<String,Object>> updates=new ArrayList<>();
            StringBuilder summary=new StringBuilder();
            for(Map<String,Object> item:targets) {
                String source=s(item.get("pack_source"));
                if(!Set.of("github","modrinth","curseforge").contains(source))continue;
                String name=s(item.get("pack_name"));if(name.isBlank())name="Unnamed pack";
                try {
                    Map<String,Object> latest=newestPack(item);
                    String old=installedPackId(item), next=s(latest.get("id"));
                    boolean different=old.isEmpty() ? !s(item.get("pack_version")).equals(s(latest.get("label"))) : !old.equals(next);
                    if(different) {
                        updates.add(item);
                        summary.append("• ").append(name).append(" (").append(source).append(") → ").append(latest.get("label")).append("\n");
                    } else log(name+" is up to date.");
                } catch(Exception ex) {log("Cannot check "+name+": "+ex.getMessage());}
            }
            SwingUtilities.invokeLater(()->{
                if(updates.isEmpty()) {JOptionPane.showMessageDialog(frame,"No new modpack updates found. See log for any errors.");return;}
                int yes=JOptionPane.showConfirmDialog(frame,"Updates found:\n\n"+summary+
                    "\nDownload and install these updates?", "Modpack updates",JOptionPane.YES_NO_OPTION);
                if(yes!=JOptionPane.YES_OPTION)return;
                async("Update modpacks",()->{
                    for(Map<String,Object> item:updates) {
                        String name=s(item.get("pack_name"));
                        Map<String,Object> tracked=new LinkedHashMap<>(m(item.get("mods")));
                        try {
                            log("Updating "+name+"...");
                            installNewestPack(item,this::log);
                            restorePackMods(item,tracked,this::log);
                            saveConfig(cfg);
                            log(name+" updated successfully.");
                        } catch(Exception ex) { log(name+" update failed: "+ex.getMessage()); }
                    }
                    saveConfig(cfg);
                });
            });
        },"SkyLauncher-modpack-updates");
        checker.setDaemon(true);checker.start();
    }

    // ---- running the game
    List<String> prepare(Map<String, Object> inst, String name, boolean run, Path[] cwdOut) throws Exception {
        Log log = this::log; Path rt = Paths.get(s(cfg.get("runtime_dir")).isEmpty() ? BASE.resolve("runtime").toString() : s(cfg.get("runtime_dir")));
        String mc = s(inst.get("mc_version")).trim(); if (mc.isEmpty()) throw new IOException("Set a Minecraft version first");
        Files.createDirectories(rt); log.log("Preparing '" + name + "': " + inst.get("loader") + " on Minecraft " + mc);
        Map<String, Object> vjson = ensureVanilla(rt, mc, log); String java = resolveJava(inst, rt, vjson, log);
        String vid = ensureLoader(inst, rt, java, log); saveConfig(cfg);
        SwingUtilities.invokeLater(() -> versionF.setText(vid));
        Map<String, Object> ver = loadVersion(rt, vid); String jarId = s(ver.get("_jar_id")); if (!jarId.equals(mc)) ensureVanilla(rt, jarId, log);
        Path jar = rt.resolve("versions").resolve(jarId).resolve(jarId + ".jar");
        var ln = resolveLibs(ver); var libs = ln.get(0); var natives = ln.get(1); List<Object[]> items = new ArrayList<>();
        log.log("Checking " + (libs.size() + natives.size()) + " libraries...");
        for (var x : libs) items.add(new Object[]{s(x.get("url")), rt.resolve("libraries").resolve(s(x.get("path"))), x.get("sha1") == null ? null : s(x.get("sha1")), x.get("size") == null ? null : (Long) n(x.get("size"), 0)});
        for (var x : natives) items.add(new Object[]{s(x.get("url")), rt.resolve("libraries").resolve(s(x.get("path"))), x.get("sha1") == null ? null : s(x.get("sha1")), x.get("size") == null ? null : (Long) n(x.get("size"), 0)});
        downloadAll(items, log, "libraries");
        Path ndir = rt.resolve("versions").resolve(vid).resolve("natives"); if (!natives.isEmpty()) extractNatives(rt, natives, ndir); Files.createDirectories(ndir);
        Path[] assets = ensureAssets(rt, ver, log); String logArg = "";
        Map<String, Object> lg = m(m(ver.get("logging")).get("client"));
        if (!lg.isEmpty()) { Map<String, Object> f = m(lg.get("file")); Path p = rt.resolve("assets").resolve("log_configs").resolve(s(f.get("id"))); fetchFile(s(f.get("url")), p, f.get("sha1") == null ? null : s(f.get("sha1")), null); logArg = s(lg.get("argument")).replace("${path}", p.toString()); }
        Path gd = gameDir(inst); Files.createDirectories(gd.resolve("mods")); cwdOut[0] = gd;
        if (!run) return null;
        Map<String, Object> acc = freshAccount(cfg, log); if (acc == null) throw new IOException("Not logged in. Press 'Log in with Microsoft' first.");
        log.log("Launching as " + acc.get("name"));
        StringBuilder cp = new StringBuilder(); for (var x : libs) cp.append(rt.resolve("libraries").resolve(s(x.get("path")))).append(SEP); cp.append(jar);
        Map<String, String> v = new HashMap<>(); v.put("auth_player_name", s(acc.get("name"))); v.put("version_name", jarId); v.put("game_directory", gd.toString());
        v.put("assets_root", assets[0].toString()); v.put("assets_index_name", s(m(ver.get("assetIndex")).get("id"))); v.put("auth_uuid", s(acc.get("uuid")));
        v.put("auth_access_token", s(acc.get("access_token"))); v.put("auth_session", s(acc.get("access_token"))); v.put("clientid", ""); v.put("auth_xuid", s(acc.get("xuid")));
        v.put("user_type", "msa"); v.put("user_properties", "{}"); v.put("version_type", ver.get("type") == null ? "release" : s(ver.get("type"))); v.put("natives_directory", ndir.toString());
        v.put("launcher_name", "skylauncher"); v.put("launcher_version", VERSION); v.put("classpath", cp.toString()); v.put("classpath_separator", SEP);
        v.put("library_directory", rt.resolve("libraries").toString()); v.put("primary_jar", jar.toString()); v.put("primary_jar_name", jar.getFileName().toString()); v.put("game_assets", assets[1].toString());
        Map<String, Object> args = m(ver.get("arguments")); List<String> jvm, game;
        if (!args.isEmpty()) { jvm = expandArgs(l(args.get("jvm"))); game = expandArgs(l(args.get("game"))); }
        else { jvm = new ArrayList<>(List.of("-Djava.library.path=${natives_directory}", "-cp", "${classpath}")); game = new ArrayList<>(Arrays.asList(s(ver.get("minecraftArguments")).trim().split("\\s+"))); }
        if (!jvm.contains("-cp") && !jvm.contains("-classpath")) { jvm.add("-cp"); jvm.add("${classpath}"); }
        if (jvm.stream().noneMatch(a -> a.startsWith("-Djava.library.path"))) jvm.add(0, "-Djava.library.path=${natives_directory}");
        if (OS.equals("osx") && !args.isEmpty() && !jvm.contains("-XstartOnFirstThread")) jvm.add(0, "-XstartOnFirstThread");
        int mem = effectiveMemoryMb(cfg, inst);
        List<String> extra = new ArrayList<>(); String w = s(inst.get("window_width")).trim(), h = s(inst.get("window_height")).trim();
        if (w.matches("\\d+") && h.matches("\\d+")) { extra.add("--width"); extra.add(w); extra.add("--height"); extra.add(h); }
        if (b(inst.get("fullscreen"))) extra.add("--fullscreen");
        extra.addAll(shlex(s(inst.get("game_args"))));
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx" + mem + "M"));
        cmd.addAll(shlex(effectiveJvmArgs(cfg, inst))); if (!logArg.isEmpty()) cmd.add(subst(logArg, v));
        for (String a : jvm) cmd.add(subst(a, v)); cmd.add(s(ver.get("mainClass"))); for (String a : game) cmd.add(subst(a, v)); cmd.addAll(extra);
        return cmd;
    }
    void run(boolean play) {
        Map<String, Object> i = inst(); String name = active();
        async("Run", () -> {
            Path[] cwd = new Path[1]; List<String> cmd = prepare(i, name, play, cwd);
            if (!play) { log("Ready. Press PLAY."); return; }
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd[0].toFile()).redirectErrorStream(true); pb.environment().putAll(parseEnv(s(i.get("env_vars"))));
            String pre = s(i.get("pre_launch_cmd")).trim();
            if (!pre.isEmpty()) {
                pre = placeholders(pre, i, name); log("Running pre-launch: " + pre);
                ProcessBuilder pp = new ProcessBuilder(shlex(pre)).directory(cwd[0].toFile()).inheritIO(); pp.environment().putAll(parseEnv(s(i.get("env_vars"))));
                if (pp.start().waitFor() != 0) throw new IOException("Pre-launch command failed; not starting.");
            }
            log("Starting game..."); Process p = pb.start(); game = p;
            Thread pump = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) { String ln; while ((ln = r.readLine()) != null) log("[mc] " + ln); } catch (IOException ignored) { }
                try { log("Game exited with code " + p.waitFor()); } catch (InterruptedException ignored) { } game = null;
            }); pump.setDaemon(true); pump.start();
        });
    }
    String placeholders(String cmd, Map<String, Object> i, String name) {
        return cmd.replace("{game_dir}", gameDir(i).toString()).replace("{mods_dir}", s(i.get("mods_dir"))).replace("{mc_version}", s(i.get("mc_version"))).replace("{loader}", s(i.get("loader"))).replace("{name}", name);
    }
    void play() {
        Map<String, Object> i = inst(); saveSettings();
        if (!s(i.get("launch_cmd")).trim().isEmpty()) {
            try { ProcessBuilder pb = new ProcessBuilder(shlex(placeholders(s(i.get("launch_cmd")).trim(), i, active()))); pb.environment().putAll(parseEnv(s(i.get("env_vars")))); pb.environment().put("GHML_GAME_DIR", gameDir(i).toString()); pb.start(); log("Launched external command."); }
            catch (Exception e) { log("Launch failed: " + e); }
        } else run(true);
    }
    void stopGame() { Process p = game; if (p != null) { p.destroy(); log("Asked the game to stop."); } }

    // ============================================================ self test (no network / no display)
    static void selfTest() throws Exception {
        String sample = "{\"token\": \"\", \"active\": \"Fathom Studios\", \"instances\": {\"Fathom Studios\": {\"mc_version\": \"1.21.1\", \"loader\": \"neoforge\", "
                + "\"mods\": {\"modrinth:AANobbMI\": {\"name\": \"Sodium\", \"tag\": \"0.6.0\", \"file\": \"sodium.jar\", \"sha256\": \"ab\", \"ids\": [\"sodium\"], \"auto\": false, \"required_by\": []}}, "
                + "\"fullscreen\": false, \"memory_mb\": \"4096\", \"future_key\": {\"x\": [1, 2.5, null, true, \"u\\u00e9\\n\"]}}}, \"theme_bg\": \"#16181f\", \"account\": null}";
        Map<String, Object> c = m(Json.parse(sample)); String out = Json.write(c); Map<String, Object> back = m(Json.parse(out));
        check(back.equals(c), "JSON round-trip"); check(out.contains("theme_bg") && out.contains("future_key"), "unknown keys preserved");
        // The original Python launcher stores its data under ~/.ghmodlauncher on every OS.
        // This is a regression check: changing the app's installation directory must not
        // move profiles, mods, the Minecraft runtime or account/config data.
        check(BASE.equals(HOME.resolve(".ghmodlauncher")) && CONFIG.equals(BASE.resolve("config.json")),
                "Python-compatible data folder");
        check(s(defaultInstance("Test", "1.21.1", "neoforge").get("mods_dir"))
                .equals(BASE.resolve("instances").resolve("Test").resolve(".minecraft").resolve("mods").toString()),
                "Python-compatible instance folder");

        Map<String, Object> memConfig = new LinkedHashMap<>();
        memConfig.put("generic_memory_mb", "10240"); memConfig.put("generic_jvm_args", "-XX:+UseG1GC");
        Map<String, Object> inherited = defaultInstance("Inherited", "1.21.1", "neoforge");
        check(effectiveMemoryMb(memConfig, inherited) == 10240, "generic 10GB inherited by new pack");
        inherited.put("memory_inherit", false); inherited.put("memory_mb", "6144");
        check(effectiveMemoryMb(memConfig, inherited) == 6144, "per-pack 6GB overrides generic 10GB");
        inherited.put("memory_mb", "invalid");
        check(effectiveMemoryMb(memConfig, inherited) == 10240, "invalid pack RAM falls back to generic");
        check(effectiveJvmArgs(memConfig, inherited).equals("-XX:+UseG1GC"), "generic JVM arguments inherited");
        inherited.put("jvm_args", "-XX:+UseZGC");
        check(effectiveJvmArgs(memConfig, inherited).equals("-XX:+UseZGC"), "per-pack JVM arguments override generic");
        Map<String,Object> cfSettings = new LinkedHashMap<>(Map.of("curseforge_bridge_url", "https://cf.example.com/"));
        check(cfBase(cfSettings).equals("https://cf.example.com"), "CurseForge bridge HTTPS URL normalization");
        check(cfDownloadUrl(cfSettings, "12345", "67890").equals("https://cf.example.com/v1/download/12345/67890"), "CurseForge mod or pack routed via VPS");
        check(!cfHeaders(cfSettings).containsKey("Authorization"), "public CurseForge bridge needs no client token");
        try { cfSettings.put("curseforge_bridge_url", "http://example.com:8767"); cfBase(cfSettings); throw new IllegalStateException("insecure bridge URL accepted"); }
        catch (IOException ignored) { check(true, "CurseForge VPS requires HTTPS"); }

        check(s(m(m(c.get("instances")).get("Fathom Studios")).get("loader")).equals("neoforge"), "reads Python-written config");
        check(mvnPath("net.fabricmc:fabric-loader:0.15.0").equals("net/fabricmc/fabric-loader/0.15.0/fabric-loader-0.15.0.jar"), "maven path");
        Map<String,Object> packVersionTest=defaultInstance("Pack","1.20.1","forge");
        packVersionTest.put("version_id","old");
        applyPackVersions(packVersionTest,"1.21.1","neoforge","21.1.199");
        check("neoforge".equals(packVersionTest.get("loader")) &&
            "1.21.1".equals(packVersionTest.get("mc_version")) &&
            s(packVersionTest.get("version_id")).isEmpty(),"pack updates Minecraft and NeoForge");
        check(profileActionLabel("Play Minecraft","Pack A").equals("Play: Pack A"),"profile button sync");
        check(conciseNotice("X".repeat(130)).contains("Logs"),"long notices redirect to logs");
        check(vcmp("21.1.10", "21.1.9") > 0, "version compare"); check(shlex("-Xss1M \"a b\" c").equals(List.of("-Xss1M", "a b", "c")), "shlex");
        check(rulesAllow(Json.parse("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"" + (OS.equals("linux") ? "windows" : "linux") + "\"}}]")), "rules");
        check(parseEnv("A=1;B=two words").get("B").equals("two words"), "env parse");
        check(digest("SHA-1", "abc".getBytes()).equals("a9993e364706816aba3e25717850c26c9cd0d89d"), "sha1");
        check(shellQuote("It's sky").equals("'It'\\''s sky'"), "shell escaping");
        check(desktopQuote("a b").equals("\"a b\""), "desktop escaping");
        Map<String,Object> fakeRelease = new LinkedHashMap<>();
        fakeRelease.put("tag_name", "v3.1.7");
        String exampleDigest = "sha256:" + "a".repeat(64);
        fakeRelease.put("assets", List.of(
            Map.of("name","SkyLauncher.jar", "browser_download_url","https://github.com/Newguygame/skylauncher/releases/download/v3.1.7/SkyLauncher.jar", "digest",exampleDigest),
            Map.of("name","SkyLauncher-3.1.7.exe", "browser_download_url","https://github.com/Newguygame/skylauncher/releases/download/v3.1.7/SkyLauncher-3.1.7.exe", "digest",exampleDigest),
            Map.of("name","SkyLauncher-3.1.7-arm64.dmg", "browser_download_url","https://github.com/Newguygame/skylauncher/releases/download/v3.1.7/SkyLauncher-3.1.7-arm64.dmg", "digest",exampleDigest),
            Map.of("name","skylauncher_3.1.7_amd64.deb", "browser_download_url","https://github.com/Newguygame/skylauncher/releases/download/v3.1.7/skylauncher_3.1.7_amd64.deb", "digest",exampleDigest)));
        check(selectLauncherAsset(fakeRelease,"portable-jar","windows","x86_64").name.equals("SkyLauncher.jar"), "portable JAR updater asset");
        check(selectLauncherAsset(fakeRelease,"win-installer","windows","x86_64").name.endsWith(".exe"), "Windows installer update asset");
        check(selectLauncherAsset(fakeRelease,"mac-app","osx","arm64").name.endsWith("-arm64.dmg"), "Apple Silicon updater asset");
        check(selectLauncherAsset(fakeRelease,"linux-deb","linux","x86_64").name.endsWith("amd64.deb"), "Linux deb updater asset");
        check(selectLauncherAsset(fakeRelease,"mac-app","osx","x86_64")==null, "missing platform build does not trigger update");
        fakeRelease.put("tag_name", "v3.1.6");
        check(selectLauncherAsset(fakeRelease,"portable-jar","windows","x86_64")==null, "wrong release asset rejected");
        fakeRelease.put("tag_name", "v3.1.7");
        check(compareReleaseVersions("v3.1.7", "3.1.3")>0, "updater version comparison");

        setShade("Black", "#29AABB", "#202833", "#FFFFFF");
        check(BG.equals(new Color(0x202833)) && FG.equals(Color.WHITE) && ACCENT.equals(new Color(0x29AABB)), "custom theme colors");
        check(SURFACE.getRGB() != BG.getRGB(), "background surface contrast");
        setShade("White", "", "", "");
        check(BG.equals(Color.WHITE) && FG.equals(new Color(0x171717)), "preset theme colors");
        // Custom icons are optional; an icon-free build is valid.
        System.out.println("selftest OK  (os=" + OS + " arch=" + ARCH + ")");
    }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError("FAILED: " + what); System.out.println("ok  " + what); }
}
