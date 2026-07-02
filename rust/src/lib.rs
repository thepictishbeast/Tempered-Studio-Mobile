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

use rpro_lang::BookRef;

/// The current exercise as /api/current JSON, OFFLINE. Mirrors rpro-serve's
/// current_handler/current_json; falls back to the first exercise when the
/// fresh store has nothing marked Current yet (so the app always shows one).
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_currentJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let exercises = rpro_runner::discover(&store.root().join("exercises")).unwrap_or_default();
    let progress = store.load_progress().unwrap_or_default();
    let current_id = progress
        .entries
        .iter()
        .find(|(_, e)| e.status == ExerciseStatus::Current)
        .map(|(id, _)| id.clone());
    let ex = match current_id {
        Some(id) => exercises.into_iter().find(|e| e.meta.id == id),
        None => exercises.into_iter().next(),
    };
    let out = match ex {
        Some(ex) => {
            let code = std::fs::read_to_string(&ex.source).unwrap_or_default();
            let m = &ex.meta;
            serde_json::json!({
                "exercise": m.id, "title": m.title, "code": code, "concept": m.concept,
                "difficulty": m.difficulty, "estimated_minutes": m.estimated_minutes,
                "book_refs": m.book_refs,
            })
            .to_string()
        }
        None => serde_json::json!({ "exercise": null }).to_string(),
    };
    jstr(&mut env, &out)
}

/// Book table of contents as /api/book JSON (no query), OFFLINE.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_bookTocJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let book = rpro_book::Book::load(&store.root().join("book")).unwrap_or_default();
    let chapters: Vec<serde_json::Value> = book
        .chapters
        .values()
        .map(|c| serde_json::json!({ "id": c.id, "title": c.title() }))
        .collect();
    jstr(&mut env, &serde_json::json!({ "chapters": chapters }).to_string())
}

/// The hint ladder as /api/hint JSON, OFFLINE — the DESKTOP ladder verbatim
/// (rpro-serve hint_handler): 0 attempts → the "run it first" gate and nothing
/// unlocks; then ONE rung earned per attempt, capped at 3; the requested level
/// clamps to what's earned; the solution is never served at any rung
/// (`ExerciseMetadata::hint`). `attempts` comes from the CALLER: on the phone,
/// runs happen through the Termux bridge, so Java counts them per exercise —
/// the store's progress file never sees them.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_hintJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
    level: jni::sys::jint,
    attempts: jni::sys::jint,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let out = hint_json(&PathBuf::from(dir), level, attempts);
    jstr(&mut env, &out)
}

/// Pure body of [`Java_studio_tempered_mobile_Seam_hintJson`], split out so the
/// ladder is host-testable (the JNI wrapper only marshals strings).
fn hint_json(store_root: &std::path::Path, level: i32, attempts: i32) -> String {
    let store = Store::at(store_root.to_path_buf());
    let exercises = rpro_runner::discover(&store.root().join("exercises")).unwrap_or_default();
    let progress = store.load_progress().unwrap_or_default();
    // Same current-exercise resolution as currentJson: the progress `Current`
    // entry, else the first exercise in learning order.
    let current_id = progress
        .entries
        .iter()
        .find(|(_, e)| e.status == ExerciseStatus::Current)
        .map(|(id, _)| id.clone());
    let ex = match current_id {
        Some(id) => exercises.into_iter().find(|e| e.meta.id == id),
        None => exercises.into_iter().next(),
    };
    let Some(ex) = ex else {
        return serde_json::json!({ "level": 0, "max_level": 0, "text": null }).to_string();
    };
    if attempts <= 0 {
        return serde_json::json!({
            "level": 0,
            "max_level": 3,
            "text": "Run it first — predict the outcome, then run and read the real \
                     compiler error by hand. Hints unlock once you've genuinely tried.",
            "attempts": 0
        })
        .to_string();
    }
    let earned = u8::try_from(attempts).unwrap_or(u8::MAX).min(3); // 1 rung per attempt, capped at 3
    let requested = u8::try_from(level.max(1)).unwrap_or(1).clamp(1, earned);
    let (level, max_level, text) = ex.meta.hint(requested);
    serde_json::json!({
        "level": level, "max_level": max_level, "text": text, "attempts": attempts
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::hint_json;

    /// The bundled store the APK ships (committed under app assets) — the same
    /// content `hint_json` sees on-device after seeding.
    fn store() -> std::path::PathBuf {
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../app/src/main/assets/store")
    }

    #[test]
    fn gate_holds_then_rungs_unlock_and_clamp() {
        // 0 attempts → gated: level 0 and the nudge, nothing unlocked.
        let v: serde_json::Value = serde_json::from_str(&hint_json(&store(), 1, 0)).unwrap();
        assert_eq!(v["level"], 0, "no hint before a genuine attempt");
        assert!(v["text"].as_str().unwrap().contains("Run it first"));
        // 1 attempt, asking for rung 3 → clamped to the ONE earned rung.
        let v: serde_json::Value = serde_json::from_str(&hint_json(&store(), 3, 1)).unwrap();
        assert_eq!(v["level"], 1, "one attempt earns exactly rung 1");
        let t = v["text"].as_str().unwrap_or("").to_lowercase();
        assert!(!t.is_empty() && !t.contains("solution outline"), "never the fix");
        // Plenty of attempts → the requested rung serves, capped at max 3.
        let v: serde_json::Value = serde_json::from_str(&hint_json(&store(), 9, 9)).unwrap();
        assert_eq!(v["level"], 3, "rungs cap at 3");
        assert_eq!(v["max_level"], 3);
    }
}

/// One book chapter's cleaned markdown as /api/book?chapter=ID JSON, OFFLINE.
/// `chapter` is a map key (never a path join), exactly like rpro-serve.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_bookChapterJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
    chapter: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let ch: String = env.get_string(&chapter).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let book = rpro_book::Book::load(&store.root().join("book")).unwrap_or_default();
    let out = match book.get(&ch) {
        Some(c) => {
            let url = RustLanguage.book_ref_url(&BookRef {
                chapter: c.id.clone(),
                anchor: None,
                why: String::new(),
            });
            serde_json::json!({ "id": c.id, "markdown": c.display_markdown(&url) }).to_string()
        }
        None => serde_json::json!({ "chapter": null }).to_string(),
    };
    jstr(&mut env, &out)
}
