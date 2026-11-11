#include <jni.h>
#include <android/api-level.h>
#include <GLES3/gl3.h>
#include <string>
#include <sstream>

extern "C" JNIEXPORT jstring JNICALL
Java_org_fathomstudios_skylauncher_android_SkyGraphicsActivity_nativeDeviceInfo(
        JNIEnv* env, jclass /* type */) {
    std::ostringstream description;
    description << "SkyLauncher ARM" << (sizeof(void*) * 8)
                << " / Android API " << android_get_device_api_level();
    const auto* version = glGetString(GL_VERSION);
    if (version == nullptr) description << " / GL context unavailable";
    else description << " / native GLES active";
    return env->NewStringUTF(description.str().c_str());
}
