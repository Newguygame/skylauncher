# SkyLauncher independent Android engine

**Development branch: do not publish this APK or link it as a stable download.**

This project is a clean Android build from the Fathom Studios SkyLauncher repository.
It neither checks out nor links Amethyst, PojavLauncher, Fold Craft Launcher,
their patched engine libraries, their Activities, or their profile storage.
The only platform dependencies at this stage are Android SDK / Java platform APIs.

## Implemented

- Fully standalone Android APK project.
- Independent SkyLauncher UI and profile storage.
- Separate per-profile game data, including directories for worlds/mods.
- Direct Mojang version manifest, game client, library and asset downloads.
- Official SHA-1 integrity checks, path safety checks, HTTPS allowlist,
  download size limits, cancellation and atomic file writes.
- Native SkyLauncher modloader initializer using official Fabric, Quilt, Forge and
  NeoForge sources; Fabric/Quilt descriptors are written by SkyLauncher.
- Official Forge/NeoForge installer JARs are checksum-verified and staged.
- The development test APK uses a different application ID, so it can be installed
  alongside the existing Android launcher without touching its user data.
- Independent Android CI. CI artifacts are for chat testing only.

## Not implemented — game is NOT playable yet

- Android-compatible Java 21 runtime installation/packaging.
- JNI JVM startup and lifecycle.
- LWJGL/GLFW/OpenGL compatibility for Android graphics and input.
- Microsoft/Minecraft authentication and token handling.
- Library classpath/Java arguments, asset preparation for actual launch.
- Forge / NeoForge official installer execution (official installer JARs are
  prepared, but not executed without the independent Java runner).
- Fabric / Quilt classpath resolution and actual game launch.
- Minecraft splash/loading, sound and touchscreen controls.

Simply embedding a desktop Minecraft JAR in an Android APK does not make it
executable on Android. The native graphics, input, sound and Java ABI
compatibility layer must be built and tested before enabling "Play".

Vanilla client game files are downloaded from Mojang's official HTTPS hosts;
the actual Minecraft Java license and Microsoft login will be enforced
when launch support is implemented.

Desktop SkyLauncher release versions are unchanged. The existing public
experimental Android release is unrelated and must not be republished for
testing changes on this branch.
