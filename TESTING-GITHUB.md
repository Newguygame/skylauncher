# SkyLauncher: automatic compatibility testing on GitHub

This repository has two workflows:

- `.github/workflows/test.yml` tests the Java launcher and setup on Windows x64, Ubuntu x64/ARM64, macOS Intel, macOS Apple Silicon, and Fedora and Arch Linux containers; tests website download logic; and smoke-tests a Linux GUI window using a virtual display.
- `.github/workflows/build.yml` now **waits for the full test workflow to pass** before producing the native installers or publishing a tagged GitHub Release.

## Add this to your repository

1. Back up your current repository. Download and extract `SkyLauncher-GitHub-With-Automatic-Tests.zip` (the ZIP has no outer wrapper folder).
2. Copy its contents into the root of `https://github.com/Newguygame/skylauncher`, including the hidden `.github` folder. Use GitHub Desktop or git to preserve the folder structure and commit to `main`.
3. Open GitHub > repository > **Actions** > **Test SkyLauncher compatibility** > **Run workflow** > **Run workflow**.
4. Wait for the jobs. Green means these automated checks passed; red means click the failing job to read the log.
5. Future pushes to main and pull requests will run tests automatically. **Build SkyLauncher native offline installers** runs them first and will not package/publish if they fail.
6. Build installers from Actions, then download them under that run's **Artifacts**. A `v*` tag can trigger publishing after successful verification.

## What passing means (and doesn't)

Passing confirms compilation, Java self-tests, JAR startup, JavaScript release filtering, and the Linux GUI smoke check on the tested runners.
It **does not** confirm Microsoft login, live Minecraft downloads, graphics drivers, actual Minecraft gameplay, mod compatibility, macOS signing/notarization, or working native shortcuts and installers. Those need further integration/manual checks.

- Windows runner uses Windows Server, not a physical Windows 10/11 desktop.
- Fedora and Arch run in containers, not physical desktops.
- All GUI smoke testing here is Linux via Xvfb. macOS desktop GUI launch remains unverified.
- GitHub Actions capacity and artifact availability depend on repository and plan settings. Public repositories normally have free standard GitHub-hosted runners.
- The workflow does not need GitHub or Minecraft credentials or any repository secrets.

## Useful commands for local testing

```bash
javac --release 17 -d build/testclasses src/SkyLauncher.java src/SkyLauncherSetup.java
java -cp build/testclasses SkyLauncher --selftest
# Setup selftest requires an embedded application JAR; package both with:
bash tests/ci-build.sh
node tests/test_logic.js
```

Place optional icons in `icons/` and `docs/` as described in their READMEs. No icon artwork is included.

## Existing Python launcher data

The Java and Python launchers both use the current user's home folder:

- Windows: `%USERPROFILE%\.ghmodlauncher\config.json`
- macOS / Linux: `~/.ghmodlauncher/config.json`
- Instances: `.ghmodlauncher/instances/<profile>/.minecraft/`
- Minecraft runtime: `.ghmodlauncher/runtime/` (unless customized in settings)

The automated self-tests verify the default Java folder and configuration compatibility
using synthetic data, without reading or editing your local SkyLauncher account or mods.
GitHub Actions runs on disposable hosted computers, not on your Windows PC.
Before first using the new Java edition, back up your existing `.ghmodlauncher` folder.
Don't upload that personal folder to GitHub: `config.json` can contain login tokens.

The very old Python v1 location `~/.ghmodlauncher.json` is a *legacy* migration
source for the Python app, not the current shared location. If you still have only
that file, open your current Python SkyLauncher once so it migrates to `config.json`.

## Test the portable Linux builds

Open **Actions → Build SkyLauncher native offline installers → Run workflow**.
After cross-platform verification succeeds, two more jobs appear:

- **Portable Linux (x86_64)** — bundles Java on Ubuntu 22.04, and creates a
  smaller fallback archive that uses system Java.
- **Portable Linux (arm64)** — bundles Java on Ubuntu 24.04 ARM.

Each job tests extraction, Java launch (`--selftest`), a user install, desktop
entry creation and uninstall. Once they pass, get the `.tar.gz` artifacts from
that workflow run. On a pushed `v*` tag they are attached to GitHub Releases
alongside the Windows/macOS installers and Ubuntu `.deb`.

The website's Linux card now recognizes `.tar.gz` packages. The tests do not
verify Minecraft gameplay on CachyOS or every Linux graphics stack.
