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
            mSkyStarting.setText("SkyLauncher\\nPreparing Minecraft...");
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
                    .setTitle("Add Minecraft account")
                    .setMessage("Sign in once to the integrated game engine. "
                            + "Afterwards, Minecraft starts directly from SkyLauncher.")
                    .setPositiveButton("Sign in", (dialog, button) ->
                            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true))
                    .setNegativeButton("Back", (dialog, button) -> finish())
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


def patch_auto_installer_return(engine: Path):
    """Make the native JAR installer return control after the Java VM exits.

    The modloader Activity verifies the actual installed Minecraft version
    before allowing direct game launch; closing an installer without success
    never causes a vanilla fallback.
    """
    source = engine / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java"
    txt = source.read_text(encoding="utf-8")
    old = "            JREUtils.launchJavaVM(this, runtime,null,javaArgList, LauncherPreferences.PREF_CUSTOM_JAVA_ARGS);"
    new = old + """
            if (getIntent().getBooleanExtra("skylauncher_auto_finish", false)) {
                runOnUiThread(() -> {
                    setResult(RESULT_OK);
                    finish();
                });
            }"""
    txt = patch_one(txt, old, new, "finish native modloader installer after Java exits")
    # The JAR's own class version may be Java 8 even if its Forge/NeoForge
    # installer needs Java 17 or 21. Respect SkyLauncher's Minecraft target.
    anchor = "            launchJavaRuntime(selectedRuntime, modFile, argList);"
    inserted = """            int minimumJava = getIntent().getIntExtra("skylauncher_java_requirement", 0);
            if (minimumJava > 0 && (selectedRuntime == null
                    || selectedRuntime.javaVersion < minimumJava)) {
                String nearest = MultiRTUtils.getNearestJreName(minimumJava);
                if (nearest == null) {
                    finalErrorDialog("This modloader needs Java " + minimumJava
                            + " or later. Install that runtime in SkyLauncher first.");
                    return;
                }
                selectedRuntime = MultiRTUtils.forceReread(nearest);
                if (selectedRuntime == null || selectedRuntime.javaVersion < minimumJava) {
                    finalErrorDialog("No compatible Java " + minimumJava
                            + " runtime is installed.");
                    return;
                }
            }
"""
    txt = patch_one(txt, anchor, inserted + anchor,
                    "version-aware modloader Java runtime")
    # Download Java 17/21 when a modern loader requires it, instead of
    # launching its installer with the bundled Java 8.
    before_select = "            Runtime selectedRuntime;"
    select_patch = """            int skyRequiredJava = getIntent().getIntExtra("skylauncher_java_requirement", 0);
            if (skyRequiredJava > 0
                    && MultiRTUtils.getNearestJreName(skyRequiredJava) == null) {
                try {
                    NewJREUtil.tryDownloadRuntime(this, skyRequiredJava);
                } catch (RuntimeException installError) {
                    finalErrorDialog("Could not install Java " + skyRequiredJava + ": "
                            + installError.getMessage());
                    return;
                }
            }
"""
    txt = patch_one(txt, before_select, select_patch + before_select,
                    "automatic JRE installation for game modloaders")
    source.write_text(txt, encoding="utf-8")

    # The native JVM launcher normally kills the Android process after Java exits.
    # Save its real exit status *before* that happens; code after launchJavaVM
    # cannot reliably announce a successful modloader installation.
    jre_utils = engine / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java"
    jre_text = jre_utils.read_text(encoding="utf-8")
    jre_text = patch_one(jre_text,
        '        Logger.appendToLog("Java Exit code: " + exitCode);',
        '''        Logger.appendToLog("Java Exit code: " + exitCode);
        if (activity.getIntent().getBooleanExtra("skylauncher_auto_finish", false)) {
            activity.getSharedPreferences("skylauncher_installer_exit",
                    android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt("last_exit_code", exitCode)
                    .putLong("last_exit_at", System.currentTimeMillis())
                    .commit();
        }''',
        "capture native modloader installer exit status before process termination")
    jre_utils.write_text(jre_text, encoding="utf-8")

    java_util = engine / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/NewJREUtil.java"
    util_text = java_util.read_text(encoding="utf-8")
    util_text = patch_one(util_text,
        "    private static void tryDownloadRuntime(Context activity, int javaVersion){",
        "    public static void tryDownloadRuntime(Context activity, int javaVersion){",
        "expose native Java runtime downloader to the integrated modloader")
    java_util.write_text(util_text, encoding="utf-8")



def patch_modded_minecraft_classpath(engine: Path):
    """Put the generated Forge/NeoForge Minecraft client on the JVM classpath.

    NeoForge needs the generated client-<mc>-<neoform>-srg.jar from
    libraries/net/minecraft/client/. It contains mapped Minecraft classes,
    unlike the NeoForge patch-only *-client.jar.
    """
    source = engine / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java"
    txt = source.read_text(encoding="utf-8")
    signature = "    public static String generateLaunchClasspath(JMinecraftVersionList.Version info, String actualname) {"
    helper = """    private static String skylauncherResolveClientJar(JMinecraftVersionList.Version info,
                                                       String installedVersion) {
        if (installedVersion.startsWith("neoforge-")) {
            // NeoForge's production client provider consumes client-<mc>-<neoform>-srg.jar.
            // neoforge-<loader>-client.jar contains patches but is NOT a
            // replacement for Minecraft's complete mapped (SRG) client.
            String mcVersion = info.inheritsFrom;
            if (mcVersion == null || mcVersion.isEmpty()) {
                throw new IllegalStateException("NeoForge Minecraft base version is missing.");
            }
            try {
                File mappedClient = org.fathomstudios.skylauncher.android
                        .SkyEngineClientArtifacts.requireNeoForgeClient(mcVersion, installedVersion);
                Logger.appendToLog("SkyLauncher NeoForge client: "
                        + mappedClient.getAbsolutePath());
                return mappedClient.getAbsolutePath();
            } catch (Exception invalid) {
                throw new IllegalStateException("NeoForge Minecraft client is incomplete: "
                        + invalid.getMessage(), invalid);
            }
        }

        if (installedVersion.startsWith("forge-")) {
            String loaderVersion = installedVersion.substring("forge-".length());
            File patchedClient = new File(DIR_HOME_LIBRARY, "net/minecraftforge/forge/"
                    + loaderVersion + "/forge-" + loaderVersion + "-client.jar");
            if (patchedClient.isFile() && patchedClient.length() >= 4096)
                return patchedClient.getAbsolutePath();
        }

        File installedClient = new File(getClientClasspath(installedVersion));
        if (installedClient.isFile()) return installedClient.getAbsolutePath();

        // Fabric and Quilt typically inherit the Minecraft vanilla client.
        if (info.inheritsFrom != null && !info.inheritsFrom.isEmpty()) {
            File parentClient = new File(getClientClasspath(info.inheritsFrom));
            if (parentClient.isFile()) return parentClient.getAbsolutePath();
        }

        throw new IllegalStateException("Minecraft client JAR is missing for "
                + installedVersion + ". Reinstall the selected Minecraft version.");
    }

"""
    txt = patch_one(txt, signature, helper + signature,
                    "Minecraft client JAR resolver")
    txt = patch_one(txt,
                    "        launchClasspath.append(getClientClasspath(actualname));",
                    "        launchClasspath.append(skylauncherResolveClientJar(info, actualname));",
                    "Minecraft client classpath entry")
    source.write_text(txt, encoding="utf-8")


def patch_cacio_bootclasspath(engine: Path):
    """Avoid passing an invalid empty -Xbootclasspath/p or /a VM argument."""
    source = engine / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java"
    txt = source.read_text(encoding="utf-8")
    anchor = '        javaArgList.add(cacioClasspath.toString());'
    txt = patch_one(txt, anchor,
                    '        if (cacioClasspath.indexOf(":") >= 0) {\n'
                    '            javaArgList.add(cacioClasspath.toString());\n'
                    '        }',
                    "empty Caciocavallo bootclasspath")
    source.write_text(txt, encoding="utf-8")

