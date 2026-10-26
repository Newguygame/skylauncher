# SkyLauncher — separate universal installers for macOS and Linux

These are **two different installers**. Both are small bootstrap ZIPs, not Minecraft launcher distributions. They fetch the **latest published GitHub Release** from this repository and automatically install the right SkyLauncher package. Installation proceeds in one script; the user does **not** have to open the downloaded DMG or archive manually.

## Download

- **[Universal macOS Installer ZIP](macOS/SkyLauncher-Universal-macOS-Installer.zip)** — supports Intel x64 and Apple Silicon ARM64 Macs.
- **[Universal Linux Installer ZIP](linux/SkyLauncher-Universal-Linux-Installer.zip)** — supports x86_64/AMD64 and ARM64/aarch64 Linux.

Download the relevant ZIP from GitHub (open the ZIP file and choose **Download raw file**).

## macOS

Unzip and double-click `Install-SkyLauncher-Mac.command`. If macOS prevents opening it directly, open Terminal in the extracted directory and run:

```bash
bash Install-SkyLauncher-Mac.command
```

The script detects the hardware CPU architecture (including Apple Silicon running a Rosetta terminal), selects the architecture-matched `.dmg` asset from the latest release, downloads and mounts it, copies `SkyLauncher.app` into `~/Applications`, and ejects the DMG. It can offer to make a desktop shortcut and launch the app. No preinstalled Java or sudo required; Apple may require approval for an unsigned/unnotarized app.

## Linux

Unzip and run:

```bash
bash Install-SkyLauncher-Linux.sh
```

The script detects x86_64 or ARM64, downloads the appropriate `SkyLauncher-Linux-Portable-*.tar.gz` **with Java bundled**, extracts it, invokes the package's `install.sh`, creates an Applications-menu entry under the current user, and optionally makes a desktop shortcut or launches SkyLauncher. No preinstalled Java or sudo required on supported glibc distributions such as Arch/CachyOS, Fedora, Debian and Ubuntu.

## Requirements and limitations

- Both installers need `curl` and a working Internet connection, and the **latest release must already contain the corresponding architecture-specific assets**. If those assets do not exist, the installer stops with a clear error; it does not install an older or wrong-architecture version.
- Linux also needs `tar`; the installers use standard shell and OS utilities.
- Neither installer updates or deletes the existing Minecraft data at `~/.ghmodlauncher`.
- The native packages are **not re-uploaded here**; these ZIPs are small installers that download them on demand.
- Check the latest release without installing: `bash Install-SkyLauncher-Mac.command --check-only` or `bash Install-SkyLauncher-Linux.sh --check-only`.
- Linux and macOS installers have passed automated architecture-selection and mocked end-to-end installation tests. Real-world macOS installation is not yet tested on a physical Mac.
- The Windows installer and launcher UI are not affected.

The bootstrap flow is: **run one ZIP's script → detect OS/CPU → fetch official release asset → install SkyLauncher → optional shortcut / launch**.

