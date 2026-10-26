# Optional SkyLauncher icons — add your own files here

There are **no artwork files** included in this project. To use your own app icon, add:

- `SkyLauncher.png` — PNG, ideally 1024 × 1024 (Linux app, Java window, website source)
- `SkyLauncher.ico` — Windows icon containing multiple resolutions
- `SkyLauncher.icns` — macOS app icon

The Java JARs and installers work without any of these. Only supplied files get embedded during **new builds**.

For the GitHub Pages website, separately place your own `icon.png` directly inside `docs/` (same filename); otherwise the website displays an empty logo slot.

To change the icon used by an already compiled installer, rebuild it after adding icon files.
