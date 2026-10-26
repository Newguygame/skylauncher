SkyLauncher — portable Linux launcher
=====================================
Works on CachyOS / Arch, Ubuntu, Fedora, and many other Linux desktops.
The package does not need root or a distribution-specific package manager.

QUICK START (no installation):
  1. Extract ALL files together into a new directory.
  2. Run ./run.sh
     (Or from a terminal: bash run.sh)

OPTIONAL INSTALL (adds Applications menu item):
  ./install.sh
  Choose whether you want a desktop shortcut when prompted.
  Use ./install.sh --desktop for a shortcut without prompting.

UNINSTALL (only after installing):
  ~/.local/share/skylauncher/uninstall.sh

There are two downloads:
  SkyLauncher-Linux-Portable-x86_64.tar.gz or ...-arm64.tar.gz
     includes a Java 21 runtime for that CPU architecture.
  SkyLauncher-Linux-Portable-Java-Required.tar.gz
     requires Java 17+ installed. On Arch/CachyOS:
       sudo pacman -S jre21-openjdk

Both use the same ~/.ghmodlauncher data folder as the Python edition.
No app-icon artwork is included. Linux uses a standard system games icon.
The launcher itself may connect to GitHub for updates and to Minecraft services
for login/downloads; installation from this archive does not.

The bundled-Java archives are glibc Linux builds. On Alpine (musl) or systems
older than their build environment, use the system-Java archive instead.
