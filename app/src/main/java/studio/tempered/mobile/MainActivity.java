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
        if (explain) code = code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "");
        else recordAttemptForCurrent(); // every run/check/test = a genuine attempt (hint gate)
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
    private String mdCollection(String subdir, String listKey, String itemKey, String id) {
        File[] files = new File(storeDir, subdir).listFiles((d, n) -> n.endsWith(".md"));
        if (files == null) files = new File[0];
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
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
        storeDir = new File(getFilesDir(), "store").getAbsolutePath();
        try { seedStoreFromAssets(); } catch (Exception e) { /* best-effort */ }

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
                        String ch = req.getUrl().getQueryParameter("chapter");
                        return json(ch != null ? Seam.bookChapterJson(storeDir, ch) : Seam.bookTocJson(storeDir));
                    }
                    // The Learn surfaces (lessons/quizzes/cheatsheets/glossary) are
                    // bundled markdown/JSON in the seeded store — serve them with the
                    // SAME response shapes as the desktop server (md_collection /
                    // glossary_handler), so the gui works fully offline. These were
                    // silently falling through to null = "unavailable" (Paul's report).
                    if (path.endsWith("/api/lessons"))
                        return json(mdCollection("lessons", "lessons", "lesson", req.getUrl().getQueryParameter("id")));
                    if (path.endsWith("/api/quizzes"))
                        return json(mdCollection("quizzes", "quizzes", "quiz", req.getUrl().getQueryParameter("id")));
                    if (path.endsWith("/api/cheatsheets"))
                        return json(mdCollection("cheatsheets", "cheatsheets", "cheatsheet", req.getUrl().getQueryParameter("id")));
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
        copyAsset(getAssets(), "store", new File(getFilesDir(), "store"));
        marker.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(want.getBytes(StandardCharsets.UTF_8));
        }
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
