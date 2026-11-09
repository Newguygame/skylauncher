#!/usr/bin/env python3
"""Build one SkyLauncher APK with Amethyst's native Android Minecraft runtime.

Source and license of engine: AngelAuraMC/Amethyst-Android, LGPLv3.
Our SkyLauncher UI, profiles, mod manager, and engine share one Android app ID,
private storage, application process and native libraries. No external Amethyst
installation or inter-app file execution is involved.
"""
import argparse
import re
import shutil
from pathlib import Path

PKG = "org.fathomstudios.skylauncher.android"
CLASSES = [
    "MainActivity", "ProfileStore", "ProfilesActivity", "SettingsActivity",
    "ToolsActivity", "LogBook", "ModBrowserActivity", "ModrinthClient",
    "ModFolderManager", "ModpacksActivity", "AccountActivity",
    "MicrosoftLogin", "AndroidUpdater", "AmethystInstaller",
]


def replace(text, before, after):
    if before not in text:
        raise RuntimeError("Expected upstream marker absent: " + before[:95])
    return text.replace(before, after, 1)


def method(text, name, replacement):
    i = text.find(name)
    if i < 0: raise RuntimeError("Missing method " + name)
    start = text.index("{", i)
    depth, k = 1, start + 1
    while depth and k < len(text):
        if text[k] == "{": depth += 1
        if text[k] == "}": depth -= 1
        k += 1
    if depth: raise RuntimeError("Unclosed Java method " + name)
    return text[:i] + replacement + text[k:]


def overlay_java(engine, sky):
    source_dir = sky / "android/app/src/main/java" / PKG.replace(".", "/")
    target_dir = engine / "app_pojavlauncher/src/main/java" / PKG.replace(".", "/")
    target_dir.mkdir(parents=True, exist_ok=True)
    for name in CLASSES:
        java = (source_dir / (name + ".java")).read_text(encoding="utf-8")
        if "R." in java:
            java = replace(java, "package " + PKG + ";\n",
                           "package " + PKG + ";\nimport net.kdt.pojavlaunch.R;\n")
        if name == "MainActivity":
            java = replace(java,
                'private static final String[] ENGINES = {"Fold Craft Launcher (FCL)", "Amethyst"};',
                'private static final String[] ENGINES = {"SkyLauncher Engine (geïntegreerd)"};')
            java = java.replace('button("Open gekozen Minecraft-launcher", true)',
                                'button("Speel Minecraft via SkyLauncher Engine", true)')
            java = replace(java,
                '''getLauncher.setOnClickListener(v -> {
            if (engine() == 1) confirmAmethystDownload();
            else openUrl(FCL_RELEASES);
        });''',
                '''getLauncher.setOnClickListener(v -> openLauncher());''')
            java = method(java, "private void refresh() {", '''private void refresh() {
        if (engineSpinner == null || loaderSpinner == null || versionInput == null) return;
        save();
        javaText.setText("Minecraft-game-engine en Java-beheer zijn ingebouwd; "
                + "de engine downloadt de benodigde runtimes en gamebestanden.");
        if (getLauncher != null) {
            getLauncher.setText("Open geïntegreerde Minecraft-engine");
            getLauncher.setEnabled(true);
        }
        if (statusText != null) {
            statusText.setText("Deze APK bevat de Minecraft-engine zelf. "
                    + "Stel je Minecraft-account en gameversie in het enginescherm in. "
                    + "Dit opent geen andere Android-app.");
        }
    }''')
            java = method(java, "private void openLauncher() {", '''private void openLauncher() {
        save();
        LogBook.write(this, "SkyLauncher geïntegreerde Minecraft-engine gestart");
        startActivity(new Intent(this, net.kdt.pojavlaunch.TestStorageActivity.class));
    }''')
            java = java.replace(
                "Een geïntegreerde Minecraft-engine is nog in ontwikkeling; voorlopig start de game via FCL of Amethyst.",
                "De Minecraft-engine is geïntegreerd in deze APK; geen extra launcher nodig.")
            java = java.replace(
                "de game-engine is voorlopig nog extern (FCL of Amethyst).",
                "de game-engine is ingebouwd.")
        if name == "ProfileStore":
            java = replace(java,
                'static final String[] ENGINES = {"Fold Craft Launcher (FCL)", "Amethyst"};',
                'static final String[] ENGINES = {"SkyLauncher Engine (ingebouwd)"};')
            java = java.replace('first.engine = p.getInt("engine", 0);', 'first.engine = 0;')
            java = java.replace('first.engine = p.getInt("engine", 1);', 'first.engine = 0;')
        if name == "ModFolderManager":
            block = '''        // No sandbox bypass: this game engine lives inside this very APK.
        try {
            net.kdt.pojavlaunch.Tools.initStorageConstants(context);
            net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.load();
            net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile selected =
                    net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.getCurrentProfile();
            File mods = new File(net.kdt.pojavlaunch.Tools.getGameDirPath(selected), "mods");
            if (mods.isDirectory() || mods.mkdirs()) {
                return new Folder(null, mods, "Ingebouwde Minecraft-engine: " +
                        mods.getAbsolutePath(), true);
            }
        } catch (Exception ignored) {
            // Game setup/profile may not exist yet; use own per-profile fallback.
        }
'''
            java = replace(java,
                "        // Always-available fallback: this app owns its own profile's mods directory.",
                block + "        // Always-available fallback: this app owns its own profile's mods directory.")
        (target_dir / (name + ".java")).write_text(java, encoding="utf-8")


def overlay_manifest(engine, sky):
    manifest_file = engine / "app_pojavlauncher/src/main/AndroidManifest.xml"
    text = manifest_file.read_text(encoding="utf-8")
    pattern = r'(<activity\b(?=[^>]*android:name="\.TestStorageActivity")[^>]*>).*?</activity>'
    replacement = re.sub(pattern, lambda m: m.group(1).replace(
        'android:exported="true"', 'android:exported="false"') + "</activity>",
        text, count=1, flags=re.DOTALL)
    if replacement == text: raise RuntimeError("Upstream launcher entry changed")
    text = replacement
    extra = '''
        <!-- SkyLauncher is the single application entrypoint. -->
        <activity android:name="org.fathomstudios.skylauncher.android.MainActivity"
            android:exported="true" android:label="SkyLauncher">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
'''
    for name in ("ProfilesActivity", "SettingsActivity", "ToolsActivity",
                 "ModpacksActivity", "ModBrowserActivity", "AccountActivity"):
        extra += ('        <activity android:name="' + PKG + "." + name +
                  '" android:exported="false"/>\n')
    extra += '''        <provider android:name="androidx.core.content.FileProvider"
            android:authorities="org.fathomstudios.skylauncher.android.fileprovider"
            android:grantUriPermissions="true" android:exported="false">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths"/>
        </provider>
'''
    text = replace(text, "</application>", extra + "</application>")
    text = replace(text, 'android:icon="@mipmap/ic_launcher"',
                   'android:icon="@drawable/skylauncher_desktop"')
    text = text.replace('android:roundIcon="@mipmap/ic_launcher_round"',
                        'android:roundIcon="@drawable/skylauncher_desktop"')
    text = text.replace('android:allowBackup="true"', 'android:allowBackup="false"')
    manifest_file.write_text(text, encoding="utf-8")

    xml_dir = engine / "app_pojavlauncher/src/main/res/xml"
    xml_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sky / "android/app/src/main/res/xml/file_paths.xml",
                    xml_dir / "file_paths.xml")
    drawable = engine / "app_pojavlauncher/src/main/res/drawable-nodpi"
    drawable.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sky / "icons/SkyLauncher.png", drawable / "skylauncher_desktop.png")
    for i in range(1, 5):
        shutil.copyfile(sky / ("assets/background-" + str(i) + ".jpg"),
                        drawable / ("skylauncher_bg_" + str(i) + ".jpg"))


def overlay_gradle(engine):
    path = engine / "app_pojavlauncher/build.gradle"
    text = path.read_text(encoding="utf-8")
    text = replace(text, 'applicationId "org.angelauramc.amethyst"',
                   'applicationId "' + PKG + '"')
    text = replace(text, "versionCode 10000000", "versionCode 6")
    text = replace(text, "versionName getVersionName()", 'versionName "0.5.0-engine-alpha"')
    text = replace(text, "minSdkVersion 21", "minSdkVersion 26")
    text = replace(text, "compileSdk = 37", "compileSdk = 36")
    text = replace(text, "applicationIdSuffix '.debug'",
                   "// Keep the same package identity for SkyLauncher Android")
    text = text.replace("org.angelauramc.amethyst", PKG)
    text = text.replace('"Amethyst (Debug)"', '"SkyLauncher Engine (Debug)"')
    text = text.replace('"Amethyst"', '"SkyLauncher Engine"')
    text = replace(text, "dependencies {",
                   "dependencies {\n    implementation 'androidx.documentfile:documentfile:1.1.0'")
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--skylauncher", type=Path, required=True)
    args = parser.parse_args()
    upstream, sky = args.upstream.resolve(), args.skylauncher.resolve()
    if not (upstream / "app_pojavlauncher/build.gradle").is_file():
        raise SystemExit("Amethyst engine checkout ontbreekt")
    overlay_java(upstream, sky)
    overlay_manifest(upstream, sky)
    overlay_gradle(upstream)
    print("SkyLauncher home + mods + profiles and native Minecraft engine share one APK.")
    print("Engine start is internal: no external Amethyst Android application needed.")
