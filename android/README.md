# SkyLauncher Android — prototype

**Prototype, not a fully integrated Minecraft Java Edition launcher.**

This native Android application (Android 8.0+, API 26+) is built as a small
launcher-selection companion. It:
- saves the chosen Minecraft version, mod loader and engine (Fold Craft Launcher or Amethyst);
- recommends Java 8/16/17/21/25 by Minecraft version;
- opens installed external launcher apps where Android exposes them;
- otherwise opens the project's official download/instructions page;
- links to modpack discovery.

**What it does not yet do**
- SkyLauncher does NOT install OpenJDK into its own storage;
- SkyLauncher does NOT automatically push the selected Java/Minecraft/profile into FCL/Amethyst;
- SkyLauncher does NOT launch Minecraft itself, authenticate Microsoft accounts, or install modpacks;
- Java download/management and Minecraft launch remain within the selected external launcher.
  FCL provides a built-in multi-version runtime manager; Amethyst has a JRE manager.

Only download third-party apps from the official projects, check their licenses, and
sign into Minecraft through the selected launcher using an account with a valid entitlement.
No Mojang/Microsoft binaries are redistributed by SkyLauncher.

## Building
From the repository root with Android SDK 35, JDK 17, Gradle 8.10.2:
```sh
gradle -p android assembleDebug
```
The installable debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`.
You can also download the `SkyLauncher-Android-Prototype-APK` artifact from
the Android APK GitHub Actions workflow.

This alpha has its own version 0.1.0-alpha and does not bump the desktop SkyLauncher 3.1.7.
