//! Android JNI bridge for the Tempered Studio language seam.
//!
//! Proves the "one seam, many surfaces" architecture reaches the phone: the
//! SAME `rpro-lang` / `rpro-lang-rust` crates that drive web/CLI/TUI/desktop are
//! cross-compiled (via cargo-ndk) into this `.so` and called from Kotlin/Java
//! over JNI. These seam calls are pure — no toolchain — so they work offline.
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use rpro_lang::Language;
use rpro_lang_rust::RustLanguage;

fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// The seam's stable language id (e.g. "rust").
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_languageId(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jstr(&mut env, RustLanguage.id())
}

/// A starter file the seam generates for a concept — proves the seam produces
/// real exercise content on-device, offline.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_exerciseTemplate<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    concept: JString<'a>,
) -> jstring {
    let c: String = env.get_string(&concept).map(|s| s.into()).unwrap_or_default();
    let tpl = RustLanguage.exercise_template(&c);
    jstr(&mut env, &tpl.contents)
}

use std::path::PathBuf;
use rpro_state::ExerciseStatus;
use rpro_storage_fs::Store;

fn status_str(s: ExerciseStatus) -> &'static str {
    match s {
        ExerciseStatus::Locked => "locked",
        ExerciseStatus::Current => "current",
        ExerciseStatus::Done => "done",
        ExerciseStatus::Skipped => "skipped",
    }
}

/// The exercise curriculum as the gui's `/api/exercises` JSON — served OFFLINE
/// from a seeded store dir (Java extracts the bundled exercises/ asset there).
/// Mirrors rpro-serve's exercises_handler exactly, so the WebView can intercept
/// `/api/exercises` and answer it with the on-device seam.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_exercisesJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let exercises = rpro_runner::discover(&store.root().join("exercises")).unwrap_or_default();
    let progress = store.load_progress().unwrap_or_default();
    let total = exercises.len();
    let mut done = 0usize;
    let items: Vec<serde_json::Value> = exercises
        .into_iter()
        .map(|e| {
            let entry = progress.entries.get(&e.meta.id);
            let status = entry.map_or(ExerciseStatus::Locked, |p| p.status);
            if status == ExerciseStatus::Done {
                done += 1;
            }
            serde_json::json!({
                "id": e.meta.id,
                "title": e.meta.title,
                "concept": e.meta.concept,
                "status": status_str(status),
                "attempts": entry.map_or(0, |p| p.attempts),
            })
        })
        .collect();
    let out = serde_json::json!({ "exercises": items, "done": done, "total": total }).to_string();
    jstr(&mut env, &out)
}
