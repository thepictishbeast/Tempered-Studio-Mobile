# Tempered Studio — Mobile (Android)

The Android surface of **Tempered Studio** — the "one seam, many surfaces"
architecture extended to a phone. This is the separate mobile repo the main
project's `docs/DISTRIBUTION.md` calls for (kept out of the Rust workspace so the
Android/Gradle toolchain doesn't muddy it).

## 📥 Download

**[Latest APK → Releases](https://github.com/thepictishbeast/Tempered-Studio-Mobile/releases/latest)**
(currently [v0.3.0](https://github.com/thepictishbeast/Tempered-Studio-Mobile/releases/tag/v0.3.0) — the
full offline learning platform: 37 lessons, glossary, quizzes, cheatsheets, exercises & the Book).

It's a **debug-signed** APK — sideloadable on any phone: enable *"install unknown
apps"* for your browser/file manager, download, tap to install. No Play Store, no
account, fully offline once installed. (A Play-Store / F-Droid *release* build
needs a signing keystore — separate.) Every push to `main` also publishes a fresh
APK as a workflow artifact via [`.github/workflows/apk.yml`](.github/workflows/apk.yml).

## Status — v0.2 (offline study app: embedded seam, real curriculum)

A minimal Java app whose single `Activity` hosts a `WebView` running the **same
single-file `gui/` shell** shipped on web and desktop — served, together with its
read-only `/api/*` endpoints, from a virtual `https://` origin so `fetch()` works
(Chromium blocks `fetch` to `file://`). The `/api/*` calls are answered **offline
by the embedded Rust seam over JNI** (`libtempered_seam.so`), which reads a store
seeded from the bundled `exercises/` + `book/`. So the app shows the **real
curriculum (65 exercises across 11 phases) + 33 Rust Book chapters — with no
network**. (Compiling the learner's code needs a toolchain Android lacks, so
`Run`/`Check` fall through; a remote/online toolchain or a future on-device one
is the next step.)

Verified end-to-end on an Android-34 emulator: boots, the seam `.so` loads, and
the real UI renders offline (curriculum, current exercise, book, "on-device
seam: rust" banner).

- **Builds to a real, installable APK** — `studio.tempered.mobile`, minSdk 24,
  targetSdk 34. No AndroidX, no Kotlin plugin, no extra deps (only the Android
  Gradle Plugin) — deliberately minimal so it builds anywhere the toolchain is.
- Verified: `aapt2 dump badging` resolves the package + launcher activity, and
  `assets/gui/index.html` is bundled inside the `.apk`.

## Build

```sh
./build-apk.sh
# or, manually:
. /tank/scratch/android-toolchain/env.sh   # JDK/SDK-34/NDK 26.3/Gradle 8.9 (+ TMPDIR off noexec /tmp)
gradle assembleDebug --no-daemon
# → app/build/outputs/apk/debug/app-debug.apk
```

`build-apk.sh` re-syncs `gui/` from a sibling `../Tempered-Studio` checkout when
present, so the mobile UI never drifts from the web/desktop one.

## Next step — embed the seam so exercises run on-device

v0.1 shows the UI but can't compile/run exercises (no server). The plan: bundle
the language seam on-device rather than a remote toolchain —

1. Cross-compile a thin `rpro-mobile` cdylib (exposing the `rpro-lang` /
   `rpro-core` seam) for the Android targets via **cargo-ndk** (already
   installed; `aarch64`, `armv7`, `x86_64`, `i686` rust targets added).
2. Bridge it to the WebView via a JNI / `@JavascriptInterface` shim (or run a
   loopback `rpro-serve` thread) so `Run`/`Check`/`Explain` work offline.
3. F-Droid + Obtainium-compatible GitHub Releases for auto-update
   (`docs/DISTRIBUTION.md` §Android).

The cross-compile toolchain is ready; this repo is the host the seam plugs into.

## License

FOSS-first, matching the parent project.
