#include "engine/render/android_vulkan.h"

#ifdef __ANDROID__

#include <SDL3/SDL.h>
#include <android/native_window.h>
#include <dlfcn.h>

#include <adrenotools/bcenabler.h>
#include <adrenotools/driver.h>

#include <cstdlib>
#include <string>

#include "engine/core/log.h"

namespace pt::vk::android {
namespace {

bool g_turnip = false;

std::string Env(const char* name) {
    const char* value = std::getenv(name);
    return value ? value : "";
}

}

PFN_vkGetInstanceProcAddr LoadVulkan() {
    const std::string wanted = Env("PT_DRIVER");
    const std::string dir = Env("PT_TURNIP_DIR"), name = Env("PT_TURNIP_NAME"), hooks = Env("PT_ADRENOTOOLS_HOOKS");
    if (wanted != "system" && !dir.empty() && !name.empty() && !hooks.empty()) {
        void* library = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hooks.c_str(), dir.c_str(), name.c_str(),
                                                   nullptr, nullptr);
        if (library) {
            if (auto proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"))) {
                g_turnip = true;
                LogInfo("vulkan: Turnip (Mesa) through adrenotools from {}{}", dir, name);
                return proc;
            }
            LogError("vulkan: the Turnip library has no vkGetInstanceProcAddr; using the system driver");
        } else {
            const char* error = dlerror();
            LogError("vulkan: adrenotools could not load Turnip from {}{} (hooks in {}): {}; using the system driver", dir, name, hooks,
                     error ? error : "no error text");
        }
    } else {
        LogInfo("vulkan: system driver ({})", wanted == "system" ? "chosen in the launcher" : "no Turnip files passed by the launcher");
    }
    return nullptr;
}

const char* DriverName() { return g_turnip ? "Turnip (Mesa)" : "system"; }

bool UsingTurnip() { return g_turnip; }

void* NativeWindow(SDL_Window* window) {
    return SDL_GetPointerProperty(SDL_GetWindowProperties(window), SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER, nullptr);
}

bool CreateSurface(VkInstance instance, SDL_Window* window, VkSurfaceKHR& surface) {
    auto* native = static_cast<ANativeWindow*>(NativeWindow(window));
    if (!native) {
        LogError("vulkan: the SDL window has no ANativeWindow yet");
        return false;
    }
    VkAndroidSurfaceCreateInfoKHR info{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    info.window = native;
    const VkResult result = vkCreateAndroidSurfaceKHR(instance, &info, nullptr, &surface);
    if (result != VK_SUCCESS) {
        LogError("vulkan: vkCreateAndroidSurfaceKHR failed ({})", static_cast<int>(result));
        return false;
    }
    return true;
}

bool PrepareBcTextures(VkInstance instance, VkPhysicalDevice physical, bool& feature_flag) {
    VkPhysicalDeviceFeatures supported{};
    vkGetPhysicalDeviceFeatures(physical, &supported);
    feature_flag = supported.textureCompressionBC == VK_TRUE;
    if (feature_flag) return true;
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(physical, &props);
    /* Qualcomm encodes driverVersion as VK_MAKE_VERSION(major, minor, patch) like other vendors */
    const uint32_t major = VK_API_VERSION_MAJOR(props.driverVersion), minor = VK_API_VERSION_MINOR(props.driverVersion);
    switch (adrenotools_get_bcn_type(major, minor, props.vendorID)) {
    case ADRENOTOOLS_BCN_BLOB:
        LogInfo("vulkan: {} supports BC textures without reporting the feature", props.deviceName);
        return true;
    case ADRENOTOOLS_BCN_PATCH: {
        void* fn = reinterpret_cast<void*>(vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFormatProperties"));
        if (fn && adrenotools_patch_bcn(fn)) {
            LogInfo("vulkan: BC textures enabled in the system driver {}.{} (adrenotools BCeNabler)", major, minor);
            return true;
        }
        LogError("vulkan: patching the system driver {}.{} for BC textures failed", major, minor);
        return false;
    }
    case ADRENOTOOLS_BCN_INCOMPATIBLE:
    default:
        LogError("vulkan: {} (driver {}.{}) has no BC texture support and cannot be patched; choose the Turnip driver in the launcher",
                 props.deviceName, major, minor);
        return false;
    }
}

}

#endif
