# SkyLauncher portable Linux — quick guide

Use this for **CachyOS, Arch Linux, Fedora, openSUSE, Ubuntu, Mint**, and many
other Linux desktops. It is **not** a `.deb` or `.rpm` package and does not
need root or an external installer app.

## 1. Download the right archive

- `SkyLauncher-Linux-Portable-x86_64.tar.gz` — most desktop PCs, **Java bundled**.
- `SkyLauncher-Linux-Portable-arm64.tar.gz` — Linux ARM64 computers, **Java bundled**.
- `SkyLauncher-Linux-Portable-Java-Required.tar.gz` — smaller, requires system
  Java **17 or newer**; useful on older distributions and Alpine with its
  native Java package.

All files are at the **archive root**, not inside an extra `SkyLauncher` folder.
Extract them into a new folder to avoid mixing them with other files.

## 2. Run directly (no installation)

Example commands for the x64 package:

```bash
mkdir -p ~/Applications/SkyLauncher
# Run these commands from the folder containing the download:
tar -xzf SkyLauncher-Linux-Portable-x86_64.tar.gz -C ~/Applications/SkyLauncher
~/Applications/SkyLauncher/run.sh
```

You can also open a terminal inside the extracted folder and run `./run.sh`.

## 3. Optional install into the Applications menu

From the extracted folder:

```bash
./install.sh
```

This installs into `~/.local/share/skylauncher/`, adds a menu shortcut, and
asks whether you also want a desktop shortcut. It does **not** use `sudo`.

To remove the installed app later:

```bash
~/.local/share/skylauncher/uninstall.sh
```

Your Minecraft mods, Microsoft login information, and profile data stay in
`~/.ghmodlauncher/`, shared with the earlier Python edition. They are never
included in the release tarball or deleted by the portable installer.

## 4. Build on GitHub Actions

Upload the updated project files to the root of
https://github.com/Newguygame/skylauncher (preserve the `.github` folder), then
open **Actions → Build SkyLauncher native offline installers → Run workflow**.

After tests pass, download the artifacts named:

- `SkyLauncher-Portable-Linux-x86_64`
- `SkyLauncher-Portable-Linux-arm64`

Each artifact contains `.tar.gz` archives. Publishing a `v*` tag will also
attach them to a GitHub Release and your GitHub Pages download website will
find them automatically.

**Compatibility:** The x64 bundled-Java release is built on Ubuntu 22.04 for
wider glibc compatibility, while ARM64 is built on Ubuntu 24.04. Linux
variants with another C library (notably Alpine/musl) need compatible system
Java. A green CI result does not prove Minecraft gameplay works on every
Linux distribution. The launcher may connect to GitHub and Minecraft
services when running, but unpacking and installing the archive are offline.
