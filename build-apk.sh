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
# Default to paul's install (where the android targets live) rather than $HOME,
# so the build works regardless of which user/service-account runs it (the CI
# runner's $HOME is not paul's). Override RUSTUP_HOME/CARGO_HOME for other setups.
export RUSTUP_HOME="${RUSTUP_HOME:-/home/paul/.rustup}"
export CARGO_HOME="${CARGO_HOME:-/home/paul/.cargo}"
export PATH="$CARGO_HOME/bin:$PATH"

echo "[1/3] cross-compiling the seam (rpro-lang) -> jniLibs"
( cd "$HERE/rust" && cargo ndk \
    -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
    -o "$HERE/app/src/main/jniLibs" build --release )

# Sibling Tempered-Studio checkout (gui + the full store: exercises, book,
# glossary, lessons). Defaults to the adjacent dir for local builds; CI sets
# $TS_SIBLING to wherever it checked it out.
SIB="${TS_SIBLING:-$HERE/../Tempered-Studio}"
if [ -d "$SIB/gui" ]; then
  echo "[2/3] syncing gui/ + store (exercises, book, glossary, lessons, quizzes, cheatsheets) from $SIB"
  rm -rf "$HERE/app/src/main/assets/gui";             cp -r "$SIB/gui"       "$HERE/app/src/main/assets/gui"
  rm -rf "$HERE/app/src/main/assets/store/exercises"; cp -r "$SIB/exercises" "$HERE/app/src/main/assets/store/exercises"
  rm -rf "$HERE/app/src/main/assets/store/book";      cp -r "$SIB/book"      "$HERE/app/src/main/assets/store/book"
  rm -rf "$HERE/app/src/main/assets/store/glossary";  cp -r "$SIB/glossary"  "$HERE/app/src/main/assets/store/glossary"
  rm -rf "$HERE/app/src/main/assets/store/lessons";   cp -r "$SIB/lessons"   "$HERE/app/src/main/assets/store/lessons"
  rm -rf "$HERE/app/src/main/assets/store/quizzes";   cp -r "$SIB/quizzes"   "$HERE/app/src/main/assets/store/quizzes"
  rm -rf "$HERE/app/src/main/assets/store/cheatsheets"; cp -r "$SIB/cheatsheets" "$HERE/app/src/main/assets/store/cheatsheets"
  # Pre-convert the glossary TOML → JSON: the on-device server (MainActivity)
  # serves the glossary as JSON, and parsing TOML in Java isn't worth it.
  # tomllib is in the python3.11+ stdlib.
  python3 - "$HERE/app/src/main/assets/store/glossary" <<'PY'
import json, pathlib, sys, tomllib
d = pathlib.Path(sys.argv[1])
data = tomllib.loads((d / "glossary.toml").read_text(encoding="utf-8"))
terms = data.get("term", [])
(d / "glossary.json").write_text(json.dumps({"terms": terms}, ensure_ascii=False), encoding="utf-8")
print(f"  + glossary.json ({len(terms)} terms)")
PY
else
  echo "[2/3] asset sync skipped (no sibling Tempered-Studio checkout)"
fi

echo "[3/3] assembling APK"
cd "$HERE"
gradle assembleDebug --no-daemon --console=plain
echo "APK: $HERE/app/build/outputs/apk/debug/app-debug.apk"
