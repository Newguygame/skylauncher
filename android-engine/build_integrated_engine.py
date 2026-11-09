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
from patch_direct_launch import patch_engine_startup, patch_auto_installer_return, patch_cacio_bootclasspath
from patch_english import patch_skylauncher_java, force_native_english

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
                                'button("Play Minecraft", true)')
            java = replace(java,
                '''getLauncher.setOnClickListener(v -> {
            if (engine() == 1) confirmAmethystDownload();
            else openUrl(FCL_RELEASES);
        });''',
                '''getLauncher.setOnClickListener(v ->
                startActivity(new Intent(this, net.kdt.pojavlaunch.TestStorageActivity.class)));''')
            java = method(java, "private void refresh() {", '''private void refresh() {
        if (engineSpinner == null || loaderSpinner == null || versionInput == null) return;
        save();
        javaText.setText("Minecraft-game-engine en Java-beheer zijn ingebouwd; "
                + "de engine downloadt de benodigde runtimes en gamebestanden.");
        if (engineSpinner != null) engineSpinner.setVisibility(android.view.View.GONE);
        if (getLauncher != null) {
            getLauncher.setText("Advanced engine settings");
            getLauncher.setVisibility(android.view.View.GONE);
        }
        if (statusText != null) {
            statusText.setText("Deze APK bevat de Minecraft-engine zelf. "
                    + "SkyLauncher gebruikt je profielinstellingen en start Minecraft direct. "
                    + "Dit opent geen andere Android-app.");
        }
    }''')
            java = method(java, "private void openLauncher() {", '''private void openLauncher() {
        save();
        SkyEngineProfileBridge.launch(this);
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
            java = method(java, "static Folder resolve(Context context) {", '''static Folder resolve(Context context) {
        ProfileStore.Profile profile = ProfileStore.active(context);
        try {
            File game = SkyEngineProfileBridge.gameDirectory(context, profile);
            File mods = new File(game, "mods");
            if (!mods.isDirectory() && !mods.mkdirs()) {
                throw new IllegalStateException("Cannot create profile mods folder.");
            }
            return new Folder(null, mods, "SkyLauncher / " + profile.name
                    + " / mods (isolated game directory)", false);
        } catch (Exception error) {
            LogBook.write(context, "Cannot open isolated mod folder: "
                    + error.getClass().getSimpleName());
            return new Folder(null, null, "Profile storage unavailable: "
                    + error.getMessage(), false);
        }
    }''')
        if name == "ModBrowserActivity":
            # Folder picking and automatic probing could select another profile's
            # world/mods. In the bundled build, all mods are inside the current
            # profile's private game folder; remove external folder chooser controls.
            java = replace(java, 'add(root, choose, 8);',
                           'choose.setVisibility(View.GONE);\n        add(root, choose, 8);')
            java = replace(java, 'add(root, auto, 16);',
                           'auto.setVisibility(View.GONE);\n        add(root, auto, 16);')
        (target_dir / (name + ".java")).write_text(java, encoding="utf-8")
    # Native runtime bridge only compiled into combined engine APK.
    shutil.copyfile(sky / "android-engine/SkyEngineProfileBridge.java",
                    target_dir / "SkyEngineProfileBridge.java")
    shutil.copyfile(sky / "android-engine/SkyEngineLoaderInstaller.java",
                    target_dir / "SkyEngineLoaderInstaller.java")
    shutil.copyfile(sky / "android-engine/SkyPackInstaller.java",
                    target_dir / "SkyPackInstaller.java")
    shutil.copyfile(sky / "android-engine/SkyPackUpdateStore.java",
                     target_dir / "SkyPackUpdateStore.java")
    raw_dir = engine / "app_pojavlauncher/src/main/res/raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sky / "android-engine/bridge-cert.pem",
                    raw_dir / "skylauncher_bridge_cert.pem")


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
                 "ModpacksActivity", "ModBrowserActivity", "AccountActivity",
                 "SkyEngineLoaderInstaller"):
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
    text = replace(text, "versionCode 10000000", "versionCode 10")
    text = replace(text, "versionName getVersionName()", 'versionName "0.9.0-engine-alpha"')
    text = replace(text, "minSdkVersion 21", "minSdkVersion 26")
    # androidnsbypass requires API 36.1; compileSdk 37 is not available
    # in the runner's SDK manager. AGP 9 supports minor API levels.
    text = text.replace("compileSdk = 37",
                        "compileSdk { version = release(36) { it.minorApiLevel = 1 } }")

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
    patch_skylauncher_java(upstream / "app_pojavlauncher/src/main/java" / PKG.replace(".", "/"))
    patch_engine_startup(upstream)
    patch_auto_installer_return(upstream)
    patch_cacio_bootclasspath(upstream)
    force_native_english(upstream)
    overlay_manifest(upstream, sky)
    overlay_gradle(upstream)
    print("SkyLauncher home + mods + profiles and native Minecraft engine share one APK.")
    print("Engine start is internal: no external Amethyst Android application needed.")
