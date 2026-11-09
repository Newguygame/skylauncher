package org.fathomstudios.skylauncher.android;

import android.app.Activity;
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
import java.util.Locale;

public final class ModpacksActivity extends Activity {
    private LinearLayout results;
    private TextView status;
    private EditText searchBox;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable rounded(int c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(c);
        d.setCornerRadius(dp(15)); return d;
    }
    private void add(LinearLayout l, View v, int space) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin = dp(space); l.addView(v,p);
    }
    private TextView text(String val,int size,int color) {
        TextView t = new TextView(this); t.setText(val); t.setTextSize(size);
        t.setTextColor(color); return t;
    }
    private Button btn(String str) {
        Button b = new Button(this); b.setText(str); b.setAllCaps(false);
        b.setBackground(rounded(0xff45dacc)); b.setTextColor(0xff07131e); return b;
    }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff0b1424);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(dp(19), dp(27), dp(19), dp(24));
        scroll.addView(root);
        ProfileStore.Profile profile = ProfileStore.active(this);
        add(root,text("SKYLAUNCHER / MODPACKS",23,Color.WHITE),12);
        add(root,text(profile.name + " · Minecraft " + profile.version + " · " +
                ProfileStore.LOADERS[profile.loader],14,0xff45dacc),15);
        add(root,text("Zoek modpacks op Modrinth die overeenkomen met je Minecraft-versie en modloader. "
                + "Het rechtstreeks importeren van complete .mrpack- of CurseForge-modpacks is nog niet beschikbaar op Android.",14,0xffc0d1e3),12);
        searchBox = new EditText(this);
        searchBox.setSingleLine(true); searchBox.setTextColor(Color.WHITE);
        searchBox.setHintTextColor(0xff9bb0c7); searchBox.setHint("Zoek modpack...");
        searchBox.setBackground(rounded(0xff182c44));
        searchBox.setPadding(dp(12),dp(12),dp(12),dp(12));
        add(root,searchBox,9);
        Button search = btn("Zoek Modrinth-modpacks");
        search.setOnClickListener(v -> search());
        add(root,search,12);
        status = text("Zoek om compatibele modpacks te vinden.",13,0xffc0d1e3);
        add(root,status,10);
        results = new LinearLayout(this); results.setOrientation(1);
        add(root,results,16);
        setContentView(scroll);
    }
    private void search() {
        String q = searchBox.getText().toString().trim();
        ProfileStore.Profile profile = ProfileStore.active(this);
        status.setText("Modpacks zoeken...");
        results.removeAllViews();
        new Thread(() -> {
            try {
                List<ModrinthClient.Mod> found = ModrinthClient.searchModpacks(q,
                        profile.version, ProfileStore.LOADERS[profile.loader]);
                runOnUiThread(() -> {
                    status.setText(found.size() + " passende modpacks gevonden.");
                    for (ModrinthClient.Mod m : found) {
                        LinearLayout panel = new LinearLayout(this);
                        panel.setBackground(rounded(0xff1c3049));
                        panel.setPadding(dp(13),dp(13),dp(13),dp(13));
                        panel.setOrientation(1);
                        add(panel,text(m.title,17,Color.WHITE),8);
                        add(panel,text(m.description,13,0xffc0d1e3),10);
                        Button open = btn("Modpackdetails openen");
                        open.setOnClickListener(v -> {
                            Intent i = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://modrinth.com/modpack/" + m.id));
                            startActivity(i);
                        });
                        add(panel,open,4);
                        add(results,panel,10);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Modpackzoekfout: " + e.getMessage()));
            }
        }, "Modpack-Search").start();
    }
}
