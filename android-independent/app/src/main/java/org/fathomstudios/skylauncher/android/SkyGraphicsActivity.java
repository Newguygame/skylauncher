package org.fathomstudios.skylauncher.android;

import android.app.Activity;
import android.graphics.Color;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        private int shaderProgram;
        private int timeUniform;
        private String shaderReport = "GLSL test not started";
        private final FloatBuffer triangle = ByteBuffer.allocateDirect(6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        private int compileShader(int type, String source) {
            int handle = GLES30.glCreateShader(type);
            GLES30.glShaderSource(handle, source);
            GLES30.glCompileShader(handle);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(handle, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                String message = GLES30.glGetShaderInfoLog(handle);
                GLES30.glDeleteShader(handle);
                throw new IllegalStateException("GLSL compile failed: " + message);
            }
            return handle;
        }

        private void prepareAnimatedGlShader() {
            int vertex = compileShader(GLES30.GL_VERTEX_SHADER,
                    "#version 300 es\n"
                    + "layout(location=0) in vec2 position;\n"
                    + "void main(){gl_Position=vec4(position,0.0,1.0);}");
            int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER,
                    "#version 300 es\n"
                    + "precision highp float;\n"
                    + "uniform float u_time;\n"
                    + "out vec4 outputColor;\n"
                    + "void main(){vec2 uv=gl_FragCoord.xy/1024.0;"
                    + "float wave=0.5+0.5*sin(u_time+uv.x*12.0);"
                    + "outputColor=vec4(0.08+wave*0.13,0.28+uv.y*0.25,"
                    + "0.49+wave*0.35,1.0);}");
            shaderProgram = GLES30.glCreateProgram();
            GLES30.glAttachShader(shaderProgram, vertex);
            GLES30.glAttachShader(shaderProgram, fragment);
            GLES30.glLinkProgram(shaderProgram);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(shaderProgram, GLES30.GL_LINK_STATUS, ok, 0);
            GLES30.glDeleteShader(vertex);
            GLES30.glDeleteShader(fragment);
            if (ok[0] == 0)
                throw new IllegalStateException("GLSL link failed: "
                        + GLES30.glGetProgramInfoLog(shaderProgram));
            timeUniform = GLES30.glGetUniformLocation(shaderProgram, "u_time");
            triangle.put(new float[]{-1f, -1f, 3f, -1f, -1f, 3f});
            triangle.flip();
            shaderReport = "GLSL ES 3.0 animated shader compiled and linked";
        }

        @Override public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
            try {
                prepareAnimatedGlShader();
            } catch (Throwable error) {
                shaderProgram = 0;
                shaderReport = "GLSL shader test failed: " + error.getMessage();
            }
            String version = GLES30.glGetString(GLES30.GL_VERSION);
            String vendor = GLES30.glGetString(GLES30.GL_VENDOR);
            String renderer = GLES30.glGetString(GLES30.GL_RENDERER);
            final String text = String.format(Locale.ROOT,
                    "SkyLauncher independent renderer running.\n"
                    + "OpenGL ES: %s\nGPU: %s / %s\nNative JNI: %s\n\n"
                    + "%s\n\n"
                    + "Minecraft still needs Java 21 and an "
                    + "LWJGL/OpenGL compatibility layer. "
                    + "This is an Android graphics test, not Minecraft gameplay.",
                    version, vendor, renderer,
                    NATIVE_AVAILABLE ? nativeDeviceInfo() : "native library not loaded",
                    shaderReport);
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
            if (shaderProgram != 0) {
                GLES30.glUseProgram(shaderProgram);
                GLES30.glUniform1f(timeUniform, frames / 50f);
                triangle.position(0);
                GLES30.glEnableVertexAttribArray(0);
                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, triangle);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
                GLES30.glDisableVertexAttribArray(0);
                GLES30.glUseProgram(0);
            }
        }
    }
}
