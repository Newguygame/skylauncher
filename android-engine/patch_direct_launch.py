#!/usr/bin/env python3
"""Attach SkyLauncher profile request to native engine and skip its launcher menu."""

from pathlib import Path


def patch_one(text, old, new, context):
    if old not in text:
        raise RuntimeError("Missing integration anchor " + context)
    return text.replace(old, new, 1)


def patch_engine_startup(engine: Path):
    bootstrap = engine / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/TestStorageActivity.java"
    txt = bootstrap.read_text(encoding="utf-8")
    txt = patch_one(txt, "        startActivity(intent);",
        """        if (getIntent().getBooleanExtra(
                org.fathomstudios.skylauncher.android.SkyEngineProfileBridge.EXTRA_DIRECT, false)) {
            intent.putExtra(
                    org.fathomstudios.skylauncher.android.SkyEngineProfileBridge.EXTRA_DIRECT, true);
        }
        startActivity(intent);""", "bootstrap intent extras")
    bootstrap.write_text(txt, encoding="utf-8")

    activity = engine / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/LauncherActivity.java"
    txt = activity.read_text(encoding="utf-8")
    txt = patch_one(txt, "    private FragmentContainerView mFragmentView;",
        """    private boolean mSkyDirectStart;
    private boolean mSkyDirectConsumed;
    private android.widget.TextView mSkyStarting;
    private FragmentContainerView mFragmentView;""", "direct start fields")
    txt = patch_one(txt, "        setContentView(R.layout.activity_pojav_launcher);",
        """        mSkyDirectStart = getIntent().getBooleanExtra(
                org.fathomstudios.skylauncher.android.SkyEngineProfileBridge.EXTRA_DIRECT, false);
        setContentView(R.layout.activity_pojav_launcher);""", "direct start launch intent")
    txt = patch_one(txt, "        bindViews();",
        """        bindViews();
        if (mSkyDirectStart) {
            mFragmentView.setVisibility(android.view.View.INVISIBLE);
            mSettingsButton.setVisibility(android.view.View.INVISIBLE);
            mAccountSpinner.setVisibility(android.view.View.INVISIBLE);
            android.widget.FrameLayout frame = findViewById(android.R.id.content);
            mSkyStarting = new android.widget.TextView(this);
            mSkyStarting.setText("SkyLauncher\\nMinecraft wordt voorbereid...");
            mSkyStarting.setTextColor(android.graphics.Color.WHITE);
            mSkyStarting.setGravity(android.view.Gravity.CENTER);
            mSkyStarting.setTextSize(22);
            mSkyStarting.setBackgroundColor(0xff101c30);
            frame.addView(mSkyStarting, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            // Offline fallback: installed versions can launch without a manifest.
            mFragmentView.postDelayed(this::startSkyDirectPlay, 12000);
        }""", "hide engine menu")
    txt = patch_one(txt,
        "        new AsyncVersionList().getVersionList(versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions), false);",
        """        new AsyncVersionList().getVersionList(versions -> {
            ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions);
            if (mSkyDirectStart) {
                mFragmentView.postDelayed(this::startSkyDirectPlay, 250);
            }
        }, false);""", "manifest callback")

    launch = '''    private void startSkyDirectPlay() {
        if (!mSkyDirectStart || mSkyDirectConsumed || isFinishing()) return;
        if (ProgressLayout.hasProcesses()) {
            mFragmentView.postDelayed(this::startSkyDirectPlay, 600);
            return;
        }
        mSkyDirectConsumed = true;
        if (mAccountSpinner == null || mAccountSpinner.getSelectedAccount() == null) {
            // One-time login is still required: SkyLauncher and the engine
            // do not yet share Microsoft's tokens.
            if (mSkyStarting != null) mSkyStarting.setVisibility(android.view.View.GONE);
            mFragmentView.setVisibility(android.view.View.VISIBLE);
            mSettingsButton.setVisibility(android.view.View.VISIBLE);
            mAccountSpinner.setVisibility(android.view.View.VISIBLE);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Minecraft-account toevoegen")
                    .setMessage("Meld je eenmaal aan bij de geïntegreerde engine. "
                            + "Daarna start Minecraft direct vanuit SkyLauncher.")
                    .setPositiveButton("Aanmelden", (dialog, button) ->
                            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true))
                    .setNegativeButton("Terug", (dialog, button) -> finish())
                    .show();
            return;
        }
        if (mSkyStarting != null) mSkyStarting.setVisibility(android.view.View.GONE);
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

'''
    txt = patch_one(txt, "    @Override\n    protected void onResume() {",
                    launch + "    @Override\n    protected void onResume() {", "direct launch handler")
    activity.write_text(txt, encoding="utf-8")
