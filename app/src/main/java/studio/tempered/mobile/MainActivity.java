package studio.tempered.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Tempered Studio — Android surface. Hosts the shared gui/ shell in a WebView and
 * exposes the on-device Rust seam (via JNI, libtempered_seam.so) to its JS as
 * `AndroidSeam`. On load it calls the seam for the language id and shows a banner
 * — concrete proof the same rpro-lang the other surfaces use runs here too.
 */
public class MainActivity extends Activity {
    private WebView web;

    /** Bridge exposing the native seam to the WebView's JavaScript. */
    public static final class SeamBridge {
        @JavascriptInterface public String languageId() { return Seam.languageId(); }
        @JavascriptInterface public String exerciseTemplate(String concept) { return Seam.exerciseTemplate(concept); }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new SeamBridge(), "AndroidSeam");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(
                    "(function(){try{var id=AndroidSeam.languageId();" +
                    "var b=document.createElement('div');" +
                    "b.textContent='● on-device seam: '+id+' (rpro-lang via JNI)';" +
                    "b.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:99999;" +
                    "font:12px/1.6 ui-monospace,monospace;text-align:center;color:#0e1116;" +
                    "background:#f74c00;padding:4px';document.body.appendChild(b);" +
                    "}catch(e){}})();", null);
            }
        });
        web.loadUrl("file:///android_asset/gui/index.html");
        setContentView(web);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
