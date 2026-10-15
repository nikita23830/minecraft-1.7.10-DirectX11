#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
if [ -n "$LLVM_MINGW" ]; then CXX="$LLVM_MINGW/bin/clang++"; else CXX="$(command -v clang++ || true)"; fi
[ -n "$CXX" ] && { [ -x "$CXX" ] || [ -x "$CXX.exe" ]; } || { echo "clang++ nicht gefunden (LLVM_MINGW setzen oder llvm-mingw in PATH aufnehmen)"; exit 1; }
[ -n "$JAVA_HOME" ] && [ -f "$JAVA_HOME/include/jni.h" ] || { echo "\$JAVA_HOME/include/jni.h nicht gefunden (JAVA_HOME auf ein JDK 21 setzen)"; exit 1; }
mkdir -p build
"$CXX" -std=c++17 -O2 -shared -DPD3D_GLFW -o build/minecraftd3d.dll minecraftd3d.cpp \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/win32" \
  -static -ld3d11 -ldxgi -ld3dcompiler_47 -luser32 -lgdi32 \
  -Wl,--kill-at
echo built native/build/minecraftd3d.dll
