package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class AccountActivity extends Activity {
    private LinearLayout root;
    private TextView status;
    private TextView loginCode;
    private Button loginButton;
    private Button browserButton;
    private Button copyButton;
    private String currentCode = "";
    private String verificationUrl = "";
    private boolean signingIn;

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(15));
        return shape;
    }
    private void add(LinearLayout parent, View child, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(bottom);
        parent.addView(child, p);
    }
    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xff081727);
        b.setBackground(rounded(0xff45dacc));
        b.setMinHeight(dp(50));
        return b;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff0c192b);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        root = new LinearLayout(this);
        root.setPadding(dp(22), dp(28), dp(22), dp(27));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        add(root, text("SKYLAUNCHER / ACCOUNT", 24, Color.WHITE), 12);
        add(root, text("Meld je aan met je eigen Microsoft-account om het Minecraft Java-profiel te controleren. "
                + "SkyLauncher vraagt nooit om je Microsoft-wachtwoord. "
                + "Aanmelden en spelen via Amethyst blijven voorlopig gescheiden.", 15, 0xffc5d7e9), 15);
        String current = MicrosoftLogin.username(this);
        if (!current.isEmpty()) {
            add(root, text("Huidige account: " + current, 18, 0xff45dacc), 9);
            add(root, text("Minecraft UUID: " + MicrosoftLogin.uuid(this),
                    12, 0xffb8cadd), 12);
            Button signOut = button("Afmelden bij SkyLauncher");
            signOut.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Afmelden?")
                    .setMessage("De lokaal opgeslagen SkyLauncher-accountgegevens worden verwijderd. Dit meldt je niet af bij Amethyst of Microsoft in je browser.")
                    .setPositiveButton("Afmelden", (d, which) -> {
                        MicrosoftLogin.signOut(this);
                        LogBook.write(this, "SkyLauncher-account afgemeld");
                        recreate();
                    })
                    .setNegativeButton("Annuleren", null).show());
            add(root, signOut, 13);
        }
        loginButton = button(current.isEmpty() ? "Inloggen met Microsoft" : "Andere Microsoft-account");
        loginButton.setOnClickListener(v -> begin());
        add(root, loginButton, 13);

        status = text("Microsoft device-code-aanmelding: veilig via de officiële Microsoft-website.", 14, 0xffc5d7e9);
        add(root, status, 12);
        loginCode = text("", 25, 0xff45dacc);
        loginCode.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        add(root, loginCode, 12);
        copyButton = button("Kopieer Microsoft-code");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> {
            if (currentCode.isEmpty()) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Microsoft-aanmeldcode", currentCode));
            Toast.makeText(this, "Code gekopieerd", Toast.LENGTH_SHORT).show();
        });
        add(root, copyButton, 9);
        browserButton = button("Open Microsoft-aanmeldwebsite");
        browserButton.setEnabled(false);
        browserButton.setOnClickListener(v -> {
            if (verificationUrl.isEmpty()) return;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)));
        });
        add(root, browserButton, 10);
        Button cancel = button("Aanmelding annuleren");
        cancel.setOnClickListener(v -> {
            MicrosoftLogin.cancel();
            signingIn = false;
            loginButton.setEnabled(true);
            status.setText("Aanmelding geannuleerd.");
            loginCode.setText("");
            currentCode = "";
            copyButton.setEnabled(false);
            browserButton.setEnabled(false);
        });
        add(root, cancel, 14);
        add(root, text("Een Minecraft-licentie is vereist. Sommige Microsoft-appregistraties "
                + "hebben nog extra toestemming nodig om Minecraft Services te gebruiken. "
                + "Accounttokens worden versleuteld bewaard op dit apparaat en niet gedeeld met Amethyst.", 12, 0xff9cb5ca), 4);
        setContentView(scroll);
    }

    private void begin() {
        if (signingIn) return;
        signingIn = true;
        loginButton.setEnabled(false);
        status.setText("Microsoft-aanmeldcode aanvragen...");
        MicrosoftLogin.login(this, new MicrosoftLogin.Listener() {
            @Override public void deviceCode(String code, String url) {
                currentCode = code;
                verificationUrl = url;
                loginCode.setText(code);
                status.setText("Open de Microsoft-website en voer de code in. "
                        + "SkyLauncher controleert daarna automatisch je Minecraft-profiel.");
                copyButton.setEnabled(true);
                browserButton.setEnabled(true);
            }
            @Override public void progress(String message) {
                status.setText(message);
            }
            @Override public void success(String username, String uuid) {
                signingIn = false;
                LogBook.write(AccountActivity.this, "Minecraft-profiel geverifieerd");
                new AlertDialog.Builder(AccountActivity.this)
                        .setTitle("Minecraft-profiel gevonden")
                        .setMessage("Aangemeld als " + username
                                + ". Je account is beschikbaar in SkyLauncher, maar Amethyst gebruikt voorlopig zijn eigen aanmeldsysteem.")
                        .setPositiveButton("OK", (d, w) -> recreate())
                        .show();
            }
            @Override public void error(String message) {
                signingIn = false;
                loginButton.setEnabled(true);
                status.setText(message);
                currentCode = "";
                verificationUrl = "";
                loginCode.setText("");
                copyButton.setEnabled(false);
                browserButton.setEnabled(false);
                LogBook.write(AccountActivity.this, "Microsoft-aanmelding mislukt (zonder tokenopslag)");
            }
        });
    }

    @Override protected void onDestroy() {
        // Do not cancel when another app (the Microsoft browser) is in foreground.
        if (isFinishing()) MicrosoftLogin.cancel();
        super.onDestroy();
    }
}
