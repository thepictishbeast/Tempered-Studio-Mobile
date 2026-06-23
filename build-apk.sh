#!/usr/bin/env bash
# Build the Tempered Studio Android APK. Sources the shared toolchain env
# (JDK/SDK/NDK/Gradle, with the noexec-/tmp TMPDIR redirect) and assembles a
# debug APK. Re-syncs the bundled gui/ shell from a sibling Tempered-Studio
# checkout when present, so the mobile surface never drifts from web/desktop.
set -euo pipefail
. /tank/scratch/android-toolchain/env.sh
HERE="$(cd "$(dirname "$0")" && pwd)"
SIB="$HERE/../Tempered-Studio/gui"
if [ -d "$SIB" ]; then
  echo "syncing gui/ from $SIB"
  rm -rf "$HERE/app/src/main/assets/gui"
  cp -r "$SIB" "$HERE/app/src/main/assets/gui"
fi
cd "$HERE"
gradle assembleDebug --no-daemon --console=plain
echo "APK: $HERE/app/build/outputs/apk/debug/app-debug.apk"
