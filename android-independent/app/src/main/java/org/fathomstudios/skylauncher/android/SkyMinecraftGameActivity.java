package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated SkyLauncher Minecraft process. Never invokes another launcher.
 * Uses the app's own JNI bridge only after all prerequisites pass.
 */
public final class SkyMinecraftGameActivity extends Activity {
    public static final String EXTRA_PROFILE_ID = "skylauncher.minecraft.profile";
    private TextView status;
    private static final boolean JNI_LOADED;
    static {
        boolean loaded;
        try {
            System.loadLibrary("skyengine");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        JNI_LOADED = loaded;
    }

    private static native String nativeLaunchMinecraft(
            String javaHome, String classpath, String mainClass,
            String gameDirectory, String[] arguments, int heapMb, String nativeLibDir);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff091325);
        getWindow().setNavigationBarColor(0xff091325);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(30, 35, 30, 30);
        content.setBackgroundColor(0xff081629);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("SkyLauncher · Minecraft Java");
        title.setTextSize(23);
        title.setTextColor(Color.WHITE);
        content.addView(title);
        status = new TextView(this);
        status.setTextColor(0xffc1d7e7);
        status.setTextSize(15);
        status.setPadding(0, 36, 0, 24);
        status.setText("Checking Minecraft launch requirements...");
        content.addView(status);
        Button back = new Button(this);
        back.setAllCaps(false);
        back.setText("Back to SkyLauncher");
        back.setOnClickListener(v -> finish());
        content.addView(back);
        ScrollView view = new ScrollView(this);
        view.addView(content);
        setContentView(view);
        new Thread(this::launch, "SkyLauncher-Minecraft-HotSpot").start();
    }

    private void report(String text) {
        runOnUiThread(() -> {
            if (!isFinishing() && status != null) status.setText(text);
        });
    }

    private int chooseHeapMb() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(info);
        long ram = info.totalMem > 0 ? info.totalMem / 1024 / 1024 : 4096;
        return (int) Math.min(4096, Math.max(1024, ram * 40 / 100));
    }

    private void launch() {
        try {
            String id = getIntent().getStringExtra(EXTRA_PROFILE_ID);
            ProfileStore.Profile profile = ProfileStore.all(this).get(0);
            if (id == null || !id.equals(profile.id))
                throw new IllegalStateException("Minecraft profile changed; launch cancelled.");
            if (!JNI_LOADED)
                throw new IllegalStateException("SkyLauncher native Java bridge is missing.");
            if (!"Vanilla".equals(profile.loader))
                throw new IllegalStateException("Modded Minecraft needs the independent "
                        + "LWJGL and Java installer backend. Vanilla is the first target.");

            SkyLaunchPlan.Plan plan = SkyLaunchPlan.preflight(this, profile);
            if (!plan.blockers.isEmpty()) {
                report(plan.report());
                return;
            }

            // Request the Minecraft bearer token only after validating all
            // files. Never place tokens in Intent extras, UI, or diagnostics.
            report("Verifying Minecraft Java ownership...");
            String token = MicrosoftLogin.getVerifiedMinecraftAccessToken(this);
            if (token == null || token.isEmpty())
                throw new IllegalStateException("Microsoft Minecraft token unavailable.");
            Map<String, String> values = new LinkedHashMap<>();
            values.put("auth_player_name", MicrosoftLogin.username(this));
            values.put("auth_uuid", MicrosoftLogin.uuid(this));
            values.put("auth_access_token", token);
            values.put("version_name", profile.version);
            values.put("game_directory", plan.gameDirectory.getAbsolutePath());
            values.put("assets_root", plan.assetRoot.getAbsolutePath());
            values.put("assets_index_name", plan.assetsIndex);
            values.put("user_type", "msa");
            values.put("version_type", "release");
            values.put("clientid", "");
            values.put("auth_xuid", "");
            values.put("launcher_name", "SkyLauncher");
            values.put("launcher_version", "independent-android-dev");
            values.put("natives_directory", getApplicationInfo().nativeLibraryDir);
            values.put("classpath", plan.classpath);
            List<String> args = new ArrayList<>();
            for (String item : plan.gameArgs) {
                String resolved = item;
                for (Map.Entry<String, String> entry : values.entrySet())
                    resolved = resolved.replace("$" + "{" + entry.getKey() + "}", entry.getValue());
                if (resolved.contains("$" + "{"))
                    throw new IllegalStateException("Minecraft has an unresolved argument.");
                args.add(resolved);
            }

            File javaHome = new File(getFilesDir(),
                    "minecraft/runtimes/java21").getCanonicalFile();
            if (!new File(javaHome, "lib/server/libjvm.so").isFile())
                throw new IllegalStateException("Android ARM64 Java 21 runtime is not installed. "
                        + "SkyLauncher must package and verify its independent OpenJDK runtime.");
            report("Calling the Minecraft Java main class through SkyLauncher JNI...");
            String result = nativeLaunchMinecraft(javaHome.getAbsolutePath(),
                    plan.classpath, plan.mainClass,
                    plan.gameDirectory.getAbsolutePath(),
                    args.toArray(new String[0]), chooseHeapMb(),
                    getApplicationInfo().nativeLibraryDir);
            report("Native Minecraft Java exit:\n" + result);
        } catch (Throwable error) {
            String message = error.getMessage();
            report("Minecraft could not start:\n" + (message == null
                    ? error.getClass().getSimpleName() : message));
        }
    }
}
