package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
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

public final class ModBrowserActivity extends Activity {
    private static final int REQUEST_FOLDER = 42;
    private final int bg = Color.rgb(11, 20, 32);
    private final int panel = Color.rgb(25, 39, 59);
    private final int white = Color.rgb(239, 246, 255);
    private final int muted = Color.rgb(175, 197, 216);
    private final int teal = Color.rgb(70, 218, 207);
    private LinearLayout root;
    private LinearLayout results;
    private TextView status;
    private TextView folderStatus;
    private EditText query;
    private SharedPreferences prefs;
    private Uri modsFolder;
    private String gameVersion;
    private String loader;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("skylauncher_android", MODE_PRIVATE);
        gameVersion = prefs.getString("version", "1.21.1");
        String[] loaders = {"NeoForge", "Fabric", "Forge", "Quilt", "Vanilla"};
        int idx = Math.max(0, Math.min(loaders.length - 1, prefs.getInt("loader", 0)));
        loader = loaders[idx].toLowerCase(java.util.Locale.ROOT);
        String saved = prefs.getString("mods_folder", "");
        if (!saved.isEmpty()) {
            try { modsFolder = Uri.parse(saved); } catch (Exception ignored) { }
        }
        setup();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(15));
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(margin);
        parent.addView(child, lp);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(bg);
        b.setBackground(rounded(teal));
        b.setMinHeight(dp(48));
        return b;
    }

    private void setup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        root = new LinearLayout(this);
        root.setPadding(dp(18), dp(26), dp(18), dp(24));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        add(root, text("SKYLAUNCHER  /  MODS", 22, white, true), 10);
        add(root, text("Modrinth · Minecraft " + gameVersion + " · " + loader,
                14, teal, true), 18);
        add(root, text("Modbestanden worden direct in de map opgeslagen die je hieronder kiest. "
                + "Kies de echte mods-map van je Minecraft-profiel. Door Android-beperkingen is "
                + "Android/data bij veel launchers niet selecteerbaar.", 14, muted, false), 12);

        folderStatus = text("", 13, white, false);
        add(root, folderStatus, 10);
        Button choose = button("Kies Minecraft mods-map");
        choose.setOnClickListener(v -> chooseFolder());
        add(root, choose, 16);

        query = new EditText(this);
        query.setTextColor(white);
        query.setHintTextColor(muted);
        query.setSingleLine(true);
        query.setTextSize(16);
        query.setHint("Zoek mods, bijvoorbeeld Create");
        query.setPadding(dp(14), dp(12), dp(14), dp(12));
        query.setBackground(rounded(panel));
        add(root, query, 8);
        Button search = button("Zoek compatibele mods");
        search.setOnClickListener(v -> search());
        add(root, search, 13);

        status = text("Zoek op naam en installeer een mod.", 13, muted, false);
        add(root, status, 12);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        add(root, results, 12);

        add(root, text("De modzoeker gebruikt de officiële Modrinth API. "
                + "Aanvullende vereiste mods (dependencies) worden nog niet automatisch geïnstalleerd. "
                + "Mods in een verkeerde of niet-toegankelijke map zijn niet zichtbaar in Minecraft.",
                12, muted, false), 1);
        setContentView(scroll);
        refreshFolder();
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri folder = data.getData();
            if (folder == null) return;
            try {
                getContentResolver().takePersistableUriPermission(folder,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                modsFolder = folder;
                prefs.edit().putString("mods_folder", folder.toString()).apply();
                refreshFolder();
            } catch (SecurityException error) {
                status.setText("Geen toegang tot de gekozen map: " + error.getMessage());
            }
        }
    }

    private void refreshFolder() {
        folderStatus.setText(modsFolder == null
                ? "Nog geen mods-map geselecteerd."
                : "Mods-map geselecteerd (documentmap met schrijftoegang).");
    }

    private void search() {
        String term = query.getText().toString().trim();
        if (term.length() < 2) {
            status.setText("Voer minimaal twee tekens in.");
            return;
        }
        status.setText("Mods zoeken...");
        results.removeAllViews();
        new Thread(() -> {
            try {
                List<ModrinthClient.Mod> mods = ModrinthClient.search(term, gameVersion, loader);
                runOnUiThread(() -> show(mods));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Zoekfout: " + e.getMessage()));
            }
        }, "Modrinth-Search").start();
    }

    private void show(List<ModrinthClient.Mod> mods) {
        results.removeAllViews();
        status.setText(mods.size() + " passende mods gevonden.");
        for (ModrinthClient.Mod mod : mods) {
            LinearLayout card = new LinearLayout(this);
            card.setBackground(rounded(panel));
            card.setPadding(dp(14), dp(13), dp(14), dp(13));
            card.setOrientation(LinearLayout.VERTICAL);
            add(card, text(mod.title, 17, white, true), 4);
            add(card, text(mod.description, 13, muted, false), 8);
            Button install = button("Installeer " + mod.title);
            install.setOnClickListener(v -> promptInstall(mod));
            add(card, install, 1);
            add(results, card, 10);
        }
    }

    private void promptInstall(ModrinthClient.Mod mod) {
        if (modsFolder == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Kies eerst de mods-map")
                    .setMessage("Om de mod direct in de Minecraft-installatie te plaatsen, moet je eerst de mods-map van je spelprofiel selecteren.")
                    .setPositiveButton("Kies map", (dialog, which) -> chooseFolder())
                    .setNegativeButton("Sluiten", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(mod.title + " installeren?")
                .setMessage("SkyLauncher downloadt de nieuwste passende .jar van Modrinth voor "
                        + gameVersion + " / " + loader
                        + " en schrijft hem naar de geselecteerde mods-map. "
                        + "Vereiste afhankelijkheden worden nog niet automatisch geïnstalleerd.")
                .setPositiveButton("Installeer", (d, w) -> install(mod))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void install(ModrinthClient.Mod mod) {
        status.setText(mod.title + " downloaden en controleren...");
        new Thread(() -> {
            try {
                ModrinthClient.install(this, modsFolder, mod, gameVersion, loader);
                runOnUiThread(() -> {
                    status.setText(mod.title + " geïnstalleerd in de gekozen mods-map.");
                    new AlertDialog.Builder(this)
                            .setTitle("Mod geïnstalleerd")
                            .setMessage(mod.title + " staat in de gekozen mods-map. "
                                    + "Start Minecraft met dezelfde Minecraft-versie en modloader; "
                                    + "installeer eventueel ook vereiste dependencies.")
                            .setPositiveButton("OK", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Installatie mislukt")
                        .setMessage(e.getMessage())
                        .setPositiveButton("OK", null)
                        .show());
            }
        }, "Modrinth-Install").start();
    }
}
