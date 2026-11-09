package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** English modpack browser and real installers for the embedded game engine. */
public final class ModpacksActivity extends Activity {
    private static final int PICK_CURSEFORGE_ZIP = 719;
    private LinearLayout results, curseResults;
    private TextView status, installationStatus, currentProfile, updateStatus;
    private EditText searchBox, curseQuery, relayBox;
    private Button fathomButton, zipButton, curseSearch, updateButton, applyUpdateButton;
    private volatile boolean busy;
    private volatile boolean cancelRequested;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(15));
        return d;
    }

    private void add(LinearLayout parent, View view, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(bottomDp);
        parent.addView(view, p);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setBackground(rounded(0xff45dacc));
        b.setTextColor(0xff07131e);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout section(LinearLayout parent, String title) {
        LinearLayout group = new LinearLayout(this);
        group.setPadding(dp(14), dp(15), dp(14), dp(14));
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(rounded(0xff192c45));
        add(group, text(title, 19, Color.WHITE), 12);
        add(parent, group, 15);
        return group;
    }

    private EditText entry(String hint, String textValue) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(textValue);
        field.setHint(hint);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xffa7bdcf);
        field.setBackground(rounded(0xff243d57));
        field.setPadding(dp(11), dp(12), dp(11), dp(12));
        return field;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff0b1424);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(19), dp(27), dp(19), dp(24));
        scroll.addView(root);
        add(root, text("SKYLAUNCHER / MODPACKS", 24, Color.WHITE), 10);
        currentProfile = text("", 14, 0xff45dacc);
        add(root, currentProfile, 15);

        LinearLayout fathom = section(root, "Fathom Studios Minecraft Pack");
        add(fathom, text("Official Fathom Studios client pack from GitHub. "
                + "Installs the client mods and configuration into the active Minecraft "
                + "profile, and selects Minecraft 1.21.1 / NeoForge. "
                + "Changed files are backed up before replacement.", 14, 0xffc0d1e3), 11);
        fathomButton = button("Download / Update Fathom Studios MC Pack");
        fathomButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Install Fathom Studios MC Pack?")
                .setMessage("This installs the client modpack into the active Minecraft "
                        + "profile, replaces matching config/mod files, and selects "
                        + "Minecraft 1.21.1 / NeoForge. Existing files that are replaced "
                        + "are backed up. Your worlds are not included or deleted.")
                .setPositiveButton("Download & install", (d, w) ->
                        beginTask(p -> SkyPackInstaller.installFathom(this, p)))
                .setNegativeButton("Cancel", null)
                .show());
        add(fathom, fathomButton, 8);

        LinearLayout curse = section(root, "CurseForge Modpacks");
        add(curse, text("Search and install CurseForge packs using your secure "
                + "CurseForge Core API relay. The API key stays on your server, "
                + "not inside the APK. You can also import a CurseForge ZIP "
                + "export with manifest.json and overrides.", 14, 0xffc0d1e3), 12);
        relayBox = entry("HTTPS CurseForge API relay URL",
                SkyPackInstaller.relay(this));
        add(curse, relayBox, 8);
        Button saveRelay = button("Save CurseForge relay");
        saveRelay.setOnClickListener(v -> {
            try {
                SkyPackInstaller.saveRelay(this, relayBox.getText().toString());
                installationStatus.setText("CurseForge relay saved. API calls will use it.");
            } catch (Exception e) {
                installationStatus.setText(e.getMessage());
            }
        });
        add(curse, saveRelay, 12);

        curseQuery = entry("Search CurseForge modpacks", "");
        add(curse, curseQuery, 8);
        curseSearch = button("Search CurseForge");
        curseSearch.setOnClickListener(v -> searchCurseForge());
        add(curse, curseSearch, 12);
        curseResults = new LinearLayout(this);
        curseResults.setOrientation(LinearLayout.VERTICAL);
        add(curse, curseResults, 10);
        zipButton = button("Import CurseForge modpack ZIP");
        zipButton.setOnClickListener(v -> {
            if (busy) return;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("application/zip");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed",
                    "application/octet-stream"});
            startActivityForResult(pick, PICK_CURSEFORGE_ZIP);
        });
        add(curse, zipButton, 8);

        LinearLayout modrinth = section(root, "Modrinth Modpacks");
        add(modrinth, text("Browse modpacks compatible with the current version "
                + "and modloader. Install a verified .mrpack directly into your active profile.",
                14, 0xffc0d1e3), 10);
        searchBox = entry("Search Modrinth modpacks", "");
        add(modrinth, searchBox, 9);
        Button search = button("Search Modrinth");
        search.setOnClickListener(v -> searchModrinth());
        add(modrinth, search, 12);
        status = text("Search for compatible packs.", 13, 0xffc0d1e3);
        add(modrinth, status, 10);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        add(modrinth, results, 10);

        LinearLayout updates = section(root, "Installed Pack Updates");
        add(updates, text("SkyLauncher checks your pack's original source. "
                + "Download an update when a newer release is published. "
                + "Worlds and personal files are preserved; pack replacements are backed up.",
                14, 0xffc0d1e3), 9);
        updateStatus = text("Checking installed modpack...", 14, 0xffc0d1e3);
        add(updates, updateStatus, 9);
        updateButton = button("Check for pack updates");
        updateButton.setOnClickListener(v -> checkPackUpdates());
        add(updates, updateButton, 8);
        applyUpdateButton = button("Install available pack update");
        applyUpdateButton.setEnabled(false);
        add(updates, applyUpdateButton, 8);

        installationStatus = text("Ready to install a pack.", 14, 0xffc0d1e3);
        add(root, installationStatus, 13);
        Button cancel = button("Cancel active pack download");
        cancel.setOnClickListener(v -> {
            cancelRequested = true;
            installationStatus.setText("Cancellation requested. Existing game files are preserved.");
        });
        add(root, cancel, 14);
        setContentView(scroll);
        showCurrentProfile();
        checkPackUpdates();
    }

    private void showCurrentProfile() {
        ProfileStore.Profile p = ProfileStore.active(this);
        if (currentProfile != null) currentProfile.setText("Active profile: " + p.name
                + " | Minecraft " + p.version + " | " + ProfileStore.LOADERS[p.loader]);
    }

    private interface Task {
        void execute(SkyPackInstaller.Progress p) throws Exception;
    }

    private void beginTask(Task task) {
        if (busy) {
            installationStatus.setText("Another installation is running.");
            return;
        }
        busy = true;
        cancelRequested = false;
        fathomButton.setEnabled(false);
        curseSearch.setEnabled(false);
        zipButton.setEnabled(false);
        installationStatus.setText("Preparing modpack...");
        new Thread(() -> {
            SkyPackInstaller.Progress listener = new SkyPackInstaller.Progress() {
                @Override public void update(String message) {
                    runOnUiThread(() -> {
                        if (!isFinishing()) installationStatus.setText(message);
                    });
                }
                @Override public void completed(String message) {
                    runOnUiThread(() -> {
                        if (!isFinishing()) {
                            installationStatus.setText(message);
                            showCurrentProfile();
                        }
                    });
                }
                @Override public void failed(String message) {
                    runOnUiThread(() -> {
                        if (!isFinishing()) installationStatus.setText(message);
                    });
                }
                @Override public boolean cancelled() { return cancelRequested; }
            };
            try {
                task.execute(listener);
            } catch (Exception e) {
                listener.failed("Installation failed: " + (e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()));
                LogBook.write(this, "Pack install failure: "
                        + e.getClass().getSimpleName());
            } finally {
                busy = false;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    fathomButton.setEnabled(true);
                    curseSearch.setEnabled(true);
                    zipButton.setEnabled(true);
                    checkPackUpdates();
                });
            }
        }, "SkyLauncher-Pack-Installer").start();
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_CURSEFORGE_ZIP && result == RESULT_OK && data != null
                && data.getData() != null) {
            Uri selected = data.getData();
            beginTask(p -> SkyPackInstaller.installCurseForgeUri(this, selected, p));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        showCurrentProfile();
    }

    private void checkPackUpdates() {
        if (updateButton == null || busy) return;
        updateButton.setEnabled(false);
        applyUpdateButton.setEnabled(false);
        updateStatus.setText("Checking installed modpack source...");
        final String expectedProfile = ProfileStore.active(this).id;
        new Thread(() -> {
            try {
                SkyPackUpdateStore.Record record = SkyPackUpdateStore.current(this);
                if (record == null) {
                    runOnUiThread(() -> {
                        if (!isFinishing()) updateStatus.setText(
                                "No tracked pack in this profile. Install one first.");
                    });
                    return;
                }
                if ("curseforge-import".equals(record.source)) {
                    runOnUiThread(() -> {
                        if (!isFinishing()) updateStatus.setText(
                                "Imported CurseForge ZIP: install a new export to update.");
                    });
                    return;
                }
                String remote = SkyPackInstaller.remotePackVersion(this, record.source);
                runOnUiThread(() -> {
                    if (isFinishing() || !ProfileStore.active(this).id.equals(expectedProfile))
                        return;
                    if (remote.equals(record.version)) {
                        updateStatus.setText(record.name + " is up to date.");
                    } else {
                        updateStatus.setText("Update available for " + record.name
                                + ". The source published revision " + remote + ".");
                        applyUpdateButton.setEnabled(true);
                        applyUpdateButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle("Update " + record.name + "?")
                                .setMessage("Download the newest pack from its original source "
                                        + "and update only this Minecraft profile. "
                                        + "Your worlds will be preserved.")
                                .setPositiveButton("Update", (d, w) ->
                                        beginTask(p -> SkyPackInstaller.updatePack(
                                                this, record.source, p)))
                                .setNegativeButton("Cancel", null).show());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing()) updateStatus.setText("Update check failed: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()));
                });
            } finally {
                runOnUiThread(() -> {
                    if (!isFinishing()) updateButton.setEnabled(true);
                });
            }
        }, "SkyLauncher-Check-Pack-Updates").start();
    }

    private void searchCurseForge() {
        if (busy) return;
        final String query = curseQuery.getText().toString().trim();
        if (query.length() < 2) {
            installationStatus.setText("Enter at least two characters.");
            return;
        }
        try {
            SkyPackInstaller.saveRelay(this, relayBox.getText().toString());
        } catch (Exception e) {
            installationStatus.setText(e.getMessage());
            return;
        }
        installationStatus.setText("Searching CurseForge...");
        curseResults.removeAllViews();
        new Thread(() -> {
            try {
                List<SkyPackInstaller.CurseProject> projects =
                        SkyPackInstaller.searchCurseForge(this, query);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    installationStatus.setText(projects.size() + " CurseForge packs found.");
                    for (SkyPackInstaller.CurseProject project : projects) {
                        LinearLayout panel = section(curseResults, project.name);
                        add(panel, text(project.summary, 13, 0xffc0d1e3), 9);
                        Button install = button("Install this CurseForge modpack");
                        install.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle("Install " + project.name + "?")
                                .setMessage("The selected modpack will be downloaded "
                                        + "from CurseForge using your API relay and installed "
                                        + "into the current Minecraft profile.")
                                .setPositiveButton("Install", (dialog, which) ->
                                        beginTask(p -> SkyPackInstaller.installCurseProject(
                                                this, project.id, p)))
                                .setNegativeButton("Cancel", null).show());
                        add(panel, install, 4);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing()) installationStatus.setText(
                            "CurseForge search error: " + e.getMessage());
                });
            }
        }, "SkyLauncher-CurseForge-Search").start();
    }

    private void searchModrinth() {
        String query = searchBox.getText().toString().trim();
        ProfileStore.Profile profile = ProfileStore.active(this);
        status.setText("Searching Modrinth...");
        results.removeAllViews();
        new Thread(() -> {
            try {
                List<ModrinthClient.Mod> found = ModrinthClient.searchModpacks(
                        query, profile.version, ProfileStore.LOADERS[profile.loader]);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    status.setText(found.size() + " matching Modrinth packs found.");
                    for (ModrinthClient.Mod m : found) {
                        LinearLayout panel = section(results, m.title);
                        add(panel, text(m.description, 13, 0xffc0d1e3), 9);
                        Button install = button("Install / Update Modrinth pack");
                        install.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle("Install " + m.title + "?")
                                .setMessage("Download the newest .mrpack and required "
                                        + "mods into this profile. Existing replaced files "
                                        + "will be backed up; worlds will be preserved.")
                                .setPositiveButton("Install", (d, w) ->
                                        beginTask(p -> SkyPackInstaller.installModrinthProject(
                                                this, m.id, p)))
                                .setNegativeButton("Cancel", null).show());
                        add(panel, install, 8);
                        Button open = button("Open Modrinth pack details");
                        open.setOnClickListener(v -> startActivity(new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://modrinth.com/modpack/" + m.id))));
                        add(panel, open, 4);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing()) status.setText("Modrinth search failed: " + e.getMessage());
                });
            }
        }, "SkyLauncher-Modrinth-Pack-Search").start();
    }
}
