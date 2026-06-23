package studio.tempered.mobile;

/**
 * JNI binding to the Tempered Studio language seam (libtempered_seam.so),
 * cross-compiled from the SAME rpro-lang / rpro-lang-rust crates that drive the
 * web/CLI/TUI/desktop surfaces. These calls are pure (no toolchain) — they work
 * offline on-device. This is "one seam, many surfaces" reaching the phone.
 */
public final class Seam {
    static { System.loadLibrary("tempered_seam"); }
    private Seam() {}

    /** The seam's stable language id, e.g. "rust". */
    public static native String languageId();

    /** A starter file the seam generates for a concept. */
    public static native String exerciseTemplate(String concept);

    /** The exercise curriculum as /api/exercises JSON, read offline from a seeded store dir. */
    public static native String exercisesJson(String storeDir);
}
