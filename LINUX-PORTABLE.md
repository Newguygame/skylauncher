# SkyLauncher for Linux — run without installing

If you prefer an automatic setup, use the [Linux installer](installers/linux/SkyLauncher-Universal-Linux-Installer.zip).

You can also download SkyLauncher and run it from a folder.

## Download

Open [SkyLauncher downloads](https://github.com/Newguygame/skylauncher/releases) and choose:

- **Linux x86_64** — for most Intel and AMD computers.
- **Linux arm64** — for ARM-based computers.

These downloads include Java, so you don't need to install it separately.

If neither version works on your computer, try the download named **Java-Required**. That one needs Java 17 or newer already installed.

## Start SkyLauncher

1. Unpack the `.tar.gz` file into a new folder.
2. Open a terminal in that folder.
3. Run:

   ```bash
   ./run.sh
   ```

## Add SkyLauncher to your Applications menu

From the same folder, run:

```bash
./install.sh
```

You can choose whether to add a desktop shortcut. You do not need an administrator password.

To remove SkyLauncher later, run:

```bash
~/.local/share/skylauncher/uninstall.sh
```

Removing the app leaves your Minecraft worlds, mods, and settings in place.
