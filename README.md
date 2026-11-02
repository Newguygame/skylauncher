# SkyLauncher

SkyLauncher helps you play Minecraft with different game versions and mods. It works on Windows, macOS, and Linux.

## Install SkyLauncher

**Windows**

1. Open the [SkyLauncher downloads page](https://github.com/Newguygame/skylauncher/releases).
2. Download the Windows `.exe` file.
3. Open it and follow the steps on screen. You can choose a desktop shortcut and whether to open SkyLauncher when installation finishes.

**Mac**

1. [Download the Mac installer](https://github.com/Newguygame/skylauncher/raw/main/installers/macOS/SkyLauncher-Universal-macOS-Installer.zip).
2. Unzip the file.
3. Double-click `Install-SkyLauncher-Mac.command` and follow the steps.

The installer picks the right version for your Mac.

**Linux**

1. [Download the Linux installer](https://github.com/Newguygame/skylauncher/raw/main/installers/linux/SkyLauncher-Universal-Linux-Installer.zip).
2. Unzip the file and open a terminal in that folder.
3. Run:

   ```bash
   bash Install-SkyLauncher-Linux.sh
   ```

The installer picks the right version for your computer. It works with many Linux systems, including CachyOS, Arch, Fedora, and Ubuntu.

The Mac and Linux installers need an internet connection. If the newest release does not have a download for your computer yet, the installer will tell you.

Want to run SkyLauncher on Linux without installing it? See the [portable Linux guide](LINUX-PORTABLE.md).

## What you can do

- Create separate Minecraft setups.
- Choose your Minecraft version and mod loader.
- Add and manage mods.
- Change how much memory Minecraft uses.
- Change the launcher's colors.

To play Minecraft, sign in with your Microsoft account.

## Your Minecraft files

SkyLauncher keeps your game setups, mods, and settings in a separate folder named `.ghmodlauncher` in your home folder. Installing a new SkyLauncher version does not normally remove these files.

It's a good idea to back up your worlds and settings before making major changes.

## Need help?

[Report a problem or ask a question on GitHub](https://github.com/Newguygame/skylauncher/issues).


## SkyLauncher 3.1.4 release details

The Windows installer, macOS Intel and Apple Silicon DMGs, Linux DEB, and Linux portable archives
are built from the same `src/SkyLauncher.java`. Native packages bundle Java; the portable JAR
requires a compatible Java runtime (Java 17+).

The four supplied wallpapers are packaged from `assets/background-*.jpg`.
Visual Style and the original Terminal Style can be chosen in Settings; generic RAM defaults
apply to all modpacks unless a pack overrides them. CurseForge support uses a shared public
VPS bridge over HTTPS and requires no user token. The API key never belongs in this repository.
CurseForge downloads depend on the VPS being operational and CurseForge permissions.

### Build locally

Run `bash build-local.sh` on a machine with JDK 21. On Windows use
`./build-windows-full-offline.ps1` with JDK 21 and Inno Setup 6. CI builds all platform packages
from GitHub Actions.

### Publishing

The build workflow publishes `v3.1.4` only for a push to `main` with commit title beginning
`Release SkyLauncher 3.1.4`, or on a `v*` tag push. The release job waits for platform builds
and tests to pass. This is the requested exception to the no-version-bump policy.


## Updating SkyLauncher in place

SkyLauncher checks official GitHub Releases on startup. Clicking **Update launcher**
now downloads the matching release asset, checks the SHA-256 digest supplied by GitHub,
exits the running launcher, and starts a small platform updater to replace the previous
application. Windows installed EXEs use the same Inno Setup AppId, macOS app bundles
are swapped with rollback, Linux `.deb` installs use polkit authorization, and portable
JARs are replaced in place. If no matching download or digest is present, the update
will not be offered. Minecraft worlds, modpacks and user settings are never deleted.

On Linux, native `.deb` updating requires `pkexec`; on macOS, an app in a protected
location may require moving it into `~/Applications`.
