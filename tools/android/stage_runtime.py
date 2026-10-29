"""Stage what the Android APK carries besides libmain.so (docs/android.md).

  <out>/runtime/       unpacked by the launcher into the game's program folder: shaders/*.spv (compiled here with the
                       host's glslc, the flags CMakeLists.txt uses), fonts/, extras.pak and licences/
  <out>/turnip/        Mesa's Turnip driver for adrenotools, when --turnip names one

and SDL's Java sources (android-project/.../org/libsdl/app) at the SDL tag cmake/Dependencies.cmake pins, so the Java side
always matches the native SDL.
"""
import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sdl_tag():
    text = (ROOT / "cmake" / "Dependencies.cmake").read_text(encoding="utf-8")
    m = re.search(r"FetchContent_Declare\(sdl3 GIT_REPOSITORY (\S+) GIT_TAG (\S+)", text)
    if not m:
        sys.exit("cannot find the SDL pin in cmake/Dependencies.cmake")
    return m.group(1), m.group(2)


def stage_sdl(dest: Path):
    url, tag = sdl_tag()
    stamp = dest / ".tag"
    if stamp.is_file() and stamp.read_text().strip() == tag:
        return
    if dest.exists():
        shutil.rmtree(dest)
    subprocess.run(["git", "clone", "--depth", "1", "--branch", tag, url, str(dest)], check=True)
    stamp.write_text(tag + "\n")


def compile_shaders(glslc: str, out: Path):
    out.mkdir(parents=True, exist_ok=True)
    shaders = sorted(p for p in (ROOT / "shaders").iterdir() if p.suffix in (".vert", ".frag", ".comp"))
    for shader in shaders:
        target = out / (shader.name + ".spv")
        if target.is_file() and target.stat().st_mtime >= max(shader.stat().st_mtime, newest_include()):
            continue
        subprocess.run([glslc, "--target-env=vulkan1.3", "-O", str(shader), "-o", str(target)], check=True)
    print(f"shaders: {len(shaders)} in {out}")


_newest = None


def newest_include():
    global _newest
    if _newest is None:
        _newest = max(p.stat().st_mtime for p in (ROOT / "shaders").rglob("*") if p.is_file())
    return _newest


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--sdl", type=Path, required=True, help="folder for SDL's source (its Java files are compiled into the APK)")
    ap.add_argument("--glslc", default=shutil.which("glslc") or "glslc")
    ap.add_argument("--turnip", type=Path, help="Mesa Turnip libvulkan_freedreno.so (soname vulkan.adreno.so) to bundle")
    ap.add_argument("--license", action="append", default=[], metavar="NAME=PATH", help="another notice for licences/ (repeatable)")
    a = ap.parse_args()

    stage_sdl(a.sdl)
    runtime = a.out / "runtime"
    compile_shaders(a.glslc, runtime / "shaders")
    fonts = runtime / "fonts"
    if fonts.exists():
        shutil.rmtree(fonts)
    shutil.copytree(ROOT / "assets" / "fonts", fonts)
    shutil.copy2(ROOT / "assets" / "extras.pak", runtime / "extras.pak")
    licenses = runtime / "licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "LICENSE", licenses / "pt-port-MIT.txt")
    for notice in (ROOT / "docs" / "licenses").glob("*.txt"):
        shutil.copy2(notice, licenses / notice.name)
    shutil.copy2(ROOT / "shaders" / "fsr1" / "LICENSE-FidelityFX-FSR.txt", licenses / "AMD-FidelityFX-FSR1-MIT.txt")
    shutil.copy2(a.sdl / "LICENSE.txt", licenses / "SDL3-zlib.txt")
    for entry in a.license:
        name, _, path = entry.partition("=")
        shutil.copy2(path, licenses / name)

    turnip = a.out / "turnip"
    if a.turnip:
        turnip.mkdir(parents=True, exist_ok=True)
        shutil.copy2(a.turnip, turnip / "vulkan.ad07XX.so")
        print(f"turnip: {a.turnip}")
    elif turnip.exists():
        shutil.rmtree(turnip)


if __name__ == "__main__":
    main()
