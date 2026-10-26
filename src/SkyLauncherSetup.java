import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import java.util.zip.*;
import java.util.jar.JarFile;

/** A small, platform-independent front end for the latest SkyLauncher.jar on GitHub Releases. */
public final class SkyLauncherSetup {
    static final String REPO = "Newguygame/skylauncher";
    static final Path DATA = Path.of(System.getProperty("user.home"), ".ghmodlauncher");
    static final Path APP_DIR = DATA.resolve("launcher-app");
    static final Path INSTALLED = APP_DIR.resolve("SkyLauncher.jar");
    static final Path VERSION_FILE = APP_DIR.resolve("release-tag.txt");
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15)).build();
    static final Color BACK = new Color(22, 22, 22), PANEL = new Color(38, 38, 38), BLUE = new Color(69, 153, 255);
    JFrame frame; JLabel status; JButton launch, install, bundled, shortcut;
    boolean busy = false;
    volatile Release newer;

    record Asset(String name, URI url, String sha256, long size) { }
    record Release(String tag, Asset jar) { }

    static String os() {
        String v = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return v.contains("win") ? "windows" : v.contains("mac") ? "mac" : "linux";
    }
    static URI apiUri() { return URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"); }
    static String request(URI uri) throws Exception {
        requireHttps(uri);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json").header("User-Agent", "SkyLauncherSetup")
            .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) throw new IOException("GitHub returned HTTP " + res.statusCode()
            + (res.statusCode() == 404 ? " (no published release yet?)" : ""));
        return res.body();
    }
    static void requireHttps(URI url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getScheme())) throw new IOException("Only HTTPS downloads are supported");
        if (url.getHost() == null) throw new IOException("Missing download host");
    }
    static Release parseRelease(String json) throws Exception {
        Object parsed = SkyLauncher.Json.parse(json);
        Map<String,Object> m = SkyLauncher.m(parsed);
        String tag = SkyLauncher.s(m.get("tag_name"));
        if (tag.isBlank()) throw new IOException("Release has no tag_name");
        // Never accidentally select the bootstrap installer, -sources, or a mod jar.
        Asset selected = null;
        for (Object obj : SkyLauncher.l(m.get("assets"))) {
            Map<String,Object> a = SkyLauncher.m(obj);
            String name = SkyLauncher.s(a.get("name"));
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.matches("skylauncher(?:[-_.]v?[0-9][a-z0-9.\\-_]*)?\\.jar") ||
                lower.contains("setup") || lower.contains("sources") || lower.contains("javadoc")) continue;
            URI url = URI.create(SkyLauncher.s(a.get("browser_download_url")));
            requireHttps(url);
            if (!"github.com".equalsIgnoreCase(url.getHost())) continue;
            String digest = SkyLauncher.s(a.get("digest"));
            String sha = digest.startsWith("sha256:") ? digest.substring(7) : "";
            if (!sha.isEmpty() && !sha.matches("[0-9a-fA-F]{64}")) throw new IOException("Invalid release digest");
            long size = SkyLauncher.n(a.get("size"), 0);
            if (size > 150_000_000L) throw new IOException("Launcher jar is unexpectedly large");
            Asset candidate = new Asset(name, url, sha, size);
            if (selected == null || lower.equals("skylauncher.jar")) selected = candidate;
        }
        if (selected == null) throw new IOException("Release " + tag + " has no SkyLauncher.jar asset. " +
            "Publish the compiled launcher JAR as a GitHub release asset.");
        return new Release(tag, selected);
    }
    static Release latest() throws Exception { return parseRelease(request(apiUri())); }
    static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte v : md.digest()) s.append(String.format("%02x", v & 255));
        return s.toString();
    }
    static void validateJar(Path file) throws Exception {
        try (JarFile zip = new JarFile(file.toFile())) {
            if (zip.getEntry("SkyLauncher.class") == null)
                throw new IOException("The downloaded JAR is not a SkyLauncher application");
            if (zip.getManifest() == null || !"SkyLauncher".equals(
                zip.getManifest().getMainAttributes().getValue("Main-Class")))
                throw new IOException("SkyLauncher JAR does not have the expected Main-Class");
        }
    }
    static void installFrom(Path source, String tag) throws Exception {
        Files.createDirectories(APP_DIR);
        Path staged = Files.createTempFile(APP_DIR, "SkyLauncher-", ".part");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            validateJar(staged);
            Path backup = APP_DIR.resolve("SkyLauncher.previous.jar");
            if (Files.isRegularFile(INSTALLED)) Files.copy(INSTALLED, backup, StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(staged, INSTALLED, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(staged, INSTALLED, StandardCopyOption.REPLACE_EXISTING); }
            Files.writeString(VERSION_FILE, tag + "\n", StandardCharsets.UTF_8);
        } finally { Files.deleteIfExists(staged); }
    }
    static void download(Release release) throws Exception {
        Files.createDirectories(APP_DIR);
        Asset a = release.jar();
        Path part = Files.createTempFile(APP_DIR, "sky-download-", ".jar");
        try {
            HttpRequest req = HttpRequest.newBuilder(a.url()).timeout(Duration.ofMinutes(4))
                .header("User-Agent", "SkyLauncherSetup").GET().build();
            HttpResponse<Path> res = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(part));
            if (res.statusCode() != 200) throw new IOException("Download returned HTTP " + res.statusCode());
            if (Files.size(part) > 150_000_000L || (a.size() > 0 && Files.size(part) != a.size()))
                throw new IOException("Downloaded file size did not match release metadata");
            if (!a.sha256().isEmpty() && !sha256(part).equalsIgnoreCase(a.sha256()))
                throw new IOException("SHA-256 verification failed");
            installFrom(part, release.tag());
        } finally { Files.deleteIfExists(part); }
    }
    static Path embeddedJar() throws Exception {
        Path temp = Files.createTempFile("SkyLauncher-bundled-", ".jar");
        try (InputStream in = SkyLauncherSetup.class.getResourceAsStream("/bundled/SkyLauncher.jar")) {
            if (in == null) { Files.deleteIfExists(temp); throw new IOException("No bundled copy was included in this installer"); }
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }
    static String javaExe() {
        return Path.of(System.getProperty("java.home"), "bin", os().equals("windows") ? "javaw.exe" : "java").toString();
    }
    static void runLauncher() throws Exception {
        if (!Files.isRegularFile(INSTALLED)) throw new IOException("SkyLauncher isn't installed yet");
        new ProcessBuilder(javaExe(), "-jar", INSTALLED.toString()).directory(DATA.toFile()).start();
    }
    static void onEDT(Runnable r) { SwingUtilities.invokeLater(r); }
    void message(String text) { onEDT(() -> status.setText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>")); }
    void state(boolean working) {
        busy = working;
        onEDT(() -> {
            install.setEnabled(!working); bundled.setEnabled(!working); launch.setEnabled(!working && Files.exists(INSTALLED));
            shortcut.setEnabled(!working);
        });
    }
    void work(String label, Task task) {
        if (busy) return;
        state(true); message(label);
        new Thread(() -> {
            try { task.run(); onEDT(() -> launch.setEnabled(Files.isRegularFile(INSTALLED))); }
            catch (Exception ex) {
                message("Unable to download the latest release: " + ex.getMessage() + ".<br>You can install the bundled version instead.");
            } finally { state(false); }
        }, "SkyLauncher-installer-worker").start();
    }
    interface Task { void run() throws Exception; }
    void update(boolean startup) {
        work("Checking GitHub Releases...", () -> {
            Release release = latest();
            newer = release;
            String installedTag = Files.exists(VERSION_FILE) ? Files.readString(VERSION_FILE).trim() : "";
            if (installedTag.equals(release.tag()) && Files.isRegularFile(INSTALLED)) {
                message("SkyLauncher " + release.tag() + " is up to date.");
                return;
            }
            if (Files.isRegularFile(INSTALLED) && startup) {
                final int[] result = {JOptionPane.NO_OPTION};
                SwingUtilities.invokeAndWait(() -> result[0] = JOptionPane.showConfirmDialog(frame,
                    "SkyLauncher " + release.tag() + " is available. Download and install it now?",
                    "SkyLauncher update available", JOptionPane.YES_NO_OPTION));
                if (result[0] != JOptionPane.YES_OPTION) {
                    message("An update to " + release.tag() + " is available. Click Download latest when ready.");
                    return;
                }
            }
            message("Downloading SkyLauncher " + release.tag() + " from GitHub...");
            download(release);
            message("SkyLauncher " + release.tag() + " installed. You can launch it now.");
            if (!Files.exists(APP_DIR.resolve("shortcut-asked.txt"))) onEDT(this::askShortcut);
        });
    }
    void bundledInstall() {
        work("Installing bundled SkyLauncher...", () -> {
            Path j = embeddedJar();
            try { installFrom(j, "bundled-3.1.2-java"); }
            finally { Files.deleteIfExists(j); }
            message("Bundled SkyLauncher installed. Use Download latest later to update from GitHub.");
            if (!Files.exists(APP_DIR.resolve("shortcut-asked.txt"))) onEDT(this::askShortcut);
        });
    }
    static String shellQuote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    static String psQuote(String s) { return "'" + s.replace("'", "''") + "'"; }
    static Path desktop() throws IOException {
        Path home = Path.of(System.getProperty("user.home"));
        if (os().equals("windows")) {
            for (String n : List.of("OneDrive/Desktop", "Desktop")) if (Files.isDirectory(home.resolve(n))) return home.resolve(n);
        } else if (os().equals("linux")) {
            Path cfg = home.resolve(".config/user-dirs.dirs");
            if (Files.exists(cfg)) for (String s : Files.readAllLines(cfg)) {
                Matcher m = Pattern.compile("^\\s*XDG_DESKTOP_DIR=\"([^\"]+)\"").matcher(s);
                if (m.find()) {
                    Path p = Path.of(m.group(1).replace("$HOME", home.toString()));
                    if (Files.isDirectory(p)) return p;
                }
            }
        }
        for (String n : List.of("Desktop", "Bureaublad", "Bureau", "Schreibtisch"))
            if (Files.isDirectory(home.resolve(n))) return home.resolve(n);
        throw new IOException("Desktop folder not found. Create or configure your Desktop folder first.");
    }
    static Path currentLauncher() throws Exception {
        String p = System.getProperty("jpackage.app-path", "");
        if (!p.isBlank() && Files.exists(Path.of(p))) return Path.of(p);
        // For the portable JAR, Java is invoked with -jar and the jar itself is the target.
        return Path.of(SkyLauncherSetup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    static Path icon(String ext) throws Exception {
        Files.createDirectories(APP_DIR);
        Path p = APP_DIR.resolve("SkyLauncher." + ext);
        try (InputStream in = SkyLauncherSetup.class.getResourceAsStream("/icons/SkyLauncher." + ext)) {
            if (in == null) throw new IOException("Missing " + ext + " icon");
            Files.copy(in, p, StandardCopyOption.REPLACE_EXISTING);
        }
        return p;
    }
    static Path optionalIcon(String ext) throws Exception {
        return SkyLauncherSetup.class.getResource("/icons/SkyLauncher." + ext) == null ? null : icon(ext);
    }
    static void saveBootstrapTarget() throws Exception {
        Path target = currentLauncher();
        boolean jar = target.toString().toLowerCase(Locale.ROOT).endsWith(".jar");
        Files.createDirectories(APP_DIR);
        String body = jar ? javaExe() + "\n-jar\n" + target + "\n" : target + "\n";
        Files.writeString(APP_DIR.resolve("bootstrap-target.txt"), body, StandardCharsets.UTF_8);
    }
    static Path shortcut() throws Exception {
        saveBootstrapTarget();
        Path base = desktop(); Path target = currentLauncher();
        boolean jar = target.toString().toLowerCase(Locale.ROOT).endsWith(".jar");
        String exec = jar ? javaExe() : target.toString();
        List<String> args = jar ? List.of("-jar", target.toString()) : List.of();
        if (os().equals("windows")) {
            Path sc = base.resolve("SkyLauncher.lnk"), ico = optionalIcon("ico");
            StringBuilder s = new StringBuilder();
            s.append("$w=New-Object -ComObject WScript.Shell;$s=$w.CreateShortcut(").append(psQuote(sc.toString())).append(");");
            s.append("$s.TargetPath=").append(psQuote(exec)).append(";");
            s.append("$s.Arguments=").append(psQuote(String.join(" ", args.stream().map(a -> "\"" + a.replace("\"", "\\\"") + "\"").toList()))).append(";");
            if (ico != null) s.append("$s.IconLocation=").append(psQuote(ico + ",0")).append(";");
            s.append("$s.Description='SkyLauncher';$s.Save();");
            String encoded = Base64.getEncoder().encodeToString(s.toString().getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).start();
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException("Windows shortcut creation failed");
            return sc;
        }
        if (os().equals("mac")) {
            Path app = base.resolve("SkyLauncher.app"), mac = app.resolve("Contents/MacOS"), res = app.resolve("Contents/Resources");
            Files.createDirectories(mac); Files.createDirectories(res);
            Path macIcon = optionalIcon("icns");
            if (macIcon != null) Files.copy(macIcon, res.resolve("SkyLauncher.icns"), StandardCopyOption.REPLACE_EXISTING);
            String script = "#!/bin/sh\nexec " + shellQuote(exec);
            for (String arg : args) script += " " + shellQuote(arg);
            Path run = mac.resolve("SkyLauncher");
            Files.writeString(run, script + " \"$@\"\n"); run.toFile().setExecutable(true);
            Files.writeString(app.resolve("Contents/Info.plist"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<plist version=\"1.0\"><dict><key>CFBundleName</key><string>SkyLauncher</string>" +
                "<key>CFBundleExecutable</key><string>SkyLauncher</string>" +
                (macIcon == null ? "" : "<key>CFBundleIconFile</key><string>SkyLauncher</string>") +
                "<key>CFBundlePackageType</key><string>APPL</string></dict></plist>\n");
            return app;
        }
        Path sc = base.resolve("SkyLauncher.desktop"); Path png = optionalIcon("png");
        String command = "\"" + exec.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        for (String a : args) command += " \"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        Files.writeString(sc, "[Desktop Entry]\nType=Application\nName=SkyLauncher\n" +
            "Comment=SkyLauncher (latest GitHub release)\nExec=" + command + "\n" +
            (png == null ? "" : "Icon=" + png + "\n") +
            "Terminal=false\nCategories=Game;\nStartupNotify=true\n");
        sc.toFile().setExecutable(true);
        try { new ProcessBuilder("gio", "set", sc.toString(), "metadata::trusted", "true").start()
            .waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { }
        return sc;
    }
    void askShortcut() {
        int answer = JOptionPane.showConfirmDialog(frame,
            "Create a SkyLauncher desktop shortcut? (Uses your icon if supplied)", "Desktop shortcut",
            JOptionPane.YES_NO_OPTION);
        try {
            if (answer == JOptionPane.YES_OPTION) {
                Path p = shortcut(); message("Shortcut created: " + p);
            }
            Files.createDirectories(APP_DIR);
            Files.writeString(APP_DIR.resolve("shortcut-asked.txt"), "yes\n");
        } catch (Exception e) { JOptionPane.showMessageDialog(frame, e.getMessage(), "Shortcut error", JOptionPane.WARNING_MESSAGE); }
    }
    JButton button(String text, Runnable runnable) {
        JButton b = new JButton(text); b.setBackground(BLUE); b.setForeground(Color.WHITE);
        b.setOpaque(true); b.setFocusPainted(false); b.setBorder(BorderFactory.createEmptyBorder(12, 15, 12, 15));
        b.addActionListener(e -> runnable.run()); return b;
    }
    void show() {
        frame = new JFrame("SkyLauncher — Installer & Updater");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(700, 360); frame.setLocationRelativeTo(null);
        try (InputStream in = getClass().getResourceAsStream("/icons/SkyLauncher.png")) {
            if (in != null) frame.setIconImage(javax.imageio.ImageIO.read(in));
        } catch (Exception ignored) { }
        JPanel p = new JPanel(new BorderLayout(18, 18)); p.setBackground(BACK);
        p.setBorder(BorderFactory.createEmptyBorder(26, 28, 26, 28));
        JLabel title = new JLabel("SkyLauncher"); title.setForeground(Color.WHITE); title.setFont(new Font("SansSerif", Font.BOLD, 28));
        JLabel subtitle = new JLabel("Install the latest version directly from GitHub Releases");
        subtitle.setForeground(new Color(185,185,185));
        JPanel top = new JPanel(new GridLayout(2, 1, 3, 4)); top.setOpaque(false); top.add(title); top.add(subtitle);
        p.add(top, BorderLayout.NORTH);
        status = new JLabel("Checking installation..."); status.setForeground(Color.WHITE); status.setFont(new Font("SansSerif", Font.PLAIN, 15));
        JPanel center = new JPanel(new BorderLayout()); center.setBackground(PANEL);
        center.setBorder(BorderFactory.createEmptyBorder(20, 18, 20, 18)); center.add(status);
        p.add(center, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0)); bottom.setOpaque(false);
        install = button("Download latest", () -> update(false));
        bundled = button("Install bundled version", this::bundledInstall);
        launch = button("Launch SkyLauncher", () -> {
            try { runLauncher(); } catch (Exception e) { JOptionPane.showMessageDialog(frame, e.getMessage()); }
        });
        shortcut = button("Desktop shortcut", this::askShortcut);
        bottom.add(install); bottom.add(bundled); bottom.add(launch); bottom.add(shortcut);
        p.add(bottom, BorderLayout.SOUTH);
        frame.setContentPane(p); frame.setVisible(true);
        try { saveBootstrapTarget(); } catch (Exception e) { message("Could not save launcher target: " + e.getMessage()); }
        launch.setEnabled(Files.isRegularFile(INSTALLED));
        if (Files.isRegularFile(INSTALLED)) message("Installed version: " + localVersion() + ". Checking for updates...");
        else message("First launch: checking GitHub for the latest release...");
        update(true);
    }
    static String localVersion() {
        try { return Files.readString(VERSION_FILE).trim(); } catch (Exception e) { return "unknown"; }
    }
    static void test() throws Exception {
        String fake = "{\"tag_name\":\"v3.2.0\",\"assets\":[" +
            "{\"name\":\"SkyLauncherSetup.jar\",\"browser_download_url\":\"https://github.com/a/b/setup\"}," +
            "{\"name\":\"SkyLauncher.jar\",\"browser_download_url\":\"https://github.com/a/b/sky.jar\"}]}";
        Release r = parseRelease(fake);
        if (!r.tag.equals("v3.2.0") || !r.jar.name.equals("SkyLauncher.jar")) throw new AssertionError("Asset selection test failed");
        if (!os().matches("windows|mac|linux")) throw new AssertionError("OS detection failed");
        Path b = embeddedJar(); try { validateJar(b); } finally { Files.deleteIfExists(b); }
        System.out.println("SkyLauncher installer self-test passed");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) { test(); return; }
        if (args.length > 0 && args[0].equals("--install-bundled")) {
            Path b = embeddedJar(); try { installFrom(b, "bundled-3.1.2-java"); } finally { Files.deleteIfExists(b); }
            System.out.println("Bundled launcher installed: " + INSTALLED); return;
        }
        if (args.length > 0 && args[0].equals("--create-shortcut")) {
            System.out.println(shortcut()); return;
        }
        SwingUtilities.invokeLater(() -> new SkyLauncherSetup().show());
    }
}
