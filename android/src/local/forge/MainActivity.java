package local.forge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Forge: a native window around the Forge IDE server that runs in the Linux Terminal VM.
 * The IDE itself (editor, agent, terminal) is served by the VM at http://localhost:8765; this app is the
 * desktop shell for it, the same way Cursor is a native shell around a web app.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Forge";
    private static final String SERVER = "http://localhost:8765";
    private static final String TERMINAL_PACKAGE = "com.android.virtualization.terminal";

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean checking = false;
    private boolean showingOffline = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0f1117"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " ForgeApp/1.0");
        web.addJavascriptInterface(new Bridge(), "ForgeApp");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "console[" + m.messageLevel() + "] " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(SERVER) || url.startsWith("file:///android_asset/")) return false;
                // Anything else (links in chat replies) opens in the system browser.
                try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); } catch (Exception ignored) { }
                return true;
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
            }
        });
        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else connect();
        if (getIntent() != null && getIntent().getBooleanExtra("start_engine", false)) EngineService.start(this);
    }

    /** Load the IDE if the server answers, otherwise show the offline page which retries by itself. */
    private void connect() {
        if (checking) return;
        checking = true;
        new Thread(() -> {
            boolean up = serverUp();
            main.post(() -> {
                checking = false;
                if (up) { showingOffline = false; web.loadUrl(SERVER + "/"); }
                else showOffline();
            });
        }, "forge-probe").start();
    }

    private void showOffline() {
        if (showingOffline) return;
        showingOffline = true;
        web.loadUrl("file:///android_asset/offline.html");
    }

    private static boolean serverUp() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(SERVER + "/api/status").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Called from the offline page and from the IDE's Engine card. */
    public class Bridge {
        @JavascriptInterface
        public String engineStatus() { return EngineService.status(MainActivity.this).toString(); }

        @JavascriptInterface
        public void engineStart() { main.post(() -> EngineService.start(MainActivity.this)); }

        @JavascriptInterface
        public void engineStop() { main.post(() -> EngineService.stop(MainActivity.this)); }

        @JavascriptInterface
        public String engineLog() { return EngineService.logTail(); }

        @JavascriptInterface
        public String appVersion() { return "1.1"; }

        @JavascriptInterface
        public void retry() { main.post(() -> { showingOffline = false; connect(); }); }

        @JavascriptInterface
        public void openTerminal() {
            main.post(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(TERMINAL_PACKAGE);
                if (i != null) { try { startActivity(i); } catch (Exception e) { Log.w(TAG, "cannot open Terminal", e); } }
            });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Ctrl+R reloads the IDE (handy after a server restart); everything else goes to the page.
        if (keyCode == KeyEvent.KEYCODE_R && event.isCtrlPressed()) { showingOffline = false; connect(); return true; }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // The IDE is a single page; Back should not leave it by accident.
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("start_engine", false)) EngineService.start(this);
        if (intent != null && intent.getBooleanExtra("stop_engine", false)) EngineService.stop(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (showingOffline) { showingOffline = false; connect(); }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
