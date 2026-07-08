package studio.tempered.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tempered Studio — Android. Serves the shared gui/ shell AND its read-only
 * /api/* endpoints from a virtual https origin so the WebView's fetch() works
 * (Chromium blocks fetch to file://). /api/* is answered OFFLINE by the embedded
 * Rust seam over JNI — the same rpro-runner/rpro-book/rpro-state that drive
 * rpro-serve, reading a store seeded from bundled assets. Browse the real
 * curriculum + book + current exercise with no network. (Run/Check need a
 * toolchain Android lacks, so they fall through and the gui handles them.)
 */
public class MainActivity extends Activity {
    // A reserved domain that never resolves to the real internet (same as
    // androidx WebViewAssetLoader). All requests to it are intercepted below.
    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/";
    // Termux's RUN_COMMAND is a `dangerous`, Termux-DEFINED permission: declaring
    // it in the manifest is not enough — Android requires a runtime grant, or every
    // RUN_COMMAND intent is rejected with "without permission …RUN_COMMAND" (why the
    // on-device compiler never ran for Paul even after allow-external-apps=true).
    private static final String TERMUX_PERM = "com.termux.permission.RUN_COMMAND";
    private static final int RC_TERMUX_PERM = 4711;

    private WebView web;
    private String storeDir;

    public final class SeamBridge {
        @JavascriptInterface public String languageId() { return Seam.languageId(); }
        @JavascriptInterface public String exerciseTemplate(String c) { return Seam.exerciseTemplate(c); }

        /** Is Termux installed? (the on-device native rustc host) */
        @JavascriptInterface public boolean termuxInstalled() {
            try { getPackageManager().getPackageInfo("com.termux", 0); return true; }
            catch (Exception e) { return false; }
        }

        /** Compile + run `code` natively on-device via an installed Termux (no server,
         *  no bundled toolchain). The result is delivered asynchronously to the page's
         *  window.__termuxResult(cbId, {stdout,stderr,errmsg,exit}). */
        @JavascriptInterface public void runViaTermux(final String code, final String cbId) {
            runViaTermux(code, cbId, "run"); // 2-arg fallback (older gui) → full run
        }
        /** op: "check" → fast type-check only (rustc --emit=metadata, no codegen/link/run);
         *  "run"/"test" → compile + run. Check is both faster and semantically correct. */
        @JavascriptInterface public void runViaTermux(final String code, final String cbId, final String op) {
            runOnUiThread(new Runnable() { public void run() { doTermuxRun(code, cbId, op); } });
        }

        /** Reliable clipboard copy (WebView's navigator.clipboard is flaky). */
        @JavascriptInterface public void copyText(final String s) {
            runOnUiThread(new Runnable() { public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Tempered Studio", s == null ? "" : s));
                } catch (Exception e) {}
            }});
        }

        /** Mirror in-app log events to logcat (adb logcat -s TemperedStudio:*). */
        @JavascriptInterface public void log(String tag, String msg) {
            try { android.util.Log.i("TemperedStudio:" + tag, msg == null ? "" : msg); } catch (Exception e) {}
        }

        /** Record a run outcome OFFLINE (the write-side mobile lacked): mark Done +
         *  advance on a passing run/test. Returns {"advanced_to": id|null}. */
        @JavascriptInterface public String recordRun(String id, boolean passed, boolean advance) {
            try { return Seam.recordRun(storeDir, id, passed, advance); } catch (Exception e) { return "{}"; }
        }
        /** Switch the current exercise OFFLINE (tap a list item / jump ahead). */
        @JavascriptInterface public String selectExercise(String id, boolean force) {
            try { return Seam.selectExercise(storeDir, id, force); } catch (Exception e) { return "{\"error\":\"seam\"}"; }
        }
        /** Re-open a completed exercise to redo it (per-exercise reset). */
        @JavascriptInterface public String resetExercise(String id) {
            try { return Seam.resetExercise(storeDir, id); } catch (Exception e) { return "{\"error\":\"seam\"}"; }
        }
    }

    // ── Attempt counting for the hint ladder ────────────────────────────────────
    // The desktop gates hints behind genuine attempts recorded by its run handler.
    // On the phone, runs happen through the Termux bridge below — the store's
    // progress never sees them — so count them here, per exercise, and feed the
    // count into the seam's hintJson (same ladder, same gate).
    private String currentExerciseId() {
        try {
            return new org.json.JSONObject(Seam.currentJson(storeDir)).optString("exercise", "");
        } catch (Exception e) { return ""; }
    }

    private int attemptsForCurrent() {
        String id = currentExerciseId();
        if (id.isEmpty()) return 0;
        return getSharedPreferences("attempts", MODE_PRIVATE).getInt(id, 0);
    }

    private void recordAttemptForCurrent() {
        String id = currentExerciseId();
        if (id.isEmpty()) return;
        android.content.SharedPreferences p = getSharedPreferences("attempts", MODE_PRIVATE);
        p.edit().putInt(id, p.getInt(id, 0) + 1).apply();
    }

    // ── Native on-device Run via Termux's RUN_COMMAND intent ─────────────────────
    // We hand the user's source to Termux on stdin; a tiny bash one-liner compiles
    // it with the REAL on-device rustc and runs it, streaming compiler output back.
    private void doTermuxRun(String code, final String cbId, final String op) {
        // explain is a REFERENCE lookup (rustc --explain), not a try at the
        // exercise — it must not earn hint rungs. Its stdin is a diagnostic
        // code: sanitize to alphanumerics here (and again in bash, belt+braces).
        final boolean explain = "explain".equals(op);
        // The Sandbox is free-play: compile + run like a normal run (it falls into
        // the run/test branch below since it is neither "check" nor "explain"), but
        // it must NEVER count as an exercise attempt or touch the hint gate.
        final boolean sandbox = "sandbox".equals(op);
        if (explain) code = code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "");
        else if (!sandbox) recordAttemptForCurrent(); // every run/check/test = a genuine attempt (hint gate)
        // Guard: without the runtime RUN_COMMAND grant the intent is silently
        // rejected. Ask again and tell the user plainly instead of failing cryptically.
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(TERMUX_PERM) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ TERMUX_PERM }, RC_TERMUX_PERM);
            deliverTermuxResult(cbId, "", "",
                "Android needs your OK to let this app run commands in Termux. "
                + "Tap Allow on the permission dialog, then press Run again.", -1);
            return;
        }
        final String action = getPackageName() + ".TERMUX_RESULT." + cbId;
        final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String out = "", err = "", errmsg = ""; int exit = -1;
                Bundle res = intent.getBundleExtra("result");
                if (res != null) {
                    out = nz(res.getString("stdout"));
                    err = nz(res.getString("stderr"));
                    errmsg = nz(res.getString("errmsg"));
                    try { exit = res.getInt("exitCode", -1); }
                    catch (Exception e) { try { exit = Integer.parseInt(nz(res.getString("exitCode"))); } catch (Exception e2) { exit = -1; } }
                }
                deliverTermuxResult(cbId, out, err, errmsg, exit);
                try { c.unregisterReceiver(this); } catch (Exception e) {}
            }
        };
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rx, new IntentFilter(action));

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(this, cbId.hashCode(),
            new Intent(action).setPackage(getPackageName()), piFlags);

        Intent exec = new Intent();
        exec.setClassName("com.termux", "com.termux.app.RunCommandService");
        exec.setAction("com.termux.RUN_COMMAND");
        exec.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        // The rustc line depends on the op:
        //  • check → `--emit=metadata` type-checks ONLY (no codegen, no link, no run) —
        //    the same diagnostics, markedly faster (the win Paul asked for), and the
        //    correct semantics for Check (it never runs the binary).
        //  • run/test → -C prefer-dynamic (dynamic libstd, skips the slow static link)
        //    + -C debuginfo=0, then run with the sysroot lib dir on LD_LIBRARY_PATH.
        final String needRustc =
            "command -v rustc >/dev/null 2>&1 || { echo 'rustc not found in Termux — run:  pkg install rust' >&2; exit 127; }; ";
        final String bashLine;
        if (explain) {
            // stdin = a diagnostic code. Re-sanitize in bash (defense in depth —
            // it's only ever an argument, never interpolated into the command).
            bashLine = needRustc + "read -r c; c=\"${c//[^A-Za-z0-9]/}\"; rustc --explain \"$c\" 2>&1";
        } else {
            // The rustc line depends on the op (see the comment block above).
            final String prefix =
                "d=\"$HOME/.tempered\"; mkdir -p \"$d\"; cat > \"$d/main.rs\"; cd \"$d\"; " + needRustc;
            final String rustcCmd = "check".equals(op)
                ? "rustc --edition 2021 --emit=metadata main.rs 2>&1"
                : "S=\"$(rustc --print sysroot)\"; " +
                  "rustc --edition 2021 -C prefer-dynamic -C debuginfo=0 main.rs -o bin 2>&1 && LD_LIBRARY_PATH=\"$S/lib:$LD_LIBRARY_PATH\" ./bin";
            bashLine = prefix + rustcCmd;
        }
        exec.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{ "-c", bashLine });
        exec.putExtra("com.termux.RUN_COMMAND_STDIN", code);
        exec.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        exec.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        exec.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(exec); else startService(exec);
        } catch (Exception e) {
            deliverTermuxResult(cbId, "", "Couldn't reach Termux: " + e.getMessage()
                + "\n(Install Termux + `pkg install rust`, and set allow-external-apps=true in ~/.termux/termux.properties.)", "", -1);
            try { unregisterReceiver(rx); } catch (Exception e2) {}
        }
    }

    private void deliverTermuxResult(String cbId, String out, String err, String errmsg, int exit) {
        final String payload = "{\"stdout\":" + jstr(out) + ",\"stderr\":" + jstr(err)
            + ",\"errmsg\":" + jstr(errmsg) + ",\"exit\":" + exit + "}";
        final String js = "window.__termuxResult&&window.__termuxResult(" + jstr(cbId) + "," + payload + ")";
        if (web != null) web.post(new Runnable() { public void run() { web.evaluateJavascript(js, null); } });
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String jstr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default: if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch);
            }
        }
        return b.append("\"").toString();
    }

    /** A file's UTF-8 contents, or null if unreadable. */
    private static String readFile(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    /** The first `# ` heading in a markdown doc — its title (fallback: the id). */
    private static String mdTitle(String md, String fallback) {
        for (String line : md.split("\n")) {
            if (line.startsWith("# ")) return line.substring(2).trim();
        }
        return fallback;
    }

    /**
     * Serve a seeded markdown collection (lessons/quizzes/cheatsheets) with the
     * exact response shapes of the desktop server's md_collection: no id → the
     * ordered {"<listKey>":[{id,title}…]} list (filename order = curriculum
     * order); ?id=STEM → {"<itemKey>":{id,title,markdown}} or null on a miss.
     * `id` is only ever COMPARED against real file stems — never joined onto a
     * path — so a traversal value simply finds no match.
     */
    private String mdCollection(String subdir, String listKey, String itemKey, String id, String q) {
        File[] files = new File(storeDir, subdir).listFiles((d, n) -> n.endsWith(".md"));
        if (files == null) files = new File[0];
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        // ?q=TERM — full-text search over every item's markdown (mirrors the desktop
        // md_collection): {"hits":[{id,title,count,snippet}]} sorted by match count.
        // Checked BEFORE id so a search never joins a path; blank term falls through
        // to the list, a no-match term returns an empty hits array (never the list).
        if (q != null && !q.trim().isEmpty()) {
            String needle = q.trim().toLowerCase();
            java.util.List<Object[]> hits = new java.util.ArrayList<>();
            for (File f : files) {
                String md = readFile(f);
                if (md == null) continue;
                int count = countOccurrences(md.toLowerCase(), needle);
                if (count == 0) continue;
                String stem = f.getName().substring(0, f.getName().length() - 3);
                String obj = "{\"id\":" + jstr(stem)
                        + ",\"title\":" + jstr(mdTitle(md, stem))
                        + ",\"count\":" + count
                        + ",\"snippet\":" + jstr(mdSnippet(md, needle)) + "}";
                hits.add(new Object[] { count, obj });
            }
            hits.sort((a, bb) -> Integer.compare((int) bb[0], (int) a[0]));
            StringBuilder sb = new StringBuilder("{\"hits\":[");
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append((String) hits.get(i)[1]);
            }
            return sb.append("]}").toString();
        }
        StringBuilder b = new StringBuilder("{");
        if (id == null) {
            b.append(jstr(listKey)).append(":[");
            boolean first = true;
            for (File f : files) {
                String md = readFile(f);
                if (md == null) continue;
                String stem = f.getName().substring(0, f.getName().length() - 3);
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":").append(jstr(stem))
                 .append(",\"title\":").append(jstr(mdTitle(md, stem))).append('}');
            }
            b.append(']');
        } else {
            String item = null;
            for (File f : files) {
                String stem = f.getName().substring(0, f.getName().length() - 3);
                if (stem.equals(id)) {
                    String md = readFile(f);
                    if (md != null)
                        item = "{\"id\":" + jstr(stem) + ",\"title\":" + jstr(mdTitle(md, stem))
                             + ",\"markdown\":" + jstr(md) + "}";
                    break;
                }
            }
            b.append(jstr(itemKey)).append(':').append(item == null ? "null" : item);
        }
        return b.append('}').toString();
    }

    /** Count non-overlapping occurrences of {@code needle} in {@code hay} (both lowercased). */
    static int countOccurrences(String hay, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) != -1) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * A short plain-text preview around the first match of {@code needleLower}
     * (already lowercased) in {@code md}: collapses whitespace, drops the loudest
     * markdown noise, and clamps indices so a preview never throws. Mirrors the
     * desktop server's md_snippet.
     */
    static String mdSnippet(String md, String needleLower) {
        int pos = md.toLowerCase().indexOf(needleLower);
        if (pos < 0) pos = 0;
        int start = Math.max(0, pos - 60);
        int end = Math.min(md.length(), pos + needleLower.length() + 90);
        String clean = md.substring(start, end)
                .replaceAll("\\s+", " ")
                .replaceAll("[#`*>]", "")
                .trim();
        return (start > 0 ? "…" : "") + clean + (end < md.length() ? "…" : "");
    }

    /**
     * A book chapter's title: the first heading of ANY level ({@code #}…{@code ######}),
     * stripped of its leading hashes. Matches rpro-book's Chapter::title (the book's
     * chapters lead with {@code ## }, so the lesson-style "# "-only mdTitle would miss
     * them and fall back to the id). Falls back to {@code fallback} when there's none.
     */
    static String bookTitle(String md, String fallback) {
        for (String line : md.split("\n")) {
            String t = line.replaceAll("^\\s+", "");
            if (t.startsWith("#")) {
                String title = t.replaceAll("^#+", "").trim();
                if (!title.isEmpty()) return title;
            }
        }
        return fallback;
    }

    /**
     * Full-text search over the bundled Rust Book chapters — the on-device mirror of
     * the desktop /api/book?q= (rpro_book::Book::search). Returns {"hits":[{chapter,
     * title,count,snippet}]} ranked by match count, the SAME shape the gui's
     * runBookSearch renders (data-ch = chapter). Pure Java over the seeded .md — no
     * seam call — so the Book search box works fully offline on the phone.
     */
    private String bookSearchJson(String q) {
        File[] files = new File(storeDir, "book").listFiles((d, n) -> n.endsWith(".md"));
        if (files == null) files = new File[0];
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        String needle = q.trim().toLowerCase();
        java.util.List<Object[]> hits = new java.util.ArrayList<>();
        for (File f : files) {
            String md = readFile(f);
            if (md == null) continue;
            int count = countOccurrences(md.toLowerCase(), needle);
            if (count == 0) continue;
            String stem = f.getName().substring(0, f.getName().length() - 3);
            String obj = "{\"chapter\":" + jstr(stem)
                    + ",\"title\":" + jstr(bookTitle(md, stem))
                    + ",\"count\":" + count
                    + ",\"snippet\":" + jstr(mdSnippet(md, needle)) + "}";
            hits.add(new Object[] { count, obj });
        }
        hits.sort((a, b) -> Integer.compare((int) b[0], (int) a[0]));
        StringBuilder sb = new StringBuilder("{\"hits\":[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append((String) hits.get(i)[1]);
        }
        return sb.append("]}").toString();
    }

    /**
     * The glossary, pre-converted to {"terms":[…]} JSON at build time
     * (build-apk.sh; parsing TOML in Java isn't worth it). No term → the whole
     * document; ?term=X → {"term":<match|null>}, matched by name, then alias,
     * then substring — mirroring the desktop lookup order.
     */
    private String glossaryJson(String term) {
        String doc = readFile(new File(storeDir, "glossary/glossary.json"));
        if (doc == null) return term == null ? "{\"terms\":[]}" : "{\"term\":null}";
        if (term == null) return doc;
        try {
            org.json.JSONArray terms = new org.json.JSONObject(doc).getJSONArray("terms");
            String q = term.trim().toLowerCase();
            org.json.JSONObject alias = null, sub = null;
            for (int i = 0; i < terms.length(); i++) {
                org.json.JSONObject t = terms.getJSONObject(i);
                String name = t.optString("name", "").toLowerCase();
                if (name.equals(q)) return "{\"term\":" + t + "}"; // exact wins outright
                org.json.JSONArray as = t.optJSONArray("aliases");
                if (alias == null && as != null) {
                    for (int j = 0; j < as.length(); j++) {
                        if (as.getString(j).trim().toLowerCase().equals(q)) { alias = t; break; }
                    }
                }
                if (sub == null && name.contains(q)) sub = t;
            }
            org.json.JSONObject hit = alias != null ? alias : sub;
            return "{\"term\":" + (hit == null ? "null" : hit.toString()) + "}";
        } catch (Exception e) { return "{\"term\":null}"; }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Ask for the Termux RUN_COMMAND grant up front so the on-device compiler
        // can actually run (see TERMUX_PERM note — a manifest declaration alone
        // leaves it ungranted and every run is rejected).
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(TERMUX_PERM) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ TERMUX_PERM }, RC_TERMUX_PERM);
        }
        storeDir = new File(getFilesDir(), "store").getAbsolutePath();
        try { seedStoreFromAssets(); } catch (Exception e) { /* best-effort */ }
        // The seeded store ships no progress.json → nothing Current → everything
        // showed Locked (the "first lesson is locked" bug). Set the first Current.
        try { Seam.ensureSeeded(storeDir); } catch (Exception e) { /* best-effort */ }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new SeamBridge(), "AndroidSeam");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                if (!HOST.equals(req.getUrl().getHost())) return null;
                String path = req.getUrl().getPath();
                if (path == null) path = "/";
                try {
                    // Read-only API → answered by the on-device seam.
                    if (path.endsWith("/api/exercises")) return json(Seam.exercisesJson(storeDir));
                    if (path.endsWith("/api/current"))   return json(Seam.currentJson(storeDir));
                    if (path.endsWith("/api/book")) {
                        String bq = req.getUrl().getQueryParameter("q");
                        if (bq != null && !bq.trim().isEmpty()) return json(bookSearchJson(bq));
                        String ch = req.getUrl().getQueryParameter("chapter");
                        return json(ch != null ? Seam.bookChapterJson(storeDir, ch) : Seam.bookTocJson(storeDir));
                    }
                    // The Learn surfaces (lessons/quizzes/cheatsheets/glossary) are
                    // bundled markdown/JSON in the seeded store — serve them with the
                    // SAME response shapes as the desktop server (md_collection /
                    // glossary_handler), so the gui works fully offline. These were
                    // silently falling through to null = "unavailable" (Paul's report).
                    if (path.endsWith("/api/lessons"))
                        return json(mdCollection("lessons", "lessons", "lesson", req.getUrl().getQueryParameter("id"), req.getUrl().getQueryParameter("q")));
                    if (path.endsWith("/api/quizzes"))
                        return json(mdCollection("quizzes", "quizzes", "quiz", req.getUrl().getQueryParameter("id"), req.getUrl().getQueryParameter("q")));
                    if (path.endsWith("/api/cheatsheets"))
                        return json(mdCollection("cheatsheets", "cheatsheets", "cheatsheet", req.getUrl().getQueryParameter("id"), req.getUrl().getQueryParameter("q")));
                    if (path.endsWith("/api/glossary"))
                        return json(glossaryJson(req.getUrl().getQueryParameter("term")));
                    // Hint ladder, on-device: the seam runs the desktop ladder;
                    // attempts come from the Termux-run counter above.
                    if (path.endsWith("/api/hint")) {
                        int lvl = 1;
                        try { lvl = Integer.parseInt(req.getUrl().getQueryParameter("level")); }
                        catch (Exception e) { /* default rung 1 */ }
                        return json(Seam.hintJson(storeDir, lvl, attemptsForCurrent()));
                    }
                    // The IDE file explorer, OFFLINE: the tree (/api/workspace) and
                    // one-file read (/api/workspace/file?id=…). Both GETs, so they
                    // work through shouldInterceptRequest (which never sees POST/PUT
                    // bodies). Without these the file explorer was dead on the phone.
                    // Check the more specific /file path first.
                    if (path.endsWith("/api/workspace/file")) {
                        String id = req.getUrl().getQueryParameter("id");
                        String body = Seam.workspaceFileJson(storeDir, id == null ? "" : id);
                        boolean unknown;
                        try { unknown = new org.json.JSONObject(body).has("error"); }
                        catch (Exception e) { unknown = true; }
                        return unknown ? jsonStatus(body, 400, "Bad Request") : json(body);
                    }
                    if (path.endsWith("/api/workspace")) return json(Seam.workspaceJson(storeDir));
                    if (path.contains("/api/")) return null; // run/select/etc → gui handles offline
                    // Everything else = the gui/ shell, served from assets.
                    String asset = "gui" + (path.equals("/") ? "/index.html" : path);
                    InputStream in = getAssets().open(asset);
                    return new WebResourceResponse(mime(asset), "utf-8", in);
                } catch (Exception e) {
                    return null;
                }
            }
        });
        web.loadUrl(BASE);
        setContentView(web);
    }

    private static WebResourceResponse json(String body) {
        WebResourceResponse r = new WebResourceResponse("application/json", "utf-8",
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        r.setResponseHeaders(h);
        return r;
    }

    /** JSON with an explicit HTTP status so the WebView's fetch() sees res.status
     *  (e.g. 400 for an unknown workspace id) exactly as it would from rpro-serve. */
    private static WebResourceResponse jsonStatus(String body, int code, String reason) {
        WebResourceResponse r = new WebResourceResponse("application/json", "utf-8",
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        r.setResponseHeaders(h);
        r.setStatusCodeAndReasonPhrase(code, reason);
        return r;
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".mjs"))  return "text/javascript"; // pdf.js modules
        if (p.endsWith(".js"))   return "text/javascript";
        if (p.endsWith(".css"))  return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg"))  return "image/svg+xml";
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".gif"))  return "image/gif";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".woff2"))return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf") || p.endsWith(".otf")) return "font/ttf";
        if (p.endsWith(".pdf"))  return "application/pdf";           // the offline library books
        if (p.endsWith(".wasm")) return "application/wasm";         // pdf.js image decoders
        if (p.endsWith(".bcmap"))return "application/octet-stream"; // pdf.js CJK cmaps
        if (p.endsWith(".ftl"))  return "text/plain";              // pdf.js viewer locale
        if (p.endsWith(".icc"))  return "application/octet-stream"; // pdf.js color profiles
        return "text/plain";
    }

    private void seedStoreFromAssets() throws Exception {
        // Re-seed whenever the APP VERSION changes, not once-ever: updates ship
        // new/updated content (lessons, quizzes, glossary…), and the old empty
        // marker pinned the FIRST install's content forever. The copy only
        // overwrites files that exist in assets — progress/state files aren't
        // bundled, so a re-seed never touches them.
        File marker = new File(storeDir, ".seeded");
        String want; // the installed versionName (AGP no longer generates BuildConfig)
        try { want = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { want = "unknown"; }
        if (marker.exists() && want.equals(readFile(marker))) return;
        // SYNC the read-only reference dirs before copying (mirrors the desktop
        // server's ensure_seeded): a content update can REMOVE or RENAME a file —
        // e.g. a lesson split replaces one .md with two — and an overlay copy
        // would leave the old file behind as a ghost entry in the lesson list.
        // Progress/state files live in the store root (not bundled) and the
        // exercises dir stays overlay-copied, so neither is touched.
        File store = new File(getFilesDir(), "store");
        for (String sub : new String[] { "lessons", "quizzes", "cheatsheets", "book", "glossary" }) {
            deleteRecursively(new File(store, sub));
        }
        copyAsset(getAssets(), "store", store);
        marker.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(want.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void copyAsset(AssetManager am, String path, File dest) throws Exception {
        String[] kids = am.list(path);
        if (kids != null && kids.length > 0) {
            dest.mkdirs();
            for (String k : kids) copyAsset(am, path + "/" + k, new File(dest, k));
        } else {
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
