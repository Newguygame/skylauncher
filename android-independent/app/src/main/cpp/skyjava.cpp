#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <vector>
#include <memory>
#include <algorithm>

// SkyLauncher's independent JNI bootstrap for Android ARM64 HotSpot.
// This does NOT embed code from Amethyst, Pojav or another launcher.
// The Android-compatible Java 21 runtime must be separately packaged and
// verified, and OpenGL/LWJGL native libraries must be available.
//
// A JVM is never started unless the Java runtime's ELF and modules are
// present. Minecraft must run in its own Android process because HotSpot
// lifecycle/native libraries must not destabilize the launcher process.

namespace {
    std::string jstringToString(JNIEnv* env, jstring str) {
        if (str == nullptr) return "";
        const char* raw = env->GetStringUTFChars(str, nullptr);
        if (raw == nullptr) return "";
        std::string value(raw);
        env->ReleaseStringUTFChars(str, raw);
        return value;
    }

    std::string safeError(const std::string& error) {
        if (error.size() <= 1024) return error;
        return error.substr(0, 1024);
    }

    bool regularFile(const std::string& path) {
        struct stat st{};
        return !path.empty() && stat(path.c_str(), &st) == 0
                && S_ISREG(st.st_mode) && st.st_size > 0;
    }

    bool isAndroidAarch64Elf(const std::string& path) {
        FILE* file = fopen(path.c_str(), "rb");
        if (!file) return false;
        unsigned char header[20]{};
        const auto count = fread(header, 1, sizeof(header), file);
        fclose(file);
        if (count != sizeof(header)) return false;
        // ELF64 little-endian AArch64. The native linker ultimately checks
        // ABI and symbol compatibility.
        return header[0] == 0x7f && header[1] == 'E'
                && header[2] == 'L' && header[3] == 'F'
                && header[4] == 2 && header[5] == 1
                && header[18] == 183 && header[19] == 0;
    }

    bool hasPendingException(JNIEnv* env, std::string& error) {
        if (!env->ExceptionCheck()) return false;
        env->ExceptionDescribe(); // Into Android's native log, no credentials.
        env->ExceptionClear();
        error = "Minecraft Java raised an exception; inspect the device logcat.";
        return true;
    }

    std::string run(JNIEnv* androidEnv, const std::string& home,
                    const std::string& classpath, const std::string& mainClass,
                    const std::string& gameDir, jobjectArray arguments,
                    int ramMb, const std::string& nativeDir) {
        // Runtime libraries are never downloaded directly into a mutable
        // executable path by this function. Only allow an explicitly
        // installed, verified runtime in the app's private files directory.
        const std::string jvmPath = home + "/lib/server/libjvm.so";
        const std::string modulesPath = home + "/lib/modules";
        if (!regularFile(jvmPath) || !regularFile(modulesPath)) {
            return "JAVA_RUNTIME_MISSING: Android-compatible OpenJDK 21 "
                    "with lib/server/libjvm.so and lib/modules is required.";
        }
        if (!isAndroidAarch64Elf(jvmPath)) {
            return "JAVA_RUNTIME_ABI: OpenJDK libjvm.so must be an ARM64 ELF.";
        }
        if (classpath.empty() || mainClass.empty() || gameDir.empty())
            return "INVALID_LAUNCH_PLAN: classpath, main class and game directory are required.";
        if (ramMb < 256 || ramMb > 8192)
            return "INVALID_RAM: heap memory must be 256-8192 MB.";

        // Android's ART JVM and HotSpot are distinct runtimes; the result is
        // contained in the :minecraft app process, never the UI process.
        // Android linker namespaces / native ABI dependencies can still
        // reject the runtime, and this is reported explicitly.
        void* handle = dlopen(jvmPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (handle == nullptr) {
            const char* why = dlerror();
            return "JAVA_LINKER_FAILURE: " + safeError(why ? why : "unknown dlopen error");
        }
        using CreateJavaVM = jint(JNICALL *)(JavaVM**, void**, void*);
        auto create = reinterpret_cast<CreateJavaVM>(dlsym(handle, "JNI_CreateJavaVM"));
        if (!create) {
            dlclose(handle);
            return "JAVA_RUNTIME_INVALID: JNI_CreateJavaVM symbol missing.";
        }
        // Do not modify process-wide JAVA_HOME or LD_LIBRARY_PATH until the
        // runtime is known to exist. These are scoped to the child process.
        setenv("JAVA_HOME", home.c_str(), 1);
        setenv("HOME", gameDir.c_str(), 1);
        setenv("TMPDIR", (home + "/tmp").c_str(), 0);
        if (chdir(gameDir.c_str()) != 0) {
            dlclose(handle);
            return "GAME_DIRECTORY: cannot change to profile directory.";
        }

        std::vector<std::string> settings = {
                "-Xms256m",
                "-Xmx" + std::to_string(ramMb) + "m",
                "-Djava.home=" + home,
                "-Djava.class.path=" + classpath,
                "-Djava.library.path=" + nativeDir,
                "-Dorg.lwjgl.librarypath=" + nativeDir,
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativeDir,
                "-Duser.home=" + gameDir,
                "-Dfile.encoding=UTF-8",
                "-Djava.awt.headless=true",
                "-Dsun.java2d.opengl=false",
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED"
        };
        std::vector<JavaVMOption> options(settings.size());
        for (size_t i = 0; i < settings.size(); i++)
            options[i].optionString = settings[i].data();

        JavaVMInitArgs vmArgs{};
        vmArgs.version = JNI_VERSION_1_6; // Android NDK JNI header exposes 1.6.
        vmArgs.nOptions = static_cast<jint>(options.size());
        vmArgs.options = options.data();
        vmArgs.ignoreUnrecognized = JNI_FALSE;

        JavaVM* gameVm = nullptr;
        JNIEnv* gameEnv = nullptr;
        const jint status = create(&gameVm, reinterpret_cast<void**>(&gameEnv), &vmArgs);
        if (status != JNI_OK || gameEnv == nullptr || gameVm == nullptr) {
            // HotSpot may already have partially initialized; do not dlclose
            // the runtime after a failed create in the same process.
            return "JAVA_VM_CREATE_FAILED: JNI_CreateJavaVM returned " + std::to_string(status);
        }
        std::string mainPath = mainClass;
        std::replace(mainPath.begin(), mainPath.end(), '.', '/');
        std::string exception;
        jclass klass = gameEnv->FindClass(mainPath.c_str());
        if (hasPendingException(gameEnv, exception) || klass == nullptr)
            return "MAIN_CLASS_FAILURE: " + exception;
        const jmethodID main = gameEnv->GetStaticMethodID(
                klass, "main", "([Ljava/lang/String;)V");
        if (hasPendingException(gameEnv, exception) || main == nullptr)
            return "MAIN_METHOD_FAILURE: " + exception;

        const jsize length = androidEnv->GetArrayLength(arguments);
        jclass javaString = gameEnv->FindClass("java/lang/String");
        if (hasPendingException(gameEnv, exception)) return exception;
        jobjectArray args = gameEnv->NewObjectArray(length, javaString, nullptr);
        if (hasPendingException(gameEnv, exception)) return exception;
        for (jsize i = 0; i < length; ++i) {
            auto item = static_cast<jstring>(androidEnv->GetObjectArrayElement(arguments, i));
            std::string value = jstringToString(androidEnv, item);
            if (item) androidEnv->DeleteLocalRef(item);
            jstring converted = gameEnv->NewStringUTF(value.c_str());
            if (hasPendingException(gameEnv, exception)) return exception;
            gameEnv->SetObjectArrayElement(args, i, converted);
            gameEnv->DeleteLocalRef(converted);
            if (hasPendingException(gameEnv, exception)) return exception;
        }
        // At this point Minecraft's own main executes inside HotSpot.
        // Missing GLFW/LWJGL native bindings are reported rather than hidden.
        gameEnv->CallStaticVoidMethod(klass, main, args);
        if (hasPendingException(gameEnv, exception))
            return "GAME_EXCEPTION: " + exception;
        return "JAVA_MAIN_RETURNED: Minecraft main method returned.";
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_fathomstudios_skylauncher_android_SkyMinecraftGameActivity_nativeLaunchMinecraft(
        JNIEnv* env, jclass, jstring jHome, jstring jClasspath,
        jstring jMainClass, jstring jGameDir, jobjectArray jArguments,
        jint heapMb, jstring jNativeDir) {
    try {
        const std::string output = run(env, jstringToString(env, jHome),
                jstringToString(env, jClasspath),
                jstringToString(env, jMainClass),
                jstringToString(env, jGameDir),
                jArguments, heapMb, jstringToString(env, jNativeDir));
        return env->NewStringUTF(output.c_str());
    } catch (const std::exception& err) {
        std::string msg = "NATIVE_EXCEPTION: " + safeError(err.what());
        return env->NewStringUTF(msg.c_str());
    }
}
