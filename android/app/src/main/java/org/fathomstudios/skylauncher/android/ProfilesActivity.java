package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

public final class ProfilesActivity extends Activity {
    private final int bg = Color.rgb(11, 20, 32);
    private final int panel = Color.rgb(27, 41, 63);
    private final int fg = Color.rgb(242, 247, 255);
    private final int accent = Color.rgb(67, 217, 208);
    private LinearLayout root;

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color);
        d.setCornerRadius(dp(14)); return d;
    }
    private TextView text(String str, int sp) {
        TextView t = new TextView(this); t.setText(str); t.setTextColor(fg);
        t.setTextSize(sp); return t;
    }
    private Button btn(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setTextColor(bg); b.setBackground(rounded(accent)); return b;
    }
    private void add(LinearLayout parent, View view, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(bottom); parent.addView(view, p);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        draw();
    }

    private void draw() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(25));
        scroll.addView(root);
        TextView title = text("SKYLAUNCHER  /  PROFIELEN", 23);
        title.setTypeface(null, Typeface.BOLD);
        add(root, title, 9);
        add(root, text("Maak je eigen Minecraft-profielen en kies Minecraft-versie, modloader en standaard RAM. De externe game-engine heeft nog aparte profielen.", 14), 17);

        Button create = btn("Nieuw profiel");
        create.setOnClickListener(v -> edit(null));
        add(root, create, 15);

        ProfileStore.Profile active = ProfileStore.active(this);
        List<ProfileStore.Profile> profiles = ProfileStore.all(this);
        for (ProfileStore.Profile p : profiles) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(15), dp(15), dp(15), dp(15));
            card.setBackground(rounded(panel));
            TextView name = text(p.name + (p.id.equals(active.id) ? "  ✓ actief" : ""), 19);
            name.setTypeface(null, Typeface.BOLD);
            add(card, name, 8);
            add(card, text(p.version + " · " + ProfileStore.LOADERS[p.loader]
                    + " · " + ProfileStore.ENGINES[p.engine]
                    + " · " + p.ramMb + " MB", 13), 12);
            Button select = btn(p.id.equals(active.id) ? "Dit profiel is actief" : "Selecteren");
            select.setEnabled(!p.id.equals(active.id));
            select.setOnClickListener(v -> { ProfileStore.setActive(this, p.id); LogBook.write(this, "Profiel geselecteerd: " + p.name); draw(); });
            add(card, select, 7);
            Button edit = btn("Bewerken");
            edit.setOnClickListener(v -> edit(p));
            add(card, edit, 7);
            if (profiles.size() > 1) {
                Button delete = btn("Verwijderen");
                delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Profiel verwijderen")
                        .setMessage("Alleen de SkyLauncher-profielinstellingen worden verwijderd. Minecraft-werelden en mods blijven behouden.")
                        .setPositiveButton("Verwijderen", (d, which) -> {
                            ProfileStore.delete(this, p.id);
                            draw();
                        }).setNegativeButton("Annuleren", null).show());
                add(card, delete, 2);
            }
            add(root, card, 12);
        }
        setContentView(scroll);
    }

    private Spinner choose(String[] options, int selected) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, options);
        s.setAdapter(adapter);
        s.setSelection(Math.max(0, Math.min(selected, options.length - 1)));
        return s;
    }

    private EditText field(String value, String hint, boolean numeric) {
        EditText v = new EditText(this);
        v.setSingleLine(true);
        v.setText(value);
        v.setHint(hint);
        v.setTextColor(fg);
        v.setHintTextColor(Color.LTGRAY);
        if (numeric) v.setInputType(InputType.TYPE_CLASS_NUMBER);
        return v;
    }

    private void edit(ProfileStore.Profile profile) {
        ProfileStore.Profile base = profile == null
                ? ProfileStore.active(this) : profile;
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(18), dp(8), dp(18), dp(6));
        form.setOrientation(LinearLayout.VERTICAL);

        EditText name = field(profile == null ? "" : profile.name, "Profielnaam", false);
        add(form, name, 8);
        EditText version = field(base.version, "Minecraft-versie (bijv. 1.21.1)", false);
        add(form, version, 8);
        add(form, text("Modloader", 14), 5);
        Spinner loader = choose(ProfileStore.LOADERS, base.loader);
        add(form, loader, 10);
        add(form, text("Launcher-engine", 14), 5);
        Spinner engine = choose(ProfileStore.ENGINES, base.engine);
        add(form, engine, 10);
        EditText ram = field(String.valueOf(base.ramMb), "Geheugen (MB)", true);
        add(form, ram, 6);
        new AlertDialog.Builder(this)
                .setTitle(profile == null ? "Nieuw Minecraft-profiel" : "Profiel bewerken")
                .setView(form)
                .setPositiveButton("Opslaan", (d, which) -> {
                    String nm = name.getText().toString().trim();
                    String ver = version.getText().toString().trim();
                    int mb;
                    try { mb = Integer.parseInt(ram.getText().toString().trim()); }
                    catch (Exception e) { mb = 4096; }
                    mb = Math.max(512, Math.min(32768, mb));
                    if (nm.isEmpty()) nm = "Minecraft-profiel";
                    if (ver.isEmpty()) ver = "1.21.1";
                    ProfileStore.Profile updated = profile == null
                            ? ProfileStore.create(nm, ver, loader.getSelectedItemPosition(),
                            engine.getSelectedItemPosition(), mb) : profile;
                    updated.name = nm;
                    if (!ver.equals(updated.version)
                            || updated.loader != loader.getSelectedItemPosition())
                        updated.loaderVersion = "";
                    updated.version = ver;
                    updated.loader = loader.getSelectedItemPosition();
                    updated.engine = engine.getSelectedItemPosition();
                    updated.ramMb = mb;
                    ProfileStore.upsert(this, updated);
                    LogBook.write(this, "Profiel opgeslagen: " + updated.name);
                    if (profile == null) ProfileStore.setActive(this, updated.id);
                    draw();
                })
                .setNegativeButton("Annuleren", null).show();
    }
}
