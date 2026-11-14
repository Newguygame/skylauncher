import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** No external dependencies or UI required; run with -ea. */
public class TestLocalModImport {
    static void check(boolean ok, String explanation) {
        if (!ok) throw new AssertionError(explanation);
    }

    static byte[] jar(String loader) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            String descriptor = loader.equals("neoforge")
                    ? "META-INF/neoforge.mods.toml" : "fabric.mod.json";
            out.putNextEntry(new ZipEntry(descriptor));
            out.write(loader.equals("neoforge")
                    ? "[[mods]]\nmodId=\"local_test\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : "{\"id\":\"local_test\",\"version\":\"1.0.0\",\"schemaVersion\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    static Path archive(Path destination, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return destination;
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("skylauncher-local-import-test-");
        try {
            Path input = temp.resolve("input");
            Path profileA = temp.resolve("profile-a/mods");
            Path profileB = temp.resolve("profile-b/mods");
            Files.createDirectories(input);
            Files.createDirectories(profileA);
            Files.createDirectories(profileB);
            byte[] neo = jar("neoforge");
            byte[] fabric = jar("fabric");
            Files.write(input.resolve("single.jar"), neo);
            Files.write(input.resolve("wrong-loader.jar"), fabric);
            Files.write(input.resolve("broken.jar"), "not really a jar".getBytes());

            Map<String, byte[]> zipEntries = new LinkedHashMap<>();
            zipEntries.put("mods/nested.jar", neo);
            zipEntries.put("mods/single.jar", neo); // duplicate of separately selected jar
            zipEntries.put("../../outside.jar", neo); // path traversal attempt
            zipEntries.put("mods/incompatible.jar", fabric);
            zipEntries.put("docs/readme.txt", "hello".getBytes());
            Path pack = archive(input.resolve("mods.zip"), zipEntries);

            Map<String, Object> first = SkyLauncher.defaultInstance("first", "1.21.1", "neoforge");
            first.put("mods_dir", profileA.toString());
            SkyLauncher.LocalImportResult result = SkyLauncher.importLocalMods(
                    List.of(input.resolve("single.jar"), pack, input.resolve("wrong-loader.jar"), input.resolve("broken.jar")),
                    first, false, message -> System.out.println("[import test] " + message));
            check(result.imported == 2, "Expected two imported NeoForge mods, got " + result.summary());
            check(result.skipped == 1, "Expected duplicate to be skipped");
            check(result.rejected >= 3, "Expected invalid/unsafe/incompatible jars rejected");
            check(Files.exists(profileA.resolve("single.jar")), "Direct jar missing");
            check(Files.exists(profileA.resolve("nested.jar")), "Jar from ZIP missing");
            check(!Files.exists(temp.resolve("outside.jar")), "Unsafe ZIP path escaped mods");
            check(Files.list(profileB).findAny().isEmpty(), "Other profile must remain unchanged");

            // No unintended replacement without overwrite, and tracked mods are always protected.
            byte[] original = Files.readAllBytes(profileA.resolve("single.jar"));
            Files.write(input.resolve("single.jar"), jar("neoforge"));
            SkyLauncher.LocalImportResult repeat = SkyLauncher.importLocalMods(
                    List.of(input.resolve("single.jar")), first, false, message -> {});
            check(repeat.skipped == 1, "Existing jar should be skipped");
            check(Arrays.equals(original, Files.readAllBytes(profileA.resolve("single.jar"))), "Duplicate modified target");
            Map<String, Object> tracked = new LinkedHashMap<>();
            tracked.put("modrinth:test", Map.of("file", "single.jar", "name", "Tracked"));
            first.put("mods", tracked);
            SkyLauncher.LocalImportResult guarded = SkyLauncher.importLocalMods(
                    List.of(input.resolve("single.jar")), first, true, message -> {});
            check(guarded.skipped == 1, "Tracked mod must not be replaced even on overwrite");

            // A separate profile can receive its own independent copy.
            Map<String, Object> second = SkyLauncher.defaultInstance("second", "1.21.1", "neoforge");
            second.put("mods_dir", profileB.toString());
            SkyLauncher.LocalImportResult isolated = SkyLauncher.importLocalMods(
                    List.of(input.resolve("single.jar")), second, false, message -> {});
            check(isolated.imported == 1 && Files.exists(profileB.resolve("single.jar")),
                    "Secondary profile should import independently");
            System.out.println("Local mod import tests passed.");
        } finally {
            SkyLauncher.deleteTree(temp);
        }
    }
}
