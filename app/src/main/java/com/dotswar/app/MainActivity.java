package com.dotswar.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import android.webkit.JavascriptInterface;
import android.os.CancellationSignal;
import java.util.concurrent.Executors;

import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private String insetsJs = "";
    // The game is served from this origin (not file://) so Web Workers, localStorage,
    // history.pushState (hardware Back button) and Firebase work like in a real browser.
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    // Firebase Console -> Authentication -> Sign-in method -> Google -> "Web client ID" (ends with .apps.googleusercontent.com)
    private static final String WEB_CLIENT_ID = "PASTE_YOUR_WEB_CLIENT_ID.apps.googleusercontent.com";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Edge-to-edge: the WebView covers the whole screen, system bars are transparent,
        //    and the page itself keeps its content clear of them via safe-area insets
        //    (passed in as CSS variables below, because Android WebView does not fill
        //    env(safe-area-inset-*) on its own). This removes the light strip that the
        //    default navigation bar used to leave at the bottom.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ic.setAppearanceLightStatusBars(false);       // light icons on our dark background
        ic.setAppearanceLightNavigationBars(false);
        ic.hide(WindowInsetsCompat.Type.statusBars()); // immersive: no status bar, swipe to peek
        ic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        // Keep the screen on during a battle (turn timers, online play)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF050310);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);      // progress, settings, language, stats
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); // everything is https now
        ws.setTextZoom(100); // ignore the system font-size setting so the layout stays intact

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Stay inside the app for our own origin; anything else (share links etc.) is ignored
                return !"appassets.androidplatform.net".equals(request.getUrl().getHost());
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                pushInsets(); // the page is ready: hand it the current safe-area insets
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        // System bar / display-cutout insets -> CSS variables --sat/--sar/--sab/--sal (CSS px)
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            float d = getResources().getDisplayMetrics().density;
            insetsJs = String.format(Locale.US,
                    "(function(s){s.setProperty('--sat','%dpx');s.setProperty('--sar','%dpx');" +
                    "s.setProperty('--sab','%dpx');s.setProperty('--sal','%dpx');" +
                    "window.dispatchEvent(new Event('resize'));})(document.documentElement.style);",
                    Math.round(sb.top / d), Math.round(sb.right / d), Math.round(sb.bottom / d), Math.round(sb.left / d));
            pushInsets();
            return WindowInsetsCompat.CONSUMED;
        });

        webView.loadUrl(START_URL);
    }

    /** Called from the page: window.AndroidBridge.googleSignIn() -> native Google sign-in -> onGoogleIdToken(token) in JS */
    private class Bridge {
        @JavascriptInterface
        public void googleSignIn() {
            runOnUiThread(MainActivity.this::startGoogleSignIn);
        }
    }

    private void startGoogleSignIn() {
        if (WEB_CLIENT_ID.startsWith("PASTE_")) { js("showToast(T('gFail')+' (no client id)',6000)"); return; }
        GetGoogleIdOption opt = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build();
        requestCredential(new GetCredentialRequest.Builder().addCredentialOption(opt).build(), true);
    }

    // Second attempt: the explicit "Sign in with Google" button flow. It shows the
    // account chooser even when the One Tap flow reports NoCredentialException.
    private void startGoogleSignInFallback() {
        GetSignInWithGoogleOption opt = new GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build();
        requestCredential(new GetCredentialRequest.Builder().addCredentialOption(opt).build(), false);
    }

    private void requestCredential(GetCredentialRequest req, final boolean allowFallback) {
        CredentialManager cm = CredentialManager.create(this);
        cm.getCredentialAsync(this, req, new CancellationSignal(), Executors.newSingleThreadExecutor(),
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override public void onResult(GetCredentialResponse result) {
                    try {
                        GoogleIdTokenCredential c = GoogleIdTokenCredential.createFrom(result.getCredential().getData());
                        final String token = c.getIdToken();
                        runOnUiThread(() -> js("onGoogleIdToken(" + jsStr(token) + ")"));
                    } catch (Exception e) { fail(e); }
                }
                @Override public void onError(GetCredentialException e) {
                    if (allowFallback && e instanceof NoCredentialException) { runOnUiThread(MainActivity.this::startGoogleSignInFallback); return; }
                    fail(e);
                }
                private void fail(Exception e) {
                    String t = e.getClass().getSimpleName();
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    if (m.length() > 120) m = m.substring(0, 120);
                    final String msg = t + (m.isEmpty() ? "" : ": " + m);
                    runOnUiThread(() -> js("showToast(T('gFail')+' — '+" + jsStr(msg) + ",8000)"));
                }
            });
    }
    private static String jsStr(String v) { return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private void js(String code) { if (webView != null) webView.evaluateJavascript(code, null); }

    private void pushInsets() {
        if (webView != null && !insetsJs.isEmpty()) webView.evaluateJavascript(insetsJs, null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Hardware Back: the page keeps one history entry while away from the main menu,
        // so goBack() returns to the menu (or asks to confirm leaving a live battle);
        // on the menu itself there is nothing to go back to and the app closes as usual.
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
