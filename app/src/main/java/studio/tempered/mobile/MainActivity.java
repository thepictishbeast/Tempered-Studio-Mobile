package studio.tempered.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.AssetManager;
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
                    if (path.contains("/api/")) return null; // run/select/hint/etc → gui handles offline
                    // Everything else = the gui/ shell, served from assets.
                    String asset = "gui" + (path.equals("/") ? "/index.html" : path);
                    InputStream in = getAssets().open(asset);
                    return new WebResourceResponse(mime(asset), "utf-8", in);
                } catch (Exception e) {
                    return null;
                }
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(
                    "(function(){try{var id=AndroidSeam.languageId();var b=document.createElement('div');" +
                    "b.textContent='● on-device seam: '+id+' (offline · rpro-lang via JNI)';" +
                    "b.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:99999;font:12px/1.6 " +
                    "ui-monospace,monospace;text-align:center;color:#0e1116;background:#f74c00;padding:4px';" +
                    "document.body.appendChild(b);}catch(e){}})();", null);
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
        if (p.endsWith(".js"))   return "text/javascript";
        if (p.endsWith(".css"))  return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg"))  return "image/svg+xml";
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".woff2"))return "font/woff2";
        return "text/plain";
    }

    private void seedStoreFromAssets() throws Exception {
        File marker = new File(storeDir, ".seeded");
        if (marker.exists()) return;
        copyAsset(getAssets(), "store", new File(getFilesDir(), "store"));
        marker.getParentFile().mkdirs();
        new FileOutputStream(marker).close();
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
