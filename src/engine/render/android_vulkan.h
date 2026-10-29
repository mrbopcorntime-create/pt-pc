#pragma once
/* Android: which Vulkan driver the game runs on, and the window surface (docs/android.md).

   Qualcomm's own driver does not expose the BC texture formats every P.T. texture uses, so on Adreno GPUs the game loads
   Mesa's Turnip through libadrenotools (the app ships it and puts it in its private files folder). The launcher passes
   the choice in environment variables before SDL_main runs:

     PT_DRIVER             turnip (default when the files below exist) or system
     PT_TURNIP_DIR         the private folder holding the Turnip library (ends with '/')
     PT_TURNIP_NAME        its file name
     PT_ADRENOTOOLS_HOOKS  the app's nativeLibraryDir, where adrenotools' hook libraries are (ends with '/')

   With the system driver on an Adreno GPU whose driver adrenotools can patch, BCeNabler makes the BC formats usable. */
#include <volk.h>

struct SDL_Window;

namespace pt::vk::android {

/* vkGetInstanceProcAddr of the driver to use, or nullptr for volk's default (the system libvulkan.so) */
PFN_vkGetInstanceProcAddr LoadVulkan();
/* "Turnip (Mesa)" or "system" for the log and the settings page */
const char* DriverName();
bool UsingTurnip();
/* the surface for SDL's window through the loaded driver (SDL's own Vulkan library would be a different one) */
bool CreateSurface(VkInstance instance, SDL_Window* window, VkSurfaceKHR& surface);
/* after the physical device is chosen: whether BC textures work, patching Qualcomm's driver if it can be patched. When this
   returns true and `feature_flag` is false, the formats work but the device feature must not be requested. */
bool PrepareBcTextures(VkInstance instance, VkPhysicalDevice physical, bool& feature_flag);

}
