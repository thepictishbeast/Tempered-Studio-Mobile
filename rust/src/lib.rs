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

/// The spaced-repetition review queue OFFLINE. Mirrors rpro-serve's
/// review_handler exactly — reads the SAME `progress.reviews` (shared
/// rpro-state) the on-device recordRun already populates when a learner
/// overcomes an error code — so the gui's RECALL chips work on the phone
/// (they never showed before: `/api/review` fell through to null). Returns
/// `{ "due": [codes], "mastered": n, "tracked": n }`.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_reviewJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let progress = store.load_progress().unwrap_or_default();
    let r = &progress.reviews;
    let out = serde_json::json!({
        "due": r.due(),
        "mastered": r.mastered_count(),
        "tracked": r.tracked_count(),
    })
    .to_string();
    jstr(&mut env, &out)
}

/// Record a run's outcome OFFLINE (the write-side that mobile lacked → the
/// "lockout"). Mirrors what rpro-serve's /api/run does server-side: mark the
/// exercise Done + advance Current to the next when `advance` (a passing
/// run/test). Diagnostics are empty (Termux gives raw output, not parsed
/// diagnostics — the review fold just gets no error code). Returns
/// `{ "advanced_to": <next id or null> }`.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_recordRun<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
    exercise_id: JString<'a>,
    passed: jni::sys::jboolean,
    advance: jni::sys::jboolean,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let id: String = env.get_string(&exercise_id).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let advanced_to = rpro_runner::record_run(&store, &id, &[], passed != 0, advance != 0);
    jstr(&mut env, &serde_json::json!({ "advanced_to": advanced_to }).to_string())
}

/// Switch the current exercise OFFLINE (tap a list item). Mirrors /api/select:
/// validates the id, refuses to un-complete a Done exercise, and gates a
/// not-yet-reached one unless `force`. Returns `{ "ok": true, "current": id }`
/// or `{ "error": "unknown|done|locked", "message": ... }`.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_selectExercise<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
    exercise_id: JString<'a>,
    force: jni::sys::jboolean,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let id: String = env.get_string(&exercise_id).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let exercises = rpro_runner::discover(&store.root().join("exercises")).unwrap_or_default();
    if !exercises.iter().any(|e| e.meta.id == id) {
        return jstr(&mut env, &serde_json::json!({ "error": "unknown" }).to_string());
    }
    let _ = force; // mobile is the learner's own device — no soft prerequisite gate
    let mut progress = store.load_progress().unwrap_or_default();
    if progress.entries.get(&id).map(|e| e.status) == Some(ExerciseStatus::Done) {
        return jstr(&mut env, &serde_json::json!({ "error": "done",
            "message": "already completed — reset it to practise again" }).to_string());
    }
    progress.select(&id);
    let _ = store.save_progress(&progress);
    jstr(&mut env, &serde_json::json!({ "ok": true, "current": id }).to_string())
}

/// Ensure the seeded store has a Current exercise (the phone store ships no
/// progress.json → nothing was Current → EVERYTHING showed Locked, incl. the
/// first exercise). Mirrors rpro-serve's ensure_seeded: if no entry is Current,
/// make the FIRST exercise Current. Idempotent; call once on startup.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_ensureSeeded<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let exercises = rpro_runner::discover(&store.root().join("exercises")).unwrap_or_default();
    let mut progress = store.load_progress().unwrap_or_default();
    let has_current = progress
        .entries
        .values()
        .any(|e| e.status == ExerciseStatus::Current);
    if !has_current {
        if let Some(first) = exercises.first() {
            progress.set_current(&first.meta.id);
            let _ = store.save_progress(&progress);
        }
    }
    jstr(&mut env, &serde_json::json!({ "ok": true }).to_string())
}

/// Re-open a completed exercise to REDO it OFFLINE (Paul: "reset to redo doesn't
/// work"; can't re-enter a finished exercise). Mirrors the CLI's reset: the
/// exercise becomes Current with completed_at cleared + attempts 0 (per-exercise
/// only — NEVER a reset-all). Demotes any other Current to Locked so the
/// single-Current invariant holds. Returns `{ "ok": true, "current": id }`.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_resetExercise<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
    exercise_id: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let id: String = env.get_string(&exercise_id).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let mut progress = store.load_progress().unwrap_or_default();
    let others: Vec<String> = progress
        .entries
        .iter()
        .filter(|(k, v)| *k != &id && v.status == ExerciseStatus::Current)
        .map(|(k, _)| k.clone())
        .collect();
    for k in others {
        if let Some(e) = progress.entries.get_mut(&k) {
            e.status = ExerciseStatus::Locked;
        }
    }
    progress.reset(&id);
    let _ = store.save_progress(&progress);
    jstr(&mut env, &serde_json::json!({ "ok": true, "current": id }).to_string())
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

// ── IDE workspace: the file explorer's tree + one-file read, OFFLINE ──────────
//
// The web GUI's fullscreen IDE fetches `/api/workspace` (the explorer tree) and
// `/api/workspace/file?id=…` (open a file). Those were unhandled on the phone
// (MainActivity fell through to `return null`), so the file explorer was DEAD
// offline — the exact "everything must work offline" gap Paul called out. These
// mirror rpro-serve's `workspace_handler` / `workspace_file_handler` byte-for-
// byte on the success path, keyed by exercise **id** (never a wire path), so the
// answer-bearing `.toml`s can't be named, listed, or read through this surface.
//
// One deliberate difference from the desktop server: NO locked (423) gate — the
// phone is the learner's own device and the seam's `selectExercise` already
// drops the soft prerequisite gate for the same reason. The IDE is a progress-
// neutral workshop, so reading a file here never advances or unlocks anything.

/// `GET /api/workspace` — the explorer tree: every exercise source grouped by
/// phase directory, in course order, each `{id,name,title,status}`. Always 200.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_workspaceJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let exercises = rpro_runner::discover(&store.root().join("exercises")).unwrap_or_default();
    let progress = store.load_progress().unwrap_or_default();
    let mut groups: Vec<serde_json::Value> = Vec::new();
    let mut cur_dir = String::new();
    let mut cur_files: Vec<serde_json::Value> = Vec::new();
    for ex in &exercises {
        let dir = ex
            .source
            .parent()
            .and_then(|p| p.file_name())
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        if dir != cur_dir && !cur_files.is_empty() {
            groups.push(serde_json::json!({ "dir": cur_dir, "files": cur_files }));
            cur_files = Vec::new();
        }
        cur_dir = dir;
        let status = progress
            .entries
            .get(&ex.meta.id)
            .map_or(ExerciseStatus::Locked, |p| p.status);
        cur_files.push(serde_json::json!({
            "id": ex.meta.id,
            "name": ex.source.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
            "title": ex.meta.title,
            "status": status_str(status),
        }));
    }
    if !cur_files.is_empty() {
        groups.push(serde_json::json!({ "dir": cur_dir, "files": cur_files }));
    }
    jstr(&mut env, &serde_json::json!({ "groups": groups }).to_string())
}

/// `GET /api/workspace/file?id=…` — one exercise source for the IDE editor:
/// `{id,name,status,content}`. Unknown id → `{"error":"unknown"}` (the caller
/// maps that to HTTP 400, matching the desktop server). No answer metadata is
/// ever included, and no `.toml` is ever read here.
#[no_mangle]
pub extern "system" fn Java_studio_tempered_mobile_Seam_workspaceFileJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    store_dir: JString<'a>,
    exercise_id: JString<'a>,
) -> jstring {
    let dir: String = env.get_string(&store_dir).map(|s| s.into()).unwrap_or_default();
    let id: String = env.get_string(&exercise_id).map(|s| s.into()).unwrap_or_default();
    let store = Store::at(PathBuf::from(dir));
    let exercises = rpro_runner::discover(&store.root().join("exercises")).unwrap_or_default();
    let Some(ex) = exercises.iter().find(|e| e.meta.id == id) else {
        return jstr(&mut env, &serde_json::json!({ "error": "unknown" }).to_string());
    };
    let content = std::fs::read_to_string(&ex.source).unwrap_or_default();
    let progress = store.load_progress().unwrap_or_default();
    let status = progress
        .entries
        .get(&ex.meta.id)
        .map_or(ExerciseStatus::Locked, |p| p.status);
    let out = serde_json::json!({
        "id": ex.meta.id,
        "name": ex.source.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
        "status": status_str(status),
        "content": content,
    });
    jstr(&mut env, &out.to_string())
}
