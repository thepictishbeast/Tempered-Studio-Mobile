package studio.tempered.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Tempered Studio — Android surface (v0.1). Renders the SAME single-file
 * gui/ shell shipped on web/desktop, loaded from bundled assets. With no
 * local server the GUI falls back to its static demo (offline preview of the
 * real layout). Wiring an embedded, NDK-cross-compiled rpro-serve so exercises
 * run on-device is the next step (see README) — this proves the mobile surface
 * builds and shows the real product UI.
 */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.loadUrl("file:///android_asset/gui/index.html");
        setContentView(web);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
