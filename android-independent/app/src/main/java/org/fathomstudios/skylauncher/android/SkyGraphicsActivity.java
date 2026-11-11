package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.graphics.Color;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Own Android GLES 3 surface with JNI capability probe. This is a renderer
 * proof-of-life, not a fake Minecraft screen or replacement for OpenGL 3.3.
 */
public final class SkyGraphicsActivity extends Activity {
    private GLSurfaceView surface;
    private TextView status;
    private static final boolean NATIVE_AVAILABLE;
    static {
        boolean present;
        try {
            System.loadLibrary("skyengine");
            present = true;
        } catch (UnsatisfiedLinkError e) {
            present = false;
        }
        NATIVE_AVAILABLE = present;
    }

    private static native String nativeDeviceInfo();
    private static native String nativeVulkanReport();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout frame = new FrameLayout(this);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(3);
        surface.setRenderer(new Renderer());
        frame.addView(surface);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xb5000914);
        status.setTextSize(13);
        status.setPadding(24, 28, 24, 24);
        status.setText("SkyLauncher graphics backend check\n"
                + "Testing native OpenGL ES context...");
        frame.addView(status);
        setContentView(frame);
    }

    @Override protected void onPause() {
        super.onPause();
        if (surface != null) surface.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (surface != null) surface.onResume();
    }

    private final class Renderer implements GLSurfaceView.Renderer {
        private long frames;
        @Override public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
            String version = GLES30.glGetString(GLES30.GL_VERSION);
            String vendor = GLES30.glGetString(GLES30.GL_VENDOR);
            String renderer = GLES30.glGetString(GLES30.GL_RENDERER);
            final String text = String.format(Locale.ROOT,
                    "SkyLauncher independent renderer running.\n"
                    + "OpenGL ES: %s\nGPU: %s / %s\nNative JNI: %s\n\n"
                    + "Minecraft still needs Java 21 and an "
                    + "LWJGL/OpenGL compatibility layer. "
                    + "This is an Android graphics test, not Minecraft gameplay.",
                    version, vendor, renderer,
                    NATIVE_AVAILABLE ? nativeDeviceInfo() : "native library not loaded");
            runOnUiThread(() -> status.setText(text));
            if (NATIVE_AVAILABLE) {
                new Thread(() -> {
                    final String details;
                    try {
                        details = nativeVulkanReport();
                    } catch (Throwable error) {
                        final String reason = error.getMessage();
                        runOnUiThread(() -> {
                            if (!isFinishing()) status.setText(text +
                                    "\n\nVulkan probe error: " + reason);
                        });
                        return;
                    }
                    runOnUiThread(() -> {
                        if (!isFinishing()) status.setText(text + "\n\n" + details);
                    });
                }, "SkyLauncher-Vulkan-Capability-Probe").start();
            }
        }
        @Override public void onSurfaceChanged(GL10 ignored, int width, int height) {
            GLES30.glViewport(0, 0, width, height);
        }
        @Override public void onDrawFrame(GL10 ignored) {
            frames++;
            float offset = (float) Math.sin(frames * 0.015) * .06f;
            GLES30.glClearColor(.025f, .10f + offset, .18f + offset, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        }
    }
}
