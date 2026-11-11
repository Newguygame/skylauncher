package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native SkyLauncher Android app: no external launcher Activities.
 * The JVM/graphics backend is intentionally not exposed as Play until it exists.
 */
public final class MainActivity extends Activity {
    private final AtomicBoolean installing = new AtomicBoolean(false);
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private LinearLayout container;
    private TextView info;
    private Button installButton;
    private Button loaderButton;
    private Button runtimeButton;
    private static final int PICK_SHADER_ZIP = 707;
    private final AtomicBoolean shaderBusy = new AtomicBoolean(false);
    private ProfileStore.Profile profile;

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private TextView label(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value); b.setAllCaps(false);
        b.setTextColor(0xff061722);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xff45dacc); drawable.setCornerRadius(dp(12));
        b.setBackground(drawable);
        b.setPadding(dp(15), dp(12), dp(15), dp(12));
        return b;
    }

    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(margin);
        parent.addView(child, layout);
    }

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(0xff0a1424);
        getWindow().setNavigationBarColor(0xff0a1424);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff0a1424);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(22), dp(34), dp(22), dp(26));
        scroll.addView(container);
        setContentView(scroll);
        render();
    }

    private void render() {
        container.removeAllViews();
        List<ProfileStore.Profile> profiles = ProfileStore.all(this);
        profile = profiles.get(0);
        add(container, label("SkyLauncher", 30, Color.WHITE), 4);
        add(container, label("INDEPENDENT ANDROID ENGINE · DEVELOPMENT BUILD",
                12, 0xff45dacc), 18);
        add(container, label("Runs without Amethyst, Pojav, FCL or another "
                + "launcher. This is the new native foundation, not yet a playable "
                + "Minecraft client.", 15, 0xffc2d0df), 20);

        add(container, label("Active profile: " + profile.name, 18, Color.WHITE), 6);
        add(container, label("Minecraft " + profile.version + " · "
                + profile.loader, 14, 0xffb8ccd9), 18);
        Button change = button("Create / select Minecraft profile");
        change.setOnClickListener(v -> showProfileDialog());
        add(container, change, 24);

        add(container, label("Minecraft files", 21, Color.WHITE), 10);
        add(container, label("Download official Minecraft version metadata, "
                + "client JAR, libraries and assets with checksum verification. "
                + "Each profile has separate worlds, configs and mods.",
                14, 0xffc2d0df), 14);
        installButton = button("Install official Minecraft files");
        installButton.setOnClickListener(v -> installGame());
        add(container, installButton, 12);
        loaderButton = button("Prepare selected modloader (own initializer)");
        loaderButton.setEnabled(!"Vanilla".equals(profile.loader));
        loaderButton.setOnClickListener(v -> prepareModloader());
        add(container, loaderButton, 12);
        boolean jreInstalled = SkyJavaRuntimeInstaller.ready(this);
        runtimeButton = button(jreInstalled
                ? "Android OpenJDK 21 installed"
                : "Install Android OpenJDK 21 runtime");
        runtimeButton.setEnabled(!jreInstalled);
        runtimeButton.setOnClickListener(v -> installJavaRuntime());
        add(container, runtimeButton, 12);
        Button stop = button("Cancel download");
        stop.setOnClickListener(v -> cancel.set(true));
        add(container, stop, 14);
        info = label(SkyJavaRuntimeInstaller.ready(this)
                ? "OpenJDK 21 files installed. The native LWJGL/OpenGL renderer "
                  + "is not integrated yet."
                : "Install the bundled Java 21 runtime before attempting to "
                  + "start Minecraft. Rendering is still experimental.",
                13, 0xffb8ccd9);
        add(container, info, 25);

        add(container, label("Microsoft / Minecraft account", 21, Color.WHITE), 10);
        String loggedIn = MicrosoftLogin.username(this);
        add(container, label(loggedIn.isEmpty()
                ? "Not signed in. A genuine Minecraft Java account is required."
                : "Signed in as: " + loggedIn,
                14, 0xffc2d0df), 10);
        Button login = button("Sign in with Microsoft");
        login.setOnClickListener(v -> beginMicrosoftLogin());
        add(container, login, 10);
        Button signout = button("Sign out");
        signout.setEnabled(!loggedIn.isEmpty());
        signout.setOnClickListener(v -> {
            MicrosoftLogin.signOut(this);
            render();
        });
        add(container, signout, 23);

        add(container, label("Shaders (experimental)", 21, Color.WHITE), 10);
        add(container, label("Import a shader pack ZIP into this Minecraft profile. "
                + "Actual Iris shader rendering requires the independent "
                + "OpenGL-on-Vulkan backend and a compatible shader mod.",
                14, 0xffc2d0df), 12);
        Button shaders = button("Import shader pack ZIP");
        shaders.setOnClickListener(v -> selectShaderZip());
        add(container, shaders, 12);
        Button listShaders = button("Show installed shader packs");
        listShaders.setOnClickListener(v -> showShaderPacks());
        add(container, listShaders, 12);
        Button backend = button("Choose target graphics backend");
        backend.setOnClickListener(v -> chooseGraphicsBackend());
        add(container, backend, 23);

        add(container, label("Minecraft launch", 21, Color.WHITE), 8);
        add(container, label("Test SkyLauncher's own graphics interface and inspect "
                + "the exact requirements needed before starting Minecraft. "
                + "Real gameplay is not enabled until Android Java 21, LWJGL "
                + "and Microsoft account support work.",
                14, 0xffc2d0df), 12);
        Button inspect = button("Check Minecraft launch requirements");
        inspect.setOnClickListener(v -> checkLaunchRequirements());
        add(container, inspect, 12);
        Button graphics = button("Test independent Android graphics");
        graphics.setOnClickListener(v ->
                startActivity(new Intent(this, SkyGraphicsActivity.class)));
        add(container, graphics, 12);
        Button play = button("Start Minecraft (experimental JNI)");
        play.setOnClickListener(v -> {
            Intent game = new Intent(this, SkyMinecraftGameActivity.class);
            game.putExtra(SkyMinecraftGameActivity.EXTRA_PROFILE_ID, profile.id);
            startActivity(game);
        });
        add(container, play, 20);
    }

    private void showProfileDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(10), dp(18), dp(4));
        EditText name = new EditText(this);
        name.setHint("Profile name"); name.setSingleLine(true);
        name.setText("Minecraft");
        form.addView(name);
        EditText version = new EditText(this);
        version.setHint("Minecraft version"); version.setSingleLine(true);
        version.setText("1.21.1");
        form.addView(version);
        Spinner loader = new Spinner(this);
        loader.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ProfileStore.LOADERS));
        form.addView(loader);
        new AlertDialog.Builder(this).setTitle("New independent Minecraft profile")
                .setView(form)
                .setPositiveButton("Create", (dialog, which) -> {
                    String v = version.getText().toString().trim();
                    if (!v.matches("[A-Za-z0-9._-]{1,64}")) {
                        Toast.makeText(this, "Invalid Minecraft version", Toast.LENGTH_LONG).show();
                        return;
                    }
                    List<ProfileStore.Profile> profiles = ProfileStore.all(this);
                    profiles.add(0, new ProfileStore.Profile(
                            java.util.UUID.randomUUID().toString(),
                            name.getText().toString().trim().isEmpty() ? "Minecraft"
                                    : name.getText().toString().trim(),
                            v, ProfileStore.LOADERS[loader.getSelectedItemPosition()]));
                    ProfileStore.save(this, profiles);
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void installJavaRuntime() {
        if (!installing.compareAndSet(false, true)) {
            Toast.makeText(this, "Another operation is running.", Toast.LENGTH_SHORT).show();
            return;
        }
        cancel.set(false);
        runtimeButton.setEnabled(false);
        installButton.setEnabled(false);
        loaderButton.setEnabled(false);
        info.setText("Verifying packaged OpenJDK 21 runtime...");
        new Thread(() -> {
            try {
                SkyJavaRuntimeInstaller.install(this, cancel, progress ->
                        runOnUiThread(() -> {
                            if (!isFinishing() && info != null)
                                info.setText(progress);
                        }));
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText(
                            "OpenJDK 21 runtime installed. Native graphics and "
                                    + "Minecraft JVM startup still require testing.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText("OpenJDK installation failed: "
                            + (error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage()));
                });
            } finally {
                installing.set(false);
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        runtimeButton.setEnabled(!SkyJavaRuntimeInstaller.ready(this));
                        if (SkyJavaRuntimeInstaller.ready(this))
                            runtimeButton.setText("Android OpenJDK 21 installed");
                        installButton.setEnabled(true);
                        loaderButton.setEnabled(!"Vanilla".equals(profile.loader));
                    }
                });
            }
        }, "SkyLauncher-Standalone-OpenJDK21-Setup").start();
    }

    private void installGame() {
        if (!installing.compareAndSet(false, true)) return;
        installButton.setEnabled(false);
        loaderButton.setEnabled(false);
        cancel.set(false);
        final ProfileStore.Profile selected = profile;
        info.setText("Starting official Minecraft download...");
        new Thread(() -> {
            try {
                SkyGameInstaller.install(this, selected, cancel,
                        message -> runOnUiThread(() -> {
                            if (!isFinishing() && info != null) info.setText(message);
                        }));
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText("Minecraft files verified. "
                            + "The native Java / graphics launcher is not ready yet.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText("Install failed: "
                            + (error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage()));
                });
            } finally {
                installing.set(false);
                runOnUiThread(() -> {
                    if (!isFinishing() && installButton != null) {
                        installButton.setEnabled(true);
                        if (loaderButton != null)
                            loaderButton.setEnabled(!"Vanilla".equals(profile.loader));
                    }
                });
            }
        }, "SkyLauncher-Independent-Minecraft-Installer").start();
    }

    private void prepareModloader() {
        if ("Vanilla".equals(profile.loader)) {
            info.setText("Vanilla Minecraft has no separate modloader.");
            return;
        }
        if (!installing.compareAndSet(false, true)) return;
        cancel.set(false);
        installButton.setEnabled(false);
        loaderButton.setEnabled(false);
        final ProfileStore.Profile selected = profile;
        info.setText("Checking official " + selected.loader + " metadata...");
        new Thread(() -> {
            try {
                SkyModloaderInitializer.Result result =
                        SkyModloaderInitializer.prepare(this, selected, cancel,
                                message -> runOnUiThread(() -> {
                                    if (!isFinishing() && info != null)
                                        info.setText(message);
                                }));
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText(result.kind + " "
                            + result.loaderVersion + " prepared for " + selected.version
                            + ". Minecraft gameplay remains disabled until the "
                            + "independent Android Java and graphics runtime is built.");
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText("Modloader initializer failed: "
                            + (failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage()));
                });
            } finally {
                installing.set(false);
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        installButton.setEnabled(true);
                        loaderButton.setEnabled(!"Vanilla".equals(profile.loader));
                    }
                });
            }
        }, "SkyLauncher-Independent-Modloader-Initializer").start();
    }


    private void checkLaunchRequirements() {
        if (!installing.compareAndSet(false, true)) {
            Toast.makeText(this, "Another installation is running.", Toast.LENGTH_SHORT).show();
            return;
        }
        final ProfileStore.Profile selected = profile;
        info.setText("Checking installed Minecraft files and Java prerequisites...");
        new Thread(() -> {
            try {
                final SkyLaunchPlan.Plan plan = SkyLaunchPlan.preflight(this, selected);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    info.setText("Minecraft preflight completed: "
                            + plan.blockers.size() + " launch blockers.");
                    new AlertDialog.Builder(this)
                            .setTitle("SkyLauncher Minecraft launch preflight")
                            .setMessage(plan.report())
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText("Launch verification error: "
                            + (failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage()));
                });
            } finally {
                installing.set(false);
            }
        }, "SkyLauncher-Launch-Preflight").start();
    }


    private void beginMicrosoftLogin() {
        if (installing.get()) {
            info.setText("Finish the current download before account sign-in.");
            return;
        }
        info.setText("Contacting Microsoft for a Minecraft device sign-in code...");
        MicrosoftLogin.login(this, new MicrosoftLogin.Listener() {
            @Override public void deviceCode(String code, String uri) {
                if (isFinishing()) return;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Sign in with Microsoft")
                        .setMessage("Open " + uri + " and enter code:\n\n" + code
                                + "\n\nOnly sign in on the official Microsoft website. "
                                + "Return here after authorizing.")
                        .setPositiveButton("Open Microsoft", (dialog, which) -> {
                            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                            startActivity(browser);
                        })
                        .setNeutralButton("Copy code", (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null)
                                clipboard.setPrimaryClip(ClipData.newPlainText(
                                        "Microsoft device code", code));
                        })
                        .setNegativeButton("Close", null)
                        .show();
                info.setText("Microsoft verification code: " + code
                        + "\nGo to: " + uri + "\nWaiting for Microsoft approval...");
            }
            @Override public void progress(String message) {
                if (!isFinishing()) info.setText(message);
            }
            @Override public void success(String username, String uuid) {
                if (!isFinishing()) {
                    render();
                    info.setText("Microsoft Minecraft account verified: " + username);
                }
            }
            @Override public void error(String message) {
                if (!isFinishing()) info.setText("Microsoft sign-in: " + message);
            }
        });
    }

    private void selectShaderZip() {
        if (installing.get() || shaderBusy.get()) {
            info.setText("Complete the current action before importing a shader pack.");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/zip");
        picker.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/x-zip-compressed",
                        "application/octet-stream"});
        try {
            startActivityForResult(picker, PICK_SHADER_ZIP);
        } catch (android.content.ActivityNotFoundException error) {
            info.setText("No Android ZIP file picker is available.");
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int requestCode, int resultCode,
                                              Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SHADER_ZIP || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        if (!shaderBusy.compareAndSet(false, true)) return;
        final ProfileStore.Profile selected = profile;
        final Uri zip = data.getData();
        cancel.set(false);
        info.setText("Checking shaderpack ZIP...");
        new Thread(() -> {
            try {
                SkyShaderPacks.ImportResult installed =
                        SkyShaderPacks.importZip(this, selected, zip, cancel,
                                status -> runOnUiThread(() -> {
                                    if (!isFinishing()) info.setText(status);
                                }));
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText(installed.summary());
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing()) info.setText("Shaderpack import failed: "
                            + (error.getMessage() == null ? "Unknown problem"
                            : error.getMessage()));
                });
            } finally {
                shaderBusy.set(false);
            }
        }, "SkyLauncher-Shaderpack-Importer").start();
    }

    private void showShaderPacks() {
        try {
            String[] packs = SkyShaderPacks.installed(this, profile);
            String message = packs.length == 0 ? "No shader packs imported yet."
                    : String.join("\n", packs);
            new AlertDialog.Builder(this).setTitle(
                    "Shaderpacks for " + profile.name)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception error) {
            info.setText("Cannot read shaderpacks: " + error.getMessage());
        }
    }

    private void chooseGraphicsBackend() {
        final String[] choices = {"Vulkan + Zink (target; not integrated)",
                "Android OpenGL ES (diagnostics only)"};
        String old = getPreferences(MODE_PRIVATE).getString("graphics_backend",
                "vulkan_zink");
        int selected = old.equals("android_gles") ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Future Minecraft graphics backend")
                .setSingleChoiceItems(choices, selected, (dialog, index) -> {
                    getPreferences(MODE_PRIVATE).edit().putString("graphics_backend",
                            index == 0 ? "vulkan_zink" : "android_gles").apply();
                    dialog.dismiss();
                    info.setText("Target graphics backend saved. This does not "
                            + "enable game rendering yet.");
                }).setNegativeButton("Cancel", null).show();
    }

}
