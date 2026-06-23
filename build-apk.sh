#!/usr/bin/env bash
# Build the Tempered Studio Android APK end to end:
#   1. cross-compile the language seam (rpro-lang) for all ABIs via cargo-ndk
#   2. re-sync the shared gui/ shell from a sibling Tempered-Studio checkout
#   3. assemble the debug APK with Gradle
# Sources the shared toolchain env (JDK/SDK/NDK/Gradle + noexec-/tmp TMPDIR).
set -euo pipefail
. /tank/scratch/android-toolchain/env.sh
HERE="$(cd "$(dirname "$0")" && pwd)"

# Use the rustup toolchain (which has the android std targets), not a system rust.
export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.rustup}"
export PATH="$HOME/.cargo/bin:$PATH"

echo "[1/3] cross-compiling the seam (rpro-lang) -> jniLibs"
( cd "$HERE/rust" && cargo ndk \
    -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
    -o "$HERE/app/src/main/jniLibs" build --release )

SIB="$HERE/../Tempered-Studio/gui"
if [ -d "$SIB" ]; then
  echo "[2/3] syncing gui/ from $SIB"
  rm -rf "$HERE/app/src/main/assets/gui"; cp -r "$SIB" "$HERE/app/src/main/assets/gui"
else
  echo "[2/3] gui/ sync skipped (no sibling Tempered-Studio checkout)"
fi

echo "[3/3] assembling APK"
cd "$HERE"
gradle assembleDebug --no-daemon --console=plain
echo "APK: $HERE/app/build/outputs/apk/debug/app-debug.apk"
