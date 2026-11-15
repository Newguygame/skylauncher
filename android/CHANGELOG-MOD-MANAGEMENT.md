# SkyLauncher Android — mod management changelog

## Unreleased (Android integrated-engine development branch)

- Added **Choose version** for Modrinth search results. Versions are filtered by the active Minecraft version and modloader; release, beta, and alpha channels are labelled.
- Added **Select / change version** for installed JARs recognized by their SHA-512 hash on Modrinth. This also works for manually copied Modrinth JARs.
- Added an in-app **changelog preview** for the selected Modrinth release before installation.
- Automatically resolves and downloads recursively declared **required Modrinth dependencies**, with a bounded dependency tree. Optional and embedded dependencies are not downloaded.
- Verifies Modrinth JAR downloads against their publisher-provided SHA-512 hashes, before modifying the active profile.
- Uses the active Minecraft profile only. Existing unrelated mods and worlds are not deleted.
- Version replacement keeps a copy of the previous JAR under the app's private `modrinth-mod-backups/` directory, with rollback on installation failure.
- Detects conflicting filenames and many duplicate mod IDs. Stops instead of silently replacing pre-existing CurseForge/local mods.
- If an installed mod cannot be matched on Modrinth, the change-version action explains the limitation and does not modify it.
- No change to the desktop SkyLauncher version or the Android application version.

### Limitations
- Automatic dependency resolution currently uses dependencies **declared by Modrinth releases**. CurseForge-only mods and manually supplied ZIP/JARs may need manual dependency handling.
- Required dependencies without a resolvable Modrinth project or version are reported instead of guessed.
- Versions of mods used by a multiplayer server may need to match that server. SkyLauncher cannot guarantee server compatibility merely because the Minecraft version and modloader match.
- The game engine is embedded in the Android app; replacing the whole APK with an APK signed using a different key may be blocked by Android.
