# Android (docs/android.md), included from CMakeLists.txt after the pt target (libmain.so here) exists.
#
# libadrenotools (BSD-2-Clause, Billy Laws) loads a custom Vulkan driver into the process on Qualcomm devices: the game
# bundles Mesa's Turnip, which supports the BC texture formats the renderer needs and Qualcomm's own driver does not
# expose. It can also patch Qualcomm's driver to accept BC textures (BCeNabler) when the player picks the system driver.
# Its hook libraries (main_hook, file_redirect_hook, gsl_alloc_hook, hook_impl) must sit in the app's nativeLibraryDir,
# so the Gradle build lists them as targets beside pt (android/app/build.gradle).
include(FetchContent)
FetchContent_Declare(adrenotools GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
  GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80 GIT_SUBMODULES_RECURSE TRUE)
FetchContent_GetProperties(adrenotools)
if(NOT adrenotools_POPULATED)
  FetchContent_Populate(adrenotools)
  add_subdirectory(${adrenotools_SOURCE_DIR} ${adrenotools_BINARY_DIR})
endif()

target_link_libraries(pt_engine PUBLIC adrenotools android log)
target_compile_definitions(pt_engine PUBLIC PT_ADRENOTOOLS=1)
# Shared objects in an APK are loaded by soname from the app's library folder; the game links the C++ runtime statically
# (ANDROID_STL=c++_static) so only libmain.so and the hooks ship.
target_link_options(pt PRIVATE "-Wl,--no-undefined")
