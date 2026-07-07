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

    /** The current exercise as /api/current JSON (first exercise if none is Current). */
    public static native String currentJson(String storeDir);

    /** Book table of contents as /api/book JSON. */
    public static native String bookTocJson(String storeDir);

    /** One book chapter's cleaned markdown as /api/book?chapter=ID JSON. */
    public static native String bookChapterJson(String storeDir, String chapter);

    /**
     * The hint ladder as /api/hint JSON — the desktop ladder verbatim (attempt
     * gate, one rung per attempt capped at 3, never the solution). `attempts`
     * is supplied by the caller: on the phone, runs happen through the Termux
     * bridge, so MainActivity counts them per exercise.
     */
    public static native String hintJson(String storeDir, int level, int attempts);

    /**
     * Record a run's outcome OFFLINE (the write-side mobile lacked → the
     * "lockout"). Marks the exercise Done + advances Current to the next when
     * `advance` (a passing run/test). Returns {"advanced_to": next id or null}.
     */
    public static native String recordRun(String storeDir, String id, boolean passed, boolean advance);

    /** Switch the current exercise OFFLINE (tap a list item). Returns
     *  {"ok":true,"current":id} or {"error":"unknown|done", "message":...}. */
    public static native String selectExercise(String storeDir, String id, boolean force);

    /** Ensure a Current exercise exists (the seeded store has no progress.json →
     *  everything showed Locked). Sets the FIRST exercise Current if none is. */
    public static native String ensureSeeded(String storeDir);

    /** Re-open a completed exercise to REDO it (per-exercise only, never a
     *  reset-all): makes it Current, clears its completion + attempts. */
    public static native String resetExercise(String storeDir, String id);

    /** The IDE file explorer tree as /api/workspace JSON: every exercise source
     *  grouped by phase directory, each {id,name,title,status}. Offline. */
    public static native String workspaceJson(String storeDir);

    /** One exercise source for the IDE editor as /api/workspace/file JSON:
     *  {id,name,status,content}, or {"error":"unknown"} for an unknown id
     *  (mapped to HTTP 400 by the caller). No answer metadata is exposed. */
    public static native String workspaceFileJson(String storeDir, String id);
}
