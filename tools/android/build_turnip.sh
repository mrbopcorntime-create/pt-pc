#!/bin/bash
# build_turnip.sh <mesa tag> <output folder>: Mesa's Turnip Vulkan driver for Adreno GPUs, cross-compiled for Android
# arm64 with the NDK in $ANDROID_NDK_LATEST_HOME (GitHub's runners), packaged the way libadrenotools loads it: the soname
# vulkan.adreno.so in vulkan.ad07XX.so (docs/android.md). The recipe follows the community's freedreno_turnip-CI builds,
# without their patches.
set -euo pipefail
tag="$1"
out="$(realpath -m "$2")"
work="${RUNNER_TEMP:-/tmp}/turnip-build"
rm -rf "$work"
mkdir -p "$work" "$out"
cd "$work"
git clone --depth 1 --branch "$tag" https://gitlab.freedesktop.org/mesa/mesa.git mesa
ndk="$ANDROID_NDK_LATEST_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cat > android-aarch64 <<CROSS
[binaries]
ar = '$ndk/llvm-ar'
c = ['$ndk/aarch64-linux-android33-clang']
cpp = ['$ndk/aarch64-linux-android33-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
c_ld = 'lld'
cpp_ld = 'lld'
strip = '$ndk/llvm-strip'
pkg-config = ['env', 'PKG_CONFIG_LIBDIR=$work/no-pkgconfig', '/usr/bin/pkg-config']
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
CROSS
cd mesa
meson setup build --cross-file ../android-aarch64 -Dbuildtype=release -Dplatforms=android -Dplatform-sdk-version=33 \
  -Dandroid-stub=true -Dgallium-drivers= -Dvulkan-drivers=freedreno -Dvulkan-beta=true -Dfreedreno-kmds=kgsl -Db_lto=true \
  -Degl=disabled
ninja -C build src/freedreno/vulkan/libvulkan_freedreno.so
cp build/src/freedreno/vulkan/libvulkan_freedreno.so "$out/vulkan.ad07XX.so"
patchelf --set-soname vulkan.adreno.so "$out/vulkan.ad07XX.so"
cp docs/license.rst "$out/Mesa-license.rst"
echo "$tag" > "$out/version.txt"
ls -la "$out"
