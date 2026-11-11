package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    static final String[] THEMES = {"Fathom turquoise", "Ocean blue", "Rose", "Emerald", "Sunset orange", "Custom"};
    static final int[] COLORS = {0xff45dacc, 0xff509fff, 0xffff74ae, 0xff68e19f, 0xffffac61};
    static final String[] WALLPAPERS = {"Cherry Blossoms", "Moonlit Ruins", "Castle Lake", "Sunset Forest"};
    private final int bg = Color.rgb(10, 20, 33);
    private final int fg = Color.rgb(245, 249, 255);
    private final int card = Color.rgb(25, 43, 63);

    static int accent(SharedPreferences prefs) {
        int idx = prefs.getInt("theme_idx", 0);
        if (idx < COLORS.length && idx >= 0) return COLORS[idx];
        try { return Color.parseColor(prefs.getString("accent_hex", "#45DACC")); }
        catch (Exception e) { return COLORS[0]; }
    }

    static int wallpaper(SharedPreferences prefs) {
        return Math.max(0, Math.min(3, prefs.getInt("wallpaper_idx", 0)));
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color);
        d.setCornerRadius(dp(15)); return d;
    }
    private TextView text(String v, int size) {
        TextView t = new TextView(this); t.setText(v); t.setTextColor(fg); t.setTextSize(size); return t;
    }
    private void add(LinearLayout l, View v, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(margin); l.addView(v, p);
    }
    private Spinner selector(String[] values, int index) {
        Spinner v = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values);
        v.setAdapter(a); v.setSelection(index); return v;
    }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = ProfileStore.prefs(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(20), dp(28), dp(20), dp(26));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        add(root, text("SKYLAUNCHER / INSTELLINGEN", 23), 12);
        add(root, text("Kies een van de desktopstijlen en de originele SkyLauncher-achtergrond.", 14), 20);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(background(card));
        add(root, panel, 12);
        add(panel, text("Kleurenprofiel", 17), 10);
        int previousTheme = Math.max(0, Math.min(THEMES.length - 1, prefs.getInt("theme_idx", 0)));
        Spinner theme = selector(THEMES, previousTheme);
        add(panel, theme, 14);
        add(panel, text("Aangepaste accentkleur (HEX, voorbeeld #45DACC)", 13), 7);
        EditText custom = new EditText(this);
        custom.setSingleLine(true);
        custom.setTextColor(fg);
        custom.setText(prefs.getString("accent_hex", "#45DACC"));
        add(panel, custom, 16);
        add(panel, text("Achtergrond", 17), 9);
        Spinner wallpaper = selector(WALLPAPERS, wallpaper(prefs));
        add(panel, wallpaper, 18);
        add(panel, text("Algemene standaard-RAM (MB)", 16), 7);
        EditText ram = new EditText(this);
        ram.setSingleLine(true); ram.setTextColor(fg);
        ram.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        ram.setText(String.valueOf(prefs.getInt("generic_memory_mb", 4096)));
        add(panel, ram, 10);
        add(panel, text("The built-in engine applies profile RAM on launch. "
                + "Keep enough memory available for Android.", 12), 15);
        add(panel, text("Gameplay memory optimization", 17), 8);
        CheckBox lowMemory = new CheckBox(this);
        lowMemory.setText("Close SkyLauncher UI when starting Minecraft (recommended)");
        lowMemory.setTextColor(fg);
        lowMemory.setChecked(prefs.getBoolean("low_memory_gameplay", true));
        add(panel, lowMemory, 6);
        add(panel, text("Frees the launcher's wallpaper and interface while Minecraft "
                + "runs. The game engine stays active; reopen SkyLauncher from its icon "
                + "after exiting Minecraft. Does not put the entire Android app "
                + "into system deep sleep.", 12), 15);
        Button save = new Button(this);
        save.setAllCaps(false);
        save.setText("Instellingen opslaan");
        save.setTextColor(bg);
        save.setBackground(background(accent(prefs)));
        save.setOnClickListener(v -> {
            String hex = custom.getText().toString().trim();
            int color;
            try { color = Color.parseColor(hex); }
            catch (Exception e) {
                Toast.makeText(this, "Ongeldige hexkleur", Toast.LENGTH_LONG).show();
                return;
            }
            int memory;
            try { memory = Integer.parseInt(ram.getText().toString()); }
            catch (Exception e) { memory = 4096; }
            memory = Math.max(512, Math.min(32768, memory));
            prefs.edit()
                    .putInt("theme_idx", theme.getSelectedItemPosition())
                    .putString("accent_hex", hex)
                    .putInt("wallpaper_idx", wallpaper.getSelectedItemPosition())
                    .putInt("generic_memory_mb", memory)
                    .putBoolean("low_memory_gameplay", lowMemory.isChecked())
                    .apply();
            Toast.makeText(this, "Stijl opgeslagen", Toast.LENGTH_SHORT).show();
            finish();
        });
        add(root, save, 16);
        add(root, text("Scherm- en kleuropties zijn van SkyLauncher zelf. Besturingsinstellingen, Java-runtimes en Minecraft-resolutie blijven voorlopig bij de gekozen Minecraft-engine.", 13), 4);
        setContentView(scroll);
    }
}
