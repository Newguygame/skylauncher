#include <jni.h>
#include <android/api-level.h>
#include <GLES3/gl3.h>
#include <vulkan/vulkan.h>
#include <algorithm>
#include <string>
#include <vector>
#include <sstream>
#include <iomanip>

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

/**
 * Independent Vulkan capability probe. Queries the Android system's Vulkan
 * loader and hardware directly. Does not copy any graphics driver/renderer
 * from an existing Minecraft launcher or claim to provide Zink.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_org_fathomstudios_skylauncher_android_SkyGraphicsActivity_nativeVulkanReport(
        JNIEnv* env, jclass /* type */) {
    std::ostringstream output;
    uint32_t loaderVersion = VK_API_VERSION_1_0;
    const auto versionFn = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    if (versionFn != nullptr) {
        VkResult ret = versionFn(&loaderVersion);
        if (ret != VK_SUCCESS) {
            output << "Vulkan loader version probe failed: " << ret;
            return env->NewStringUTF(output.str().c_str());
        }
    }
    auto versionString = [](uint32_t version) {
        std::ostringstream stream;
        stream << VK_VERSION_MAJOR(version) << "."
               << VK_VERSION_MINOR(version) << "."
               << VK_VERSION_PATCH(version);
        return stream.str();
    };
    output << "Vulkan loader: " << versionString(loaderVersion) << "\n";
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "SkyLauncher Independent Android";
    app.applicationVersion = 1;
    app.pEngineName = "SkyLauncher Native GPU Probe";
    app.engineVersion = 1;
    app.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&ci, nullptr, &instance);
    if (result != VK_SUCCESS) {
        output << "Could not create Vulkan instance: " << result;
        return env->NewStringUTF(output.str().c_str());
    }

    uint32_t count = 0;
    result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (result != VK_SUCCESS || count == 0) {
        output << "No accessible Vulkan GPU: " << result;
        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(output.str().c_str());
    }
    std::vector<VkPhysicalDevice> devices(count);
    result = vkEnumeratePhysicalDevices(instance, &count, devices.data());
    if (result != VK_SUCCESS) {
        output << "Vulkan device enumeration failed: " << result;
        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(output.str().c_str());
    }
    output << "Detected Vulkan GPU(s): " << count << "\n";
    for (uint32_t i = 0; i < count; i++) {
        VkPhysicalDeviceProperties props{};
        VkPhysicalDeviceFeatures features{};
        vkGetPhysicalDeviceProperties(devices[i], &props);
        vkGetPhysicalDeviceFeatures(devices[i], &features);
        output << "\n" << props.deviceName
               << "\nDriver API: " << versionString(props.apiVersion)
               << "\nGeometry shaders: " << (features.geometryShader ? "yes" : "no")
               << "\nDual-source blending: " << (features.dualSrcBlend ? "yes" : "no")
               << "\nShader clip distance: " << (features.shaderClipDistance ? "yes" : "no")
               << "\nCompute shaders: " << (features.shaderInt64 ? "64-bit supported" : "available via Vulkan 1.0")
               << "\nAnisotropic filtering: " << (features.samplerAnisotropy ? "yes" : "no");
        uint32_t extCount = 0;
        VkResult extResult = vkEnumerateDeviceExtensionProperties(devices[i], nullptr,
                                                                  &extCount, nullptr);
        if (extResult == VK_SUCCESS && extCount > 0) {
            std::vector<VkExtensionProperties> extensions(extCount);
            extResult = vkEnumerateDeviceExtensionProperties(devices[i], nullptr,
                                                               &extCount, extensions.data());
            if (extResult == VK_SUCCESS) {
                auto has = [&extensions](const char* name) {
                    return std::any_of(extensions.begin(), extensions.end(),
                        [name](const VkExtensionProperties& item) {
                            return std::string(item.extensionName) == name;
                        });
                };
                output << "\nTransform feedback ext: "
                       << (has("VK_EXT_transform_feedback") ? "yes" : "no")
                       << "\nVertex divisor ext: "
                       << (has("VK_EXT_vertex_attribute_divisor") ||
                           has("VK_KHR_vertex_attribute_divisor") ? "yes" : "no");
            }
        }
    }
    vkDestroyInstance(instance, nullptr);
    output << "\n\nThese are hardware facts, not proof of Zink/Iris shader compatibility."
           << "\nFull shaderpacks need an integrated desktop-GL-on-Vulkan "
           << "driver plus the correct Minecraft shader mod.";
    return env->NewStringUTF(output.str().c_str());
}
