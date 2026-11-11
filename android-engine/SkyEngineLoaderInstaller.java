package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SkyLauncher-owned modloader installation flow. All download/version logic
 * lives in SkyLoaderInitializer, never in Amethyst's loader installer.
 */
public final class SkyEngineLoaderInstaller extends Activity {
    public static final String EXTRA_PROFILE_ID = "skylauncher.loader.profile_id";
    private static final int JAVA_RUN_REQUEST = 1408;

    private ProfileStore.Profile profile;
    private TextView status;
    private ProgressBar progress;
    private Button cancel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelled;
    private volatile boolean finished;
    private boolean waitingForJava;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profile = ProfileStore.active(this);
        String id = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if (id == null || !id.equals(profile.id)) {
            new AlertDialog.Builder(this).setTitle("Minecraft profile changed")
                    .setMessage("Select the intended profile in SkyLauncher and press Play again.")
                    .setPositiveButton("OK", (d, w) -> finish()).show();
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 32, 24, 20);
        layout.setBackgroundColor(0xff101c30);
        TextView title = new TextView(this);
        title.setText("SkyLauncher — Modloader initializer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        layout.addView(title);
        status = new TextView(this);
        status.setTextColor(0xffcbddec);
        status.setTextSize(15);
        status.setPadding(0, 24, 0, 24);
        layout.addView(status);
        progress = new ProgressBar(this);
        layout.addView(progress);
        cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> {
            if (waitingForJava) return; // Native JVM installation cannot be interrupted safely.
            cancelled = true;
            finish();
        });
        layout.addView(cancel);
        setContentView(layout);
        install();
    }

    private void report(String message) {
        runOnUiThread(() -> {
            if (isFinishing() || cancelled) return;
            status.setText(message);
            LogBook.write(this, message);
        });
    }

    private void fail(String error) {
        running.set(false);
        LogBook.write(this, "SkyLoader initializer failure: " + error);
        runOnUiThread(() -> {
            if (cancelled || finished || isFinishing()) return;
            progress.setVisibility(View.GONE);
            status.setText(error);
            new AlertDialog.Builder(this)
                    .setTitle("SkyLauncher modloader installation failed")
                    .setMessage(error + "\n\nMinecraft will not silently start with Vanilla.")
                    .setPositiveButton("Retry", (d, w) -> {
                        progress.setVisibility(View.VISIBLE);
                        install();
                    })
                    .setNegativeButton("Back", (d, w) -> finish())
                    .setCancelable(false).show();
        });
    }

    private void install() {
        if (cancelled || !running.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                SkyLoaderInitializer.Result result = SkyLoaderInitializer.initialize(
                        this, profile, new SkyLoaderInitializer.Status() {
                            @Override public void update(String message) { report(message); }
                            @Override public boolean cancelled() { return cancelled; }
                        });
                if (cancelled) return;
                if (!result.needsJavaInstaller()) {
                    running.set(false);
                    verifyAndLaunch();
                    return;
                }
                runOnUiThread(() -> {
                    if (cancelled || isFinishing()) return;
                    try {
                        report("Launching SkyLauncher Java initializer for " + result.kind + "...");
                        Intent intent = new Intent(this, SkyLoaderJavaActivity.class);
                        intent.putExtra(SkyLoaderJavaActivity.EXTRA_INSTALLER,
                                result.installer.getAbsolutePath());
                        intent.putExtra(SkyLoaderJavaActivity.EXTRA_KIND, result.kind);
                        intent.putExtra(SkyLoaderJavaActivity.EXTRA_PROFILE, profile.id);
                        intent.putExtra(SkyLoaderJavaActivity.EXTRA_VERSION_ID, result.versionId);
                        intent.putExtra(SkyLoaderJavaActivity.EXTRA_MIN_JAVA,
                                SkyLoaderInitializer.minimumJavaVersion(profile.version));
                        waitingForJava = true;
                        cancel.setEnabled(false);
                        running.set(false);
                        startActivityForResult(intent, JAVA_RUN_REQUEST);
                    } catch (Exception failure) {
                        fail("Cannot open SkyLauncher Java installer: " + failure.getMessage());
                    }
                });
            } catch (Exception error) {
                fail(error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
            }
        }, "SkyLauncher-Modloader-Initializer").start();
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != JAVA_RUN_REQUEST || !waitingForJava) return;
        waitingForJava = false;
        cancel.setEnabled(true);
        if (result != RESULT_OK) {
            String detail = data == null ? "Java installer did not complete."
                    : data.getStringExtra("skyloader.error");
            fail(detail == null ? "Java installer did not complete." : detail);
            return;
        }
        verifyAndLaunch();
    }

    private void verifyAndLaunch() {
        if (cancelled || finished || isFinishing()) return;
        report("Verifying Minecraft profile and loader artifacts...");
        new Thread(() -> {
            try {
                if (!profile.id.equals(ProfileStore.active(this).id))
                    throw new IllegalStateException("Active profile changed during installation.");
                SkyEngineProfileBridge.synchronize(this, profile);
                runOnUiThread(() -> {
                    if (cancelled || finished || isFinishing()) return;
                    finished = true;
                    progress.setVisibility(View.GONE);
                    LogBook.write(this, "SkyLauncher modloader ready; launching Minecraft.");
                    SkyEngineProfileBridge.launch(this);
                    finish();
                });
            } catch (Exception problem) {
                fail("SkyLauncher could not verify the installed modloader: "
                        + (problem.getMessage() == null
                        ? problem.getClass().getSimpleName() : problem.getMessage()));
            }
        }, "SkyLauncher-Verify-Loader").start();
    }
}
