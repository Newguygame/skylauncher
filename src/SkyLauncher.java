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
import javax.imageio.ImageIO;

public class SkyLauncher {
    static final String VERSION = "3.1.1-java";
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
    static final class HttpErr extends IOException {
        final int code; final String body;
        HttpErr(int code, String body, String url) { super("HTTP " + code + " for " + url); this.code = code; this.body = body; }
    }
    static HttpResponse<InputStream> open(String method, String url, Map<String, String> h, byte[] body) throws IOException {
        String cur = url, firstHost = URI.create(url).getHost();
        for (int hop = 0; hop < 8; hop++) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(cur)).timeout(Duration.ofSeconds(300))
                    .setHeader("User-Agent", "skylauncher-java");
            boolean sameHost = Objects.equals(URI.create(cur).getHost(), firstHost);
            if (h != null) for (var e : h.entrySet()) {
                if (e.getKey().equalsIgnoreCase("Authorization") && !sameHost) continue; // never leak tokens to CDNs
                rb.setHeader(e.getKey(), e.getValue());
            }
            rb.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<InputStream> r;
            try { r = HC.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream()); }
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
        i.put("java_path", ""); i.put("memory_mb", "4096"); i.put("jvm_args", ""); i.put("launch_cmd", "");
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
        try { cfg.putAll(m(Json.parse(Files.readString(CONFIG)))); } catch (Exception ignored) { }
        Map<String, Object> insts = m(cfg.get("instances"));
        cfg.put("instances", insts);
        if (insts.isEmpty()) insts.put("Default", defaultInstance("Default", "", "fabric"));
        for (var e : insts.entrySet()) {
            Map<String, Object> i = m(e.getValue());
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
    static void installModrinthPack(Map<String, Object> cfg, String slug, String version, Map<String, Object> inst, boolean clear, Log log) throws Exception {
        Map<String, Object> p = m(mr(cfg, "/project/" + enc(slug)));
        if (!"modpack".equals(s(p.get("project_type")))) throw new IOException("'" + slug + "' on Modrinth is a " + p.get("project_type") + ", not a modpack");
        Map<String, Object> v;
        if (!version.isEmpty()) v = m(mr(cfg, "/project/" + p.get("id") + "/version/" + enc(version)));
        else {
            List<Object> vs = l(mr(cfg, "/project/" + p.get("id") + "/version")); if (vs.isEmpty()) throw new IOException("This modpack has no versions");
            v = m(vs.get(0)); for (Object o : vs) if ("release".equals(s(m(o).get("version_type")))) { v = m(o); break; }
        }
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
                inst.put("mc_version", mc); inst.put("loader", loader); inst.put("loader_version", lver);
                log.log("Pack needs " + loader + " " + lver + " on Minecraft " + mc);
            } finally { Files.deleteIfExists(dl); }
        } finally { Files.deleteIfExists(tmp); }
        inst.put("pack_source", "modrinth"); inst.put("pack_repo", ""); inst.put("pack_ref", ""); inst.put("pack_sha", ""); inst.put("pack_skip_sha", "");
        inst.put("pack_url", "https://modrinth.com/modpack/" + (p.get("slug") == null ? slug : p.get("slug"))); inst.put("pack_name", p.get("title")); inst.put("pack_version", v.get("version_number"));
        log.log("Pack install complete.");
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
    final Map<String, JTextField> fields = new LinkedHashMap<>(); JCheckBox fullscreen; JTextField tokenF, mrTokenF, runtimeF, versionF;
    JComboBox<String> loaderBox, launcherBox;
    final List<Map<String, Object>> searchHits = new ArrayList<>();

    Map<String, Object> insts() { return m(cfg.get("instances")); }
    String active() { return s(cfg.get("active")); }
    Map<String, Object> inst() { return m(insts().get(active())); }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) { selfTest(); return; }
        PREVIEW_MODE = Arrays.asList(args).contains("--ui-preview");
        if (OS.equals("unknown")) { System.err.println("Unsupported OS: " + System.getProperty("os.name")); return; }
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new SkyLauncher().build());
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
            catch (Exception e) { log(what + " ERROR: " + e); }
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
            gg.setColor(SURFACE); gg.fillRoundRect(0,0,getWidth()-1,getHeight()-1,arc,arc);
            if (line) { gg.setColor(outline()); gg.drawRoundRect(0,0,getWidth()-1,getHeight()-1,arc,arc); }
            gg.dispose(); super.paintComponent(g);
        }
    }
    static class RoundedAction extends JButton {
        final String role;
        boolean hover;
        RoundedAction(String title, String role) {
            super(title); this.role = role;
            setFont(new Font("Dialog", role.equals("play") ? Font.BOLD : Font.PLAIN, role.equals("play") ? 15 : 12));
            setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
            setPreferredSize(new Dimension(role.equals("play") ? 118 : Math.max(78, title.length()*7+30), role.equals("play") ? 40 : 32));
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
            gg.setColor(shade); gg.fillRoundRect(0,0,getWidth()-1,getHeight()-1,getHeight(),getHeight());
            gg.dispose();
            setForeground(contrast(shade));
            super.paintComponent(g);
        }
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
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
    }
    static void styleTable(JTable table) {
        table.setRowHeight(27); table.setShowVerticalLines(false); table.setGridColor(outline());
        table.setFillsViewportHeight(true); table.getTableHeader().setReorderingAllowed(false);
    }
    JPanel makeActivityLog() {
        JPanel container = new JPanel(new BorderLayout(0, 7));
        container.setBackground(BG); container.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        JPanel title = new JPanel(new BorderLayout()); title.setBackground(BG);
        title.add(smallHeading("RECENT ACTIVITY"), BorderLayout.WEST);
        title.add(btn("Clear", () -> logBox.setText("")), BorderLayout.EAST);
        container.add(title, BorderLayout.NORTH);
        logBox = new JTextArea(5, 80); logBox.setEditable(false);
        logBox.setBackground(SURFACE); logBox.setForeground(GREEN);
        logBox.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logBox.setLineWrap(true); logBox.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scroll = new JScrollPane(logBox); styleScroll(scroll); container.add(scroll,BorderLayout.CENTER);
        return container;
    }
    JPanel makeBottomBar() {
        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setBackground(SURFACE); footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(outline(),1,true), BorderFactory.createEmptyBorder(10,14,10,14)));
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
    void build() {
        setShade(s(cfg.getOrDefault("ui_shade_preset", "Black")), s(cfg.get("ui_accent_hex")),
            s(cfg.get("ui_background_hex")), s(cfg.get("ui_text_hex")));
        applyPythonTheme();
        frame = new JFrame("SkyLauncher " + VERSION);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(940, 620)); frame.setSize(1360, 760);
        frame.getContentPane().setBackground(BG);
        java.awt.Image iconImage = appIcon(); if (iconImage != null) frame.setIconImage(iconImage);

        JPanel header = new RoundedSurface(new BorderLayout(12, 0), 25, true);
        header.setBorder(BorderFactory.createEmptyBorder(9, 20, 9, 20));
        JLabel brand = new JLabel("SkyLauncher"); brand.setFont(new Font("Dialog", Font.BOLD, 23));
        brand.setForeground(FG);
        JPanel brandStack = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0)); brandStack.setOpaque(false);
        brandStack.add(brand);
        JLabel versionLabel = new JLabel("v"+VERSION); versionLabel.setForeground(MUTED); versionLabel.setFont(new Font("Dialog",Font.PLAIN,11));
        brandStack.add(versionLabel); header.add(brandStack,BorderLayout.WEST);
        JPanel account = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); account.setOpaque(false);
        authLabel = new JLabel(); authLabel.setForeground(MUTED); account.add(authLabel);
        launcherUpdateButton = actionButton("Update launcher", this::offerLauncherUpdate, ACCENT);
        launcherUpdateButton.setVisible(false); account.add(launcherUpdateButton);
        account.add(actionButton("Log in with Microsoft", this::login, ACCENT));
        account.add(btn("Log out", () -> { cfg.put("account", null); saveConfig(cfg); updateAuth(); log("Logged out."); }));
        header.add(account, BorderLayout.EAST);

        JPanel profile = new JPanel(new FlowLayout(FlowLayout.LEFT, 13, 7)); profile.setBackground(BG);
        profile.add(smallHeading("PROFILE"));
        instBox = new JComboBox<>(); instBox.setPrototypeDisplayValue("xxxxxxxxxxxxxxxxxxxxxxxx");
        instBox.setPreferredSize(new Dimension(228, 32)); profile.add(instBox);
        instBox.addActionListener(e -> { if (!loading && instBox.getSelectedItem() != null) selectInstance((String) instBox.getSelectedItem()); });
        profile.add(btn("New profile", this::newInstance)); profile.add(btn("Open folder", this::openFolder));
        profile.add(actionButton("Delete", this::deleteInstance, RED));
        JPanel top = new JPanel(new BorderLayout(0, 6)); top.setBackground(BG);
        top.setBorder(BorderFactory.createEmptyBorder(10, 11, 0, 11));
        top.add(header,BorderLayout.NORTH); top.add(profile,BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane(); tabs.setBackground(BG);
        tabs.addTab("Mods",modsTab()); tabs.addTab("Settings",settingsTab());
        tabs.setFont(new Font("Dialog",Font.PLAIN,12));
        JPanel activity = makeActivityLog();
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,tabs,activity);
        split.setBorder(null); split.setResizeWeight(.62); split.setDividerSize(5);
        split.setContinuousLayout(true);
        JPanel footerHolder = new JPanel(new BorderLayout()); footerHolder.setBackground(BG);
        footerHolder.setBorder(BorderFactory.createEmptyBorder(6,11,10,11));
        footerHolder.add(makeBottomBar(),BorderLayout.CENTER);
        frame.add(top, BorderLayout.NORTH); frame.add(split,BorderLayout.CENTER);
        frame.add(footerHolder,BorderLayout.SOUTH);
        frame.setLocationRelativeTo(null); frame.setVisible(true);
        selectInstance(active());
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
    void checkLauncherUpdate() {
        String repo = launcherRepo();
        if (repo.isEmpty()) { log("Launcher update checks disabled: set SKYLAUNCHER_UPDATE_REPO=owner/repo or launcher_update_repo in config.json"); return; }
        Thread worker = new Thread(() -> {
            try {
                Map<String, Object> rel = m(getJson("https://api.github.com/repos/" + repo + "/releases/latest", ghHeaders(cfg, null)));
                String tag = s(rel.get("tag_name"));
                if (compareReleaseVersions(tag, VERSION) <= 0) return;
                availableLauncherRelease = rel;
                SwingUtilities.invokeLater(() -> {
                    launcherUpdateButton.setVisible(true);
                    launcherUpdateButton.setToolTipText("SkyLauncher " + tag + " is available");
                    frame.revalidate();
                    int choice = JOptionPane.showConfirmDialog(frame,
                        "SkyLauncher " + tag + " is available (installed: " + VERSION + ").\nUpdate now?",
                        "SkyLauncher update available", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) offerLauncherUpdate();
                });
            } catch (Exception e) { log("Launcher update check failed: " + e.getMessage()); }
        }, "SkyLauncher-update-check");
        worker.setDaemon(true); worker.start();
    }
    void offerLauncherUpdate() {
        Map<String, Object> rel = availableLauncherRelease;
        if (rel == null) return;
        String extension = OS.equals("windows") ? ".exe" : OS.equals("osx") ? ".dmg" : ".deb";
        String arch = ARCH.equals("arm64") ? "arm64" : ARCH.equals("x86_64") ? "x64" : "x86";
        String url = "";
        for (Object o : l(rel.get("assets"))) {
            Map<String, Object> a = m(o);
            String name = s(a.get("name")).toLowerCase(Locale.ROOT);
            if (name.endsWith(extension) && (name.contains(arch) || !name.contains("arm64") && !name.contains("aarch64"))) {
                url = s(a.get("browser_download_url")); break;
            }
        }
        if (url.isEmpty()) url = s(rel.get("html_url"));
        if (url.isEmpty()) { JOptionPane.showMessageDialog(frame, "No installer or release page is available."); return; }
        try { Desktop.getDesktop().browse(URI.create(url)); }
        catch (Exception e) { JOptionPane.showMessageDialog(frame, "Cannot open update link: " + e.getMessage()); }
    }

    JPanel modsTab() {
        JPanel content = new JPanel(); content.setBackground(BG);
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
        packActions.add(btn("Check for updates", this::checkPackUpdate));
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
        addInput.add(actionButton("Add mod",this::addMod,ACCENT),BorderLayout.EAST);
        addCard.add(addInput,BorderLayout.CENTER);
        JLabel tip = new JLabel("GitHub owner/name or URL   ·   Modrinth link   ·   modrinth:slug   ·   direct .jar URL");
        tip.setForeground(MUTED); tip.setFont(new Font("Dialog",Font.PLAIN,12));
        addCard.add(tip,BorderLayout.SOUTH);
        content.add(addCard); content.add(Box.createVerticalStrut(12));

        JPanel searchCard = glassCard(new BorderLayout(0,10)); searchCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchCard.add(smallHeading("SEARCH MODRINTH"),BorderLayout.NORTH);
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
        outer.setBorder(null); outer.getViewport().setBackground(BG); outer.getVerticalScrollBar().setUnitIncrement(22);
        JPanel wrapper=new JPanel(new BorderLayout()); wrapper.setBackground(BG); wrapper.add(outer,BorderLayout.CENTER);
        return wrapper;
    }
    // Modpack actions are intentionally on the Mods tab to match the Python launcher.
    /** Settings keep common choices visible; expert options are in a separate dialog. */
    JPanel settingsTab() {
        JPanel page = new JPanel();
        page.setBackground(BG);
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
        addSettingsField(gameForm, c, "RAM (MB)", "memory_mb",
            "4096 MB = 4 GB. Only change this if Minecraft needs more or less memory.");
        addSettingsField(gameForm, c, "Extra JVM arguments", "jvm_args",
            "Optional Java options, for example -XX:+UseG1GC. Leave blank unless needed.");
        gameCard.add(gameForm, BorderLayout.CENTER);
        page.add(gameCard); page.add(Box.createVerticalStrut(12));

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
        appearanceCard.add(appearance, BorderLayout.CENTER);
        page.add(appearanceCard); page.add(Box.createVerticalStrut(12));

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

        JPanel wrapper = new JPanel(new BorderLayout()); wrapper.setBackground(BG);
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(null); scroll.getViewport().setBackground(BG); scroll.getVerticalScrollBar().setUnitIncrement(18);
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
        cfg.put("runtime_dir", runtimeF.getText().trim()); cfg.put("token", tokenF.getText().trim()); cfg.put("modrinth_token", mrTokenF.getText().trim());
        saveConfig(cfg);
    }
    void selectInstance(String name) {
        cfg.put("active", name); loading = true;
        instBox.removeAllItems(); for (String k : insts().keySet()) instBox.addItem(k); instBox.setSelectedItem(name);
        Map<String, Object> i = inst();
        for (var e : fields.entrySet()) e.getValue().setText(s(i.get(e.getKey())));
        loaderBox.setSelectedItem(s(i.get("loader"))); fullscreen.setSelected(b(i.get("fullscreen"))); versionF.setText(s(i.get("version_id")));
        runtimeF.setText(s(cfg.get("runtime_dir"))); tokenF.setText(s(cfg.get("token"))); mrTokenF.setText(s(cfg.get("modrinth_token")));
        loading = false; saveConfig(cfg); refreshAll(); updateAuth();
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
        if (insts().size() < 2) { JOptionPane.showMessageDialog(frame, "Keep at least one profile."); return; }
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
            profile.put("javaArgs", "-Xmx"+s(instance.get("memory_mb"))+"M " + s(instance.get("jvm_args")));
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
    String modLabel(Map<String, Object> inst, String key) { String nm = s(m(m(inst.get("mods")).get(key)).get("name")); return nm.isEmpty() ? key : nm; }
    void addMod() {
        String text = addField.getText().trim(); String key = null, name = null, ref;
        Matcher mm = Pattern.compile("(?:https?://)?(?:www\\.)?modrinth\\.com/(?:mod|plugin|datapack)/([^/?#\\s]+).*").matcher(text);
        if (text.matches("https?://\\S+\\.jar(?:\\?\\S*)?")) ref = "url:" + text;
        else if (mm.matches()) ref = "modrinth:" + mm.group(1);
        else if (text.toLowerCase().matches("modrinth:\\S+")) ref = "modrinth:" + text.substring(9);
        else if (text.toLowerCase().contains("curseforge")) { JOptionPane.showMessageDialog(frame, "CurseForge isn't supported in the Java edition yet.\nUse Modrinth/GitHub or the Python version."); return; }
        else { String repo = text.replaceAll("^https?://github\\.com/", "").replaceAll("^/+|/+$", "").replaceAll("\\.git$", ""); String[] pp = repo.split("/");
            if (pp.length < 2 || !(pp[0] + "/" + pp[1]).matches("[\\w.-]+/[\\w.-]+")) { JOptionPane.showMessageDialog(frame, "Use a GitHub owner/name or URL, a Modrinth link, or a direct .jar link."); return; }
            ref = pp[0] + "/" + pp[1]; }
        addField.setText(""); final String fref = ref;
        async("Add mod", () -> {
            Map<String, Object> i = inst(); String k = fref;
            if (fref.startsWith("modrinth:")) { Map<String, Object> p = m(mr(cfg, "/project/" + enc(fref.substring(9)))); k = "modrinth:" + p.get("id"); log("Resolved Modrinth '" + fref.substring(9) + "' -> " + p.get("title")); installMod(k, s(p.get("title")), i, false, null, new HashSet<>(Set.of(k)), null, null); }
            else if (fref.startsWith("url:")) { String u = fref.substring(4); k = "url:" + digest("SHA-1", u.getBytes()).substring(0, 12); installUrl(k, u, i); }
            else installMod(k, k, i, false, null, new HashSet<>(Set.of(k)), null, null);
            log("Done.");
        });
    }
    void search() {
        String q = searchField.getText().trim(); if (q.isEmpty()) return; Map<String, Object> i = inst();
        async("Search", () -> {
            List<Object> facets = new ArrayList<>(); facets.add(List.of("project_type:mod"));
            if (!s(i.get("loader")).isEmpty()) facets.add(List.of("categories:" + i.get("loader")));
            if (!s(i.get("mc_version")).trim().isEmpty()) facets.add(List.of("versions:" + s(i.get("mc_version")).trim()));
            Map<String, Object> r = m(mr(cfg, "/search?query=" + enc(q) + "&limit=25&facets=" + enc(Json.write(facets).replaceAll("\\s+", ""))));
            List<Map<String, Object>> hits = new ArrayList<>(); for (Object o : l(r.get("hits"))) hits.add(m(o));
            SwingUtilities.invokeLater(() -> { searchModel.setRowCount(0); searchHits.clear(); searchHits.addAll(hits);
                for (var h : hits) { long dl = n(h.get("downloads"), 0); searchModel.addRow(new Object[]{h.get("title"), dl >= 1_000_000 ? String.format("%.1fM", dl / 1e6) : dl >= 1000 ? dl / 1000 + "K" : dl, s(h.get("description"))}); } });
            log("Search: " + hits.size() + " result(s)");
        });
    }
    void installSearched() {
        int row = searchTable.getSelectedRow(); if (row < 0 || row >= searchHits.size()) { JOptionPane.showMessageDialog(frame, "Select a search result first."); return; }
        Map<String, Object> h = searchHits.get(row); String key = "modrinth:" + h.get("project_id"); Map<String, Object> i = inst();
        async("Install", () -> { installMod(key, s(h.get("title")), i, false, null, new HashSet<>(Set.of(key)), null, null); log("Done."); });
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
        if (src.equals("curseforge")) { log("[" + label + "] CurseForge mods aren't supported in the Java edition yet - skipped"); return; }
        log("[" + label + "] checking " + src + " (" + loader + " " + (s(inst.get("mc_version")).isEmpty() ? "any MC" : inst.get("mc_version")) + ")...");
        List<Map<String, Object>> versions = listVersions(key, inst, cfg); Map<String, Object> v = null;
        if (vid != null) { for (var x : versions) if (s(x.get("id")).equals(vid)) v = x; } else v = pickLatest(versions);
        if (v == null) { log("[" + label + "] no version with a jar for " + loader + " " + inst.get("mc_version")); return; }
        Files.createDirectories(dir);
        // resolve file + expected hash
        String url, fname, sha1 = null, sha512 = null, sha256 = null, tag = s(v.get("label")), versionId = s(v.get("id")); Map<String, String> dlHeaders = null;
        if (src.equals("modrinth")) {
            Map<String, Object> f = m(l(v.get("files")).get(0)); url = s(f.get("url")); fname = Paths.get(s(f.get("name"))).getFileName().toString();
            sha1 = f.get("sha1") == null ? null : s(f.get("sha1")); sha512 = f.get("sha512") == null ? null : s(f.get("sha512"));
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
        if (src.equals("modrinth")) info.put("version_id", versionId);
        if (!auto) info.put("auto", false); else info.putIfAbsent("auto", true);
        if (parent != null) { List<Object> rb = l(info.get("required_by")); if (!rb.contains(parent)) rb.add(parent); info.put("required_by", rb); }
        saveConfig(cfg);
        // required dependencies declared by Modrinth
        for (Object d : l(v.get("deps"))) {
            String k2 = "modrinth:" + d; if (k2.equals(key) || !seen.add(k2)) continue;
            Map<String, Object> have = m(m(inst.get("mods")).get(k2));
            if (!have.isEmpty() && Files.exists(dir.resolve(s(have.get("file"))))) { continue; }
            String nm = s(m(mr(cfg, "/project/" + enc(s(d)))).get("title")); log("[" + label + "] requires " + nm);
            try { installMod(k2, nm, inst, true, key, seen, null, null); } catch (Exception e) { log("[" + nm + "] dependency install failed: " + e); }
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
        String text = JOptionPane.showInputDialog(frame, "<html>Pack link or GitHub repo:<br>GitHub: owner/name or https://github.com/owner/name<br>Modrinth: https://modrinth.com/modpack/&lt;slug&gt;[/version/&lt;id&gt;]</html>");
        if (text == null || text.isBlank()) return; text = text.trim();
        Matcher mr = Pattern.compile("(?:https?://)?(?:www\\.)?modrinth\\.com/modpack/([^/?#\\s]+)(?:/version/([^/?#\\s]+))?.*").matcher(text);
        String repo = text.replaceAll("^https?://github\\.com/", "").replaceAll("^/+|/+$", "").replaceAll("\\.git$", ""); String[] pp = repo.split("/");
        boolean isMr = mr.matches(), isGh = !isMr && pp.length >= 2 && (pp[0] + "/" + pp[1]).matches("[\\w.-]+/[\\w.-]+");
        if (text.contains("curseforge")) { JOptionPane.showMessageDialog(frame, "CurseForge packs aren't supported in the Java edition yet."); return; }
        if (!isMr && !isGh) { JOptionPane.showMessageDialog(frame, "Couldn't recognise that as a GitHub repo or Modrinth modpack link."); return; }
        String slug = isMr ? mr.group(1) : pp[1], ver = isMr && mr.group(2) != null ? mr.group(2) : "", ref = "main";
        if (isGh) { String r = JOptionPane.showInputDialog(frame, "Branch or tag:", "main"); if (r == null) return; ref = r.trim().isEmpty() ? "main" : r.trim(); }
        if (JOptionPane.showConfirmDialog(frame, "Install into a NEW profile?\n(No = replace the mods folder of '" + active() + "')", "New profile?", JOptionPane.YES_NO_OPTION) == 0) {
            String pn = slug, base = slug; for (int n = 2; insts().containsKey(pn); n++) pn = base + "-" + n;
            Map<String, Object> ni = defaultInstance(pn, s(inst().get("mc_version")).isEmpty() ? "1.21.1" : s(inst().get("mc_version")), s(inst().get("loader")).isEmpty() ? "neoforge" : s(inst().get("loader")));
            if (isGh) { String mc = JOptionPane.showInputDialog(frame, "Minecraft version for this pack:", ni.get("mc_version")); if (mc == null) return; ni.put("mc_version", mc.trim());
                Object ld = JOptionPane.showInputDialog(frame, "Loader:", "Loader", JOptionPane.PLAIN_MESSAGE, null, LOADERS, ni.get("loader")); if (ld == null) return; ni.put("loader", ld); }
            insts().put(pn, ni); selectInstance(pn);
        }
        final String frepo = repo.isEmpty() ? "" : pp.length >= 2 ? pp[0] + "/" + pp[1] : repo, fref = ref, fver = ver;
        packJob(() -> { if (isMr) installModrinthPack(cfg, slug, fver, inst(), true, this::log); else installGithubPack(cfg, frepo, fref, inst(), true, this::log); }, false);
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
    void reinstallPack() {
        Map<String, Object> i = inst(); String src = s(i.get("pack_source")), repo = s(i.get("pack_repo")), ref = s(i.get("pack_ref")).isEmpty() ? "main" : s(i.get("pack_ref"));
        if (src.equals("modrinth") && !s(i.get("pack_url")).isEmpty()) {
            String slug = s(i.get("pack_url")).replaceAll(".*/modpack/", "");
            if (JOptionPane.showConfirmDialog(frame, "Re-download the latest version of " + slug + "?\nThe mods folder is replaced; mods you added are re-installed.", "Reinstall modpack", JOptionPane.YES_NO_OPTION) == 0) packJob(() -> installModrinthPack(cfg, slug, "", i, true, this::log), true);
            return;
        }
        if (repo.isEmpty()) { JOptionPane.showMessageDialog(frame, "This profile has no GitHub/Modrinth pack recorded."); return; }
        if (JOptionPane.showConfirmDialog(frame, "Re-download and reinstall " + repo + "@" + ref + "?\nThe mods folder is replaced; mods you added are re-installed.", "Reinstall modpack", JOptionPane.YES_NO_OPTION) == 0) packJob(() -> installGithubPack(cfg, repo, ref, i, true, this::log), true);
    }
    void checkPackUpdate() {
        Map<String, Object> i = inst(); String repo = s(i.get("pack_repo")), ref = s(i.get("pack_ref")).isEmpty() ? "main" : s(i.get("pack_ref"));
        if (repo.isEmpty()) { JOptionPane.showMessageDialog(frame, "Update checks only work for packs installed from GitHub.\nFor Modrinth packs use 'Reinstall pack'."); return; }
        Thread t = new Thread(() -> {
            try {
                Map<String, Object> c = m(getJson("https://api.github.com/repos/" + repo + "/commits/" + enc(ref).replace("%2F", "/"), ghHeaders(cfg, null)));
                String latest = s(c.get("sha")), cur = s(i.get("pack_sha"));
                SwingUtilities.invokeLater(() -> {
                    if (cur.isEmpty()) JOptionPane.showMessageDialog(frame, "The installed commit isn't recorded. Use 'Reinstall pack' once and updates will be detected.");
                    else if (cur.equals(latest)) JOptionPane.showMessageDialog(frame, repo + " is up to date (" + cur.substring(0, 7) + ").");
                    else if (JOptionPane.showConfirmDialog(frame, "A newer version of " + repo + " is available.\nInstalled: " + cur.substring(0, 7) + "\nLatest: " + latest.substring(0, 7) + "\n\nUpdate now?", "Modpack update", JOptionPane.YES_NO_OPTION) == 0)
                        packJob(() -> installGithubPack(cfg, repo, ref, i, true, this::log), true);
                });
            } catch (Exception e) { log("Pack update check failed: " + e.getMessage()); }
        }); t.setDaemon(true); t.start();
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
        int mem; try { mem = Integer.parseInt(s(inst.get("memory_mb")).trim()); } catch (NumberFormatException e) { mem = 4096; }
        List<String> extra = new ArrayList<>(); String w = s(inst.get("window_width")).trim(), h = s(inst.get("window_height")).trim();
        if (w.matches("\\d+") && h.matches("\\d+")) { extra.add("--width"); extra.add(w); extra.add("--height"); extra.add(h); }
        if (b(inst.get("fullscreen"))) extra.add("--fullscreen");
        extra.addAll(shlex(s(inst.get("game_args"))));
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx" + mem + "M")); cmd.addAll(shlex(s(inst.get("jvm_args")))); if (!logArg.isEmpty()) cmd.add(subst(logArg, v));
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

        check(s(m(m(c.get("instances")).get("Fathom Studios")).get("loader")).equals("neoforge"), "reads Python-written config");
        check(mvnPath("net.fabricmc:fabric-loader:0.15.0").equals("net/fabricmc/fabric-loader/0.15.0/fabric-loader-0.15.0.jar"), "maven path");
        check(vcmp("21.1.10", "21.1.9") > 0, "version compare"); check(shlex("-Xss1M \"a b\" c").equals(List.of("-Xss1M", "a b", "c")), "shlex");
        check(rulesAllow(Json.parse("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"" + (OS.equals("linux") ? "windows" : "linux") + "\"}}]")), "rules");
        check(parseEnv("A=1;B=two words").get("B").equals("two words"), "env parse");
        check(digest("SHA-1", "abc".getBytes()).equals("a9993e364706816aba3e25717850c26c9cd0d89d"), "sha1");
        check(shellQuote("It's sky").equals("'It'\\''s sky'"), "shell escaping");
        check(desktopQuote("a b").equals("\"a b\""), "desktop escaping");
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
