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
