# SkyLauncher — screenshot-matched Java UI

The previous Swing layout was replaced with a closer visual match to the supplied
GH Mod Launcher screenshot. The Mods tab starts with MODPACK and ADD MOD panels,
followed by SEARCH MODRINTH and INSTALLED MODS in the same scrollable view.
The Activity Log, progress bar, Install / prepare, Stop, Sync to official launcher,
and green PLAY button remain visible while scrolling.

The Settings tab still contains the five grayscale themes and built-in color chooser.
No extra Java UI libraries or icon artwork are included.

## Run locally on Windows

Run `SkyLauncher-Windows-Local-Setup.exe`. It contains the updated Java launcher,
never downloads the launcher during **installation**, and requires Java 17+ installed.
You can also run `java -jar release-ready/SkyLauncher.jar` if Java is already available.
Normal launcher update checks and online Minecraft functionality still use the Internet.

## Installers without Java preinstalled

Push this project to GitHub and use `.github/workflows/build.yml` to build the native
Windows EXE, macOS DMG and Linux DEB (the native packages bundle a Java runtime).

## GitHub Pages

Upload the contents of the repository ZIP to the GitHub repository root. Go to
Settings > Pages > Deploy from a branch > `main` > `/docs`.
The website reads the latest published GitHub Release assets.

No CurseForge integration or app icon is bundled.

## Simplified settings / color pickers (this update)

- Settings now shows Minecraft version, mod loader, RAM and JVM arguments only under Minecraft Settings.
- Less-used settings stay available through **Advanced settings...**; profile data is unchanged.
- Appearance now supports three separate picker + HEX + reset controls: background, text and accent.
- Saved grayscale presets remain; custom background automatically derives complementary surfaces and readable default text.
- Improved everyday button labels in the mods and launch area.
