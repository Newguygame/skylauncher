package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import java.io.File;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 18, 31);
    private static final int PANEL = Color.rgb(20, 33, 52);
    private static final int WHITE = Color.rgb(238, 248, 255);
    private static final int MUTED = Color.rgb(174, 196, 213);
    private static final int ACCENT = Color.rgb(65, 218, 204);
    private static final String FCL_PACKAGE = "com.tungsten.fcl";
    private static final String AMETHYST_PACKAGE = "org.angelauramc.amethyst";
    private static final String FCL_RELEASES = "https://github.com/FCL-Team/FoldCraftLauncher/releases";
    private static final String AMETHYST_INSTALL = "https://github.com/AngelAuraMC/Amethyst-Android/releases";
    private static final String[] ENGINES = {"Fold Craft Launcher (FCL)", "Amethyst"};
    private static final String[] LOADERS = {"NeoForge", "Fabric", "Forge", "Quilt", "Vanilla"};
    private SharedPreferences prefs;
    private Spinner engineSpinner;
    private Spinner loaderSpinner;
    private EditText versionInput;
    private TextView statusText;
    private TextView javaText;
    private Button getLauncher;
    private File pendingAmethystApk;
    private boolean downloadingAmethyst;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("skylauncher_android", MODE_PRIVATE);
        render();
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable background(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private void add(LinearLayout parent, View view, int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(bottomMargin);
        parent.addView(view, p);
    }

    private LinearLayout section(LinearLayout container) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(background(PANEL, 18));
        add(container, card, 14);
        return card;
    }

    private Button button(String caption, boolean highlight) {
        Button button = new Button(this);
        button.setText(caption);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(highlight ? BG : WHITE);
        button.setBackground(background(highlight ? ACCENT : Color.rgb(43, 62, 84), 12));
        button.setMinHeight(dp(52));
        return button;
    }

    private Spinner spinner(String[] options, int index) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        s.setSelection(Math.max(0, Math.min(options.length - 1, index)));
        s.setPadding(dp(8), dp(2), dp(8), dp(2));
        s.setBackground(background(Color.rgb(38, 56, 77), 12));
        s.setMinimumHeight(dp(48));
        return s;
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        add(root, text("SKYLAUNCHER", 30, WHITE, true), 3);
        add(root, text("Android · launcherkeuze (alpha)", 15, ACCENT, true), 20);

        LinearLayout intro = section(root);
        add(intro, text("Minecraft Java op Android", 20, WHITE, true), 10);
        add(intro, text(
                "Kies een externe Minecraft-launcher. Deze eerste Android-versie bewaart je voorkeuren en opent die launcher; Minecraft en Java draaien in FCL of Amethyst.",
                14, MUTED, false), 2);

        LinearLayout setup = section(root);
        add(setup, text("1  Kies je Minecraft-engine", 17, WHITE, true), 12);
        engineSpinner = spinner(ENGINES, prefs.getInt("engine", 0));
        add(setup, engineSpinner, 14);

        add(setup, text("2  Minecraft-versie", 17, WHITE, true), 10);
        versionInput = new EditText(this);
        versionInput.setSingleLine(true);
        versionInput.setText(prefs.getString("version", "1.21.1"));
        versionInput.setHint("Bijvoorbeeld 1.21.1");
        versionInput.setTextColor(WHITE);
        versionInput.setHintTextColor(MUTED);
        versionInput.setSelectAllOnFocus(true);
        versionInput.setBackground(background(Color.rgb(38, 56, 77), 12));
        versionInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        add(setup, versionInput, 14);

        add(setup, text("3  Modloader", 17, WHITE, true), 10);
        loaderSpinner = spinner(LOADERS, prefs.getInt("loader", 0));
        add(setup, loaderSpinner, 12);

        javaText = text("", 14, ACCENT, true);
        add(setup, javaText, 12);

        Button launch = button("Open gekozen Minecraft-launcher", true);
        launch.setOnClickListener(v -> openLauncher());
        add(setup, launch, 12);
        getLauncher = button("Download / installeer launcher", false);
        getLauncher.setOnClickListener(v -> {
            if (engine() == 1) confirmAmethystDownload();
            else openUrl(FCL_RELEASES);
        });
        add(setup, getLauncher, 10);
        statusText = text("", 13, MUTED, false);
        add(setup, statusText, 2);

        LinearLayout javaSection = section(root);
        add(javaSection, text("Java-runtime", 17, WHITE, true), 8);
        add(javaSection, text(
                "FCL bevat beheer voor Java 8, 17, 21 en 25 en kan benodigde runtimes beheren. Amethyst heeft een JRE-manager. SkyLauncher heeft nog geen eigen Java-downloader.",
                14, MUTED, false), 12);
        Button javaInfo = button("Hoe installeer ik Java?", false);
        javaInfo.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Java op Android")
                .setMessage("Open de gekozen launcher en gebruik daar de ingebouwde Java/JRE-manager. Sommige runtimes worden bij de eerste start gedownload. De keuze en installatie gebeuren op dit moment nog niet vanuit SkyLauncher zelf.")
                .setPositiveButton("Begrepen", null)
                .show());
        add(javaSection, javaInfo, 1);

        LinearLayout mods = section(root);
        add(mods, text("Modpacks", 17, WHITE, true), 8);
        add(mods, text("Vind een modpack en importeer het in de gekozen Minecraft-launcher. Directe SkyLauncher-modpackinstallatie volgt later.", 14, MUTED, false), 10);
        Button modrinth = button("Bekijk Modrinth-modpacks", false);
        modrinth.setOnClickListener(v -> openUrl("https://modrinth.com/modpacks"));
        add(mods, modrinth, 8);
        Button curseforge = button("Bekijk CurseForge-modpacks", false);
        curseforge.setOnClickListener(v -> openUrl("https://www.curseforge.com/minecraft/search?page=1&class=modpacks"));
        add(mods, curseforge, 2);

        add(root, text("Een geldige Minecraft Java-licentie is vereist. Niet verbonden aan Mojang of Microsoft. Alleen officiële downloadpagina's worden geopend.", 12, MUTED, false), 4);
        engineSpinner.setOnItemSelectedListener(selectionListener());
        loaderSpinner.setOnItemSelectedListener(selectionListener());
        versionInput.setOnFocusChangeListener((v, focused) -> {
            if (!focused) refresh();
        });
        setContentView(scroll);
        refresh();
    }

    private AdapterView.OnItemSelectedListener selectionListener() {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
    }

    private int engine() {
        return engineSpinner == null ? 0 : engineSpinner.getSelectedItemPosition();
    }

    private void save() {
        prefs.edit().putInt("engine", engine())
                .putInt("loader", loaderSpinner.getSelectedItemPosition())
                .putString("version", versionInput.getText().toString().trim()).apply();
    }

    private int recommendedJava(String version) {
        try {
            String[] p = version.trim().split("\\.");
            int first = Integer.parseInt(p[0]);
            if (first >= 26) return 25;
            if (first != 1) return 21;
            int minor = Integer.parseInt(p[1]);
            int patch = p.length >= 3 ? Integer.parseInt(p[2]) : 0;
            if (minor > 20 || (minor == 20 && patch >= 5)) return 21;
            if (minor >= 18) return 17;
            if (minor == 17) return 16;
            return 8;
        } catch (Exception e) {
            return 21;
        }
    }

    private boolean isInstalled(String packageName) {
        try {
            return getPackageManager().getLaunchIntentForPackage(packageName) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void refresh() {
        if (engineSpinner == null || loaderSpinner == null || versionInput == null) return;
        save();
        javaText.setText("Aanbevolen: Java " + recommendedJava(versionInput.getText().toString()) + " (instellen in de gekozen launcher)");
        String pkg = engine() == 0 ? FCL_PACKAGE : AMETHYST_PACKAGE;
        if (getLauncher != null) {
            getLauncher.setText(engine() == 1
                    ? "Amethyst automatisch installeren / bijwerken"
                    : "FCL-downloadpagina openen");
            getLauncher.setEnabled(!downloadingAmethyst);
        }
        if (!downloadingAmethyst) {
            statusText.setText(isInstalled(pkg)
                    ? "Launcher gevonden: klaar om te openen. Stel Minecraft en Java in die app in."
                    : "Launcher nog niet geïnstalleerd. " + (engine() == 1
                    ? "Gebruik de knop hierboven om Amethyst automatisch te downloaden."
                    : "Download FCL via de officiële projectpagina."));
        }
    }

    private void openLauncher() {
        save();
        String pkg = engine() == 0 ? FCL_PACKAGE : AMETHYST_PACKAGE;
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) {
            try {
                startActivity(intent);
                return;
            } catch (Exception e) {
                Toast.makeText(this, "Kon de gekozen launcher niet openen.", Toast.LENGTH_LONG).show();
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Minecraft-launcher nog niet gevonden")
                .setMessage("Je moet eerst " + ENGINES[engine()] + " installeren. Android vraagt bij een APK-installatie zelf om toestemming.")
                .setPositiveButton("Officiële downloadpagina", (d, which) ->
                        openUrl(engine() == 0 ? FCL_RELEASES : AMETHYST_INSTALL))
                .setNegativeButton("Annuleren", null)
                .show();
    }


    @Override protected void onResume() {
        super.onResume();
        if (pendingAmethystApk != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls())) {
            File apk = pendingAmethystApk;
            pendingAmethystApk = null;
            launchSystemInstaller(apk);
        } else if (statusText != null && !downloadingAmethyst) {
            refresh();
        }
    }

    private void confirmAmethystDownload() {
        if (downloadingAmethyst) return;
        new AlertDialog.Builder(this)
                .setTitle("Amethyst via GitHub installeren")
                .setMessage("SkyLauncher downloadt Amethyst.apk rechtstreeks vanaf de officiële GitHub Releases van AngelAuraMC. Daarna opent Android zijn eigen installatievenster en moet je zelf toestemming geven. Er worden geen Minecraft-spelbestanden meegeleverd.")
                .setPositiveButton("Download en installeer", (dialog, which) -> startAmethystDownload())
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void startAmethystDownload() {
        if (downloadingAmethyst) return;
        downloadingAmethyst = true;
        refresh();
        statusText.setText("Officiële Amethyst-release ophalen...");
        AmethystInstaller.download(this, new AmethystInstaller.Listener() {
            @Override public void onProgress(String message) {
                if (statusText != null) statusText.setText(message);
            }
            @Override public void onComplete(File apk) {
                downloadingAmethyst = false;
                if (getLauncher != null) getLauncher.setEnabled(true);
                if (statusText != null) statusText.setText("Download voltooid. Android-installatie starten...");
                requestAndroidInstallation(apk);
            }
            @Override public void onError(String message) {
                downloadingAmethyst = false;
                if (getLauncher != null) getLauncher.setEnabled(true);
                if (statusText != null) statusText.setText("Download mislukt: " + message);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Amethyst niet geïnstalleerd")
                        .setMessage(message + "\n\nJe kunt Amethyst ook via de officiële releasepagina downloaden.")
                        .setPositiveButton("Officiële releases", (d, w) -> openUrl(AMETHYST_INSTALL))
                        .setNegativeButton("Sluiten", null)
                        .show();
            }
        });
    }

    private void requestAndroidInstallation(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingAmethystApk = apk;
            new AlertDialog.Builder(this)
                    .setTitle("Installatietoestemming vereist")
                    .setMessage("Android moet toestaan dat SkyLauncher een APK-installatie start. Schakel 'Toestaan vanaf deze bron' in bij de instellingen. Vervolgens opent automatisch het Android-installatievenster.")
                    .setPositiveButton("Open Android-instellingen", (d, w) -> {
                        try {
                            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(settings);
                        } catch (Exception e) {
                            pendingAmethystApk = null;
                            statusText.setText("Open handmatig Android > Apps > SkyLauncher > Onbekende apps installeren.");
                        }
                    })
                    .setNegativeButton("Niet toestaan", (d, w) -> pendingAmethystApk = null)
                    .show();
            return;
        }
        launchSystemInstaller(apk);
    }

    private void launchSystemInstaller(File apk) {
        try {
            AmethystInstaller.openInstaller(this, apk);
            if (statusText != null) statusText.setText("Bevestig de installatie in Android. SkyLauncher installeert niets zonder toestemming.");
        } catch (Exception e) {
            if (statusText != null) statusText.setText("Kon Android-installatie niet openen: " + e.getMessage());
            new AlertDialog.Builder(this)
                    .setTitle("Android kon de APK niet openen")
                    .setMessage("Probeer Amethyst via de officiële GitHub-releasepagina te installeren.")
                    .setPositiveButton("Open releases", (d, w) -> openUrl(AMETHYST_INSTALL))
                    .setNegativeButton("Sluiten", null).show();
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Geen browser gevonden om de pagina te openen.", Toast.LENGTH_LONG).show();
        }
    }
}
