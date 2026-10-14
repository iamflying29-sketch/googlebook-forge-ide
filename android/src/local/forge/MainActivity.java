package local.forge;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.net.Uri;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.content.pm.PackageManager;
import android.Manifest;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
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
    /** Path this window opens on the Forge server, for example "/?folder=/home/you/projects". */
    private String startPath = "/";
    private ValueCallback<Uri[]> fileCallback;
    private static final int PICK_FILES = 42;
    private static final int REQ_MIC = 43;
    private Voice voice;
    /** What to do once the microphone permission is answered: start dictation, or answer a page's request. */
    private Runnable afterMicGranted;
    private Runnable afterMicDenied;

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
            /** Lets the IDE's image button open the system picker (WebView has no file chooser of its own). */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = params.createIntent();
                    String[] types = params.getAcceptTypes();
                    boolean any = types == null || types.length == 0 || (types.length == 1 && (types[0] == null || types[0].isEmpty()));
                    if (any) {
                        // The attachment button accepts every kind of file.
                        i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                    }
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, PICK_FILES);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }

            /** Microphone for the IDE's own page only (the browser fallback for voice input). */
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                main.post(() -> {
                    String origin = String.valueOf(request.getOrigin());
                    boolean audioOnly = true;
                    for (String r : request.getResources()) if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audioOnly = false;
                    if (!origin.startsWith(SERVER) || !audioOnly) { request.deny(); return; }
                    withMicrophone(() -> request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}), request::deny);
                });
            }

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
        // Edge-to-edge (Android 15 and later): keep the IDE clear of the status bar in maximized and full-screen windows.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0f1117"));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        VisionControl.ensure(this);
        String p = getIntent() != null ? getIntent().getStringExtra("path") : null;
        if (p != null && p.startsWith("/")) startPath = p;
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
                if (up) { showingOffline = false; web.loadUrl(SERVER + startPath); }
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
        public String appVersion() { return "1.4"; }

        /** Voice input with Android's speech recognizer. Events come back through window.ForgeVoice._native(). */
        @JavascriptInterface
        public boolean voiceAvailable() { return Voice.available(MainActivity.this); }

        @JavascriptInterface
        public void voiceStart(String lang) {
            main.post(() -> withMicrophone(() -> {
                if (voice == null) voice = new Voice(MainActivity.this, MainActivity.this::voiceEvent);
                voice.start(lang);
            }, () -> voiceEvent(voiceError("Forge needs permission to use the microphone for voice input."))));
        }

        @JavascriptInterface
        public void voiceStop() { main.post(() -> { if (voice != null) voice.stop(); }); }

        /** Opens a separate Forge window (its own task in the taskbar), like Cursor's New Window. */
        @JavascriptInterface
        public void newWindow(String path) {
            if (path == null || !path.startsWith("/")) path = "/";
            final String target = path;
            main.post(() -> {
                Intent i = new Intent(MainActivity.this, MainActivity.class);
                i.putExtra("path", target);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                startActivity(i);
            });
        }

        /** Labels this window in the taskbar and the overview with its folder name. */
        @JavascriptInterface
        public void setTitle(String name) {
            final String label = (name == null || name.isEmpty()) ? "Forge" : name + " · Forge";
            main.post(() -> {
                try { setTaskDescription(new ActivityManager.TaskDescription(label)); } catch (Exception ignored) { }
            });
        }

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

    private static org.json.JSONObject voiceError(String text) {
        org.json.JSONObject o = new org.json.JSONObject();
        try { o.put("type", "error"); o.put("text", text); } catch (Exception ignored) { }
        return o;
    }

    private void voiceEvent(org.json.JSONObject event) {
        final String js = "window.ForgeVoice && window.ForgeVoice._native(" + org.json.JSONObject.quote(event.toString()) + ")";
        main.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    /** Runs granted() once the app may record audio, asking you first if needed. */
    private void withMicrophone(Runnable granted, Runnable denied) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { granted.run(); return; }
        if (afterMicDenied != null) afterMicDenied.run();
        afterMicGranted = granted;
        afterMicDenied = denied;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        Runnable ok = afterMicGranted, no = afterMicDenied;
        afterMicGranted = null; afterMicDenied = null;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && ok != null) ok.run();
        else if (!granted && no != null) no.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Never keep the microphone open for a window you are not using.
        if (voice != null) voice.stop();
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int k = 0; k < n; k++) result[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
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
        if (voice != null) { voice.stop(); voice.release(); }
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
