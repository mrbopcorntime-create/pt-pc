# Android

An arm64 Android build of the port for phones with a Vulkan 1.3 GPU, aimed at Snapdragon (Adreno) phones such as the
Galaxy S24 Ultra. It is a personal fork addition, built by `.github/workflows/android.yml` and published as the
`android-latest` pre-release. Like the PC port it contains no game data: it runs on the player's own fake PKG of P.T.

## Installing

1. Install `PT-Port-Android.apk` from the `android-latest` release (allow installing from the browser when asked).
2. Copy your fake PKG of P.T. (made from your own PS4 dump) to the phone.
3. Open **P.T. Port**, tap **Import game from fake PKG...** and pick the PKG. The launcher writes the three archives the
   game needs (`chunk1.psarc`, `texture.qar`, `pathid_list_ps4.bin`, about 1.3 GB) into the app's storage. The PKG can be
   deleted afterwards. Retail (PlayStation Store) packages are refused: they are encrypted for the console that owns them.
4. Tap **Play**.

Updates install over the previous APK and keep the imported files and saves (every build is signed with the same key,
`android/ptport-sideload.keystore`, committed on purpose for these sideload builds). Uninstalling deletes them.

## Controls

A Bluetooth or USB controller (DualSense, DualShock 4, Xbox and others SDL knows) works as on PC. Back/Share/View opens the
PC settings page (graphics, FSR 1, sound, accessibility). The voice moment has no speech model on Android: clicking both
sticks together stands in for saying the word (J on a keyboard). Touch controls are not implemented yet.

## Graphics driver

Every P.T. texture is BC-compressed. Qualcomm's own Vulkan driver does not expose the BC formats, so on Adreno GPUs the app
loads Mesa's open-source Turnip driver into the game with libadrenotools (BSD-2-Clause). CI builds Turnip from the newest
stable Mesa release (`android.yml`, job `turnip`); the launcher copies it into the app's private files folder, where
adrenotools may load it (`src/engine/render/android_vulkan.cpp`). **Graphics driver: System driver** in the launcher uses
the phone's driver instead; on Adreno drivers adrenotools knows, it patches in BC support (BCeNabler), otherwise the game
reports in `pt.log` that the textures cannot be used. Non-Qualcomm GPUs (Exynos Xclipse, Mali) always use the system
driver and need it to report BC textures.

## Resolution and upscaling

The swapchain is made at most **Render resolution cap** lines high (1080 by default) and the compositor scales it to the
screen. Within it the game renders at a lower size and upscales with **AMD FSR 1** (`shaders/fsr1_easu.frag`,
`shaders/fsr1_resolve.frag`, AMD's MIT `ffx_fsr1.h`): edge-adaptive spatial upsampling (EASU) followed by contrast-adaptive
sharpening (RCAS). FSR 1 is spatial, so the temporal inputs, jitter and dither cycling of FSR 3/DLSS are off with it and
the original's FXAA stays on. The Android defaults are FSR 1 Balanced (1/1.7 per axis) with sharpness 3 of 10; the PC
settings page changes both, and FSR 1 is also available on PC.

## Files

| Where | What |
|---|---|
| `Android/data/com.ptport.android/files/` | the program folder (`PT_ANDROID_ROOT`): shaders, fonts, `extras.pak`, licences |
| `.../files/game/` | the archives imported from the PKG |
| `.../files/data/` | `pt.ini`, saves, `pt.log` (the launcher's **Share log** sends it) |
| app private `files/turnip/` | the Turnip driver |

## Building

CI is the supported route. Locally: Android SDK with NDK 28.2.13676358, CMake 3.31.6 (`cmake.dir` in
`android/local.properties`), Ninja, `glslc` and Python 3 on the PATH, then `gradle -p android assembleRelease`. Without
`PT_TURNIP_SO` the APK has no Turnip and uses the system driver.

## Code

- `cmake/Android.cmake`, `CMakeLists.txt` (`pt` is `libmain.so`), `cmake/Dependencies.cmake` (SDL shared, Khronos Vulkan
  headers, `PT_VOICE` off)
- `src/engine/render/android_vulkan.*`: Turnip through adrenotools, the Android surface, BC handling; `vk_context.cpp`:
  swapchain rotation (identity transform, the compositor rotates) and the resolution cap
- `src/engine/core/resource_path.cpp`: the program folder from `PT_ANDROID_ROOT`
- `android/`: the launcher (`LauncherActivity`), the fake PKG reader (`PkgExtractor`, after LibOrbisPkg, LGPL), the SDL
  activity (`PtActivity`); `tools/android/stage_runtime.py` stages the assets
