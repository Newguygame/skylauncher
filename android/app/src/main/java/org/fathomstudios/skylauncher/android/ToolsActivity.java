package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ToolsActivity extends Activity {
    private TextView logs;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable bg(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(14)); return d;
    }
    private void add(LinearLayout l, View v, int space) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(space); l.addView(v, p);
    }
    private Button button(String name) {
        Button b = new Button(this); b.setText(name); b.setAllCaps(false);
        b.setTextColor(Color.rgb(9, 18, 30)); b.setBackground(bg(0xff45dacc)); return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(0xff0b1424);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout root = new LinearLayout(this); root.setPadding(dp(19), dp(26), dp(19), dp(26)); root.setOrientation(1);
        scroll.addView(root);
        TextView title = new TextView(this); title.setText("SKYLAUNCHER / TOOLS & LOGS");
        title.setTextColor(Color.WHITE); title.setTextSize(23); add(root,title,13);
        Button share = button("Logboek delen");
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, readLogs());
            startActivity(Intent.createChooser(send, "SkyLauncher-logboek delen"));
        });
        add(root,share,10);
        Button refresh = button("Logboek verversen");
        refresh.setOnClickListener(v -> logs.setText(readLogs()));
        add(root,refresh,15);
        logs = new TextView(this); logs.setTextSize(12); logs.setTextColor(0xffdbebf7);
        logs.setTypeface(android.graphics.Typeface.MONOSPACE);
        logs.setPadding(dp(13),dp(13),dp(13),dp(13)); logs.setBackground(bg(0xff18283d));
        logs.setText(readLogs()); add(root,logs,10);
        TextView info = new TextView(this);
        info.setText("Alleen lokale SkyLauncher-acties worden gelogd. Microsoft-aanmeldingen, gamelogboeken en foutmeldingen van externe launchers zijn niet beschikbaar.");
        info.setTextSize(13); info.setTextColor(0xffaabcce); add(root,info,4);
        setContentView(scroll);
    }
    private String readLogs() {
        try {
            java.io.File file = LogBook.logFile(this);
            if (!file.exists()) return "Nog geen SkyLauncher-logboek.";
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) { return "Kan log niet openen: " + e.getMessage(); }
    }
}
