#include "engine/core/resource_path.h"

#include <SDL3/SDL.h>

#include <cstdlib>

#ifdef __APPLE__
#include <mach-o/dyld.h>

#include <vector>
#endif

namespace pt {

std::filesystem::path ExecutableDir() {
#ifdef __ANDROID__
    /* No executable folder on Android: the launcher (android/) unpacks the game's runtime files (shaders, fonts, extras.pak)
       into the app's external files folder and names it in PT_ANDROID_ROOT; data/ (settings, saves, pt.log) and game/ (the
       three archives) live there too, where the launcher can share the log */
    if (const char* root = std::getenv("PT_ANDROID_ROOT"); root && *root) return std::filesystem::path(reinterpret_cast<const char8_t*>(root));
#endif
    const char* base = SDL_GetBasePath();
    return base ? std::filesystem::path(reinterpret_cast<const char8_t*>(base)) : std::filesystem::current_path();
}

#ifdef __APPLE__
std::filesystem::path ExecutablePath() {
    uint32_t size = 0;
    _NSGetExecutablePath(nullptr, &size);
    std::vector<char> buffer(size + 1, '\0');
    if (_NSGetExecutablePath(buffer.data(), &size) != 0) return {};
    std::error_code ec;
    const std::filesystem::path path = std::filesystem::weakly_canonical(std::filesystem::path(buffer.data()), ec);
    return ec ? std::filesystem::path(buffer.data()) : path;
}
#endif

std::filesystem::path ResourceDir(std::string_view name, const std::filesystem::path& build_dir) {
    std::error_code ec;
    const std::filesystem::path local = ExecutableDir() / name;
    if (std::filesystem::is_directory(local, ec)) {
        return local;
    }
    return build_dir;
}

}
