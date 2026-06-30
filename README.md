# Tempered Studio — Mobile (Android)

Learn Rust on your phone, **fully offline** — 37 lessons, 71 exercises, quizzes,
cheatsheets, a glossary, and the Rust Book, all bundled (no account, no network).
Predict what code will do, then **compile + run it with a real native `rustc`**
on the device itself — no server, nobody's cloud.

---

## ⬇️ Install & Setup

### 1. Get the app
**[⬇️ Download the latest APK](https://github.com/thepictishbeast/Tempered-Studio-Mobile/releases/download/v0.3.2/TemperedStudio-v0.3.2-debug.apk)**
&nbsp;·&nbsp; or browse **[all releases](https://github.com/thepictishbeast/Tempered-Studio-Mobile/releases/latest)**

It's a **debug-signed, sideloadable** APK — no Play Store needed:

1. Tap the download link above on your phone.
2. When prompted, **allow your browser to "install unknown apps"**.
3. Open the downloaded `.apk` → **Install**. (You'll see the Ferris-crab icon.)

Everything — lessons, exercises, predicting, the Book, glossary — works **immediately, offline**, with no further setup.

### 2. (Optional) Run Rust natively on the phone
Compiling needs a Rust toolchain, which Android doesn't ship. Tempered Studio uses
an installed **[Termux](https://f-droid.org/packages/com.termux/)** as the on-device
compiler — real `rustc`, offline, no server. One-time setup:

1. **Install Termux** from **F-Droid** (the Play-Store build is outdated): <https://f-droid.org/packages/com.termux/>
2. Open Termux and run:
   ```sh
   pkg install rust
   echo allow-external-apps=true >> ~/.termux/termux.properties
   ```
3. Back in Tempered Studio, press **Run** — Android will ask to grant the *"Run
   commands in Termux"* permission once; allow it. Your code now compiles and runs
   on-device.

If Termux isn't installed, **Run** shows these same steps. (You can still learn
fully without it — predict the outcome, read the lesson + Book, check your guess.)

---

## What it is

A minimal Java app whose single `Activity` hosts a `WebView` running the **same
single-file `gui/` shell** shipped on web and desktop. Its read-only `/api/*`
endpoints are answered **offline by the embedded Rust seam over JNI**
(`libtempered_seam.so`), reading a store seeded from the bundled `exercises/` +
`book/` + `lessons/` + `glossary/` + `quizzes/` + `cheatsheets/`. So the whole
curriculum renders with **no network**.

For **compiling/running** the learner's code, the app bridges to an installed
Termux via its `com.termux.RUN_COMMAND` intent (see *Install & Setup* above) —
native, on-device, offline. No code is ever sent to a server.

- **`studio.tempered.mobile`** · minSdk 24 · targetSdk 34 · no AndroidX/Kotlin —
  deliberately minimal so it builds anywhere the toolchain is.

## Build

```sh
./build-apk.sh
# or, manually:
. /tank/scratch/android-toolchain/env.sh   # JDK / SDK-34 / NDK 26.3 / Gradle 8.9
gradle assembleDebug --no-daemon
# → app/build/outputs/apk/debug/app-debug.apk
```

`build-apk.sh` cross-compiles the `rpro-lang` seam for all ABIs (cargo-ndk) and
re-syncs `gui/` + the full store from a sibling `../Tempered-Studio` checkout, so
the mobile UI never drifts from the web/desktop one. Every push to `main` also
builds an APK via [`.github/workflows/apk.yml`](.github/workflows/apk.yml);
tagged `v*` pushes attach it to a downloadable GitHub Release.

## License

FOSS-first, matching the parent project.
