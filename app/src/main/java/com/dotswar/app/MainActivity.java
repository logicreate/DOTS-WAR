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
import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import android.net.Uri;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;

public class MainActivity extends Activity {
    private WebView webView;
    private String insetsJs = "";
    // The game is served from this origin (not file://) so Web Workers, localStorage,
    // history.pushState (hardware Back button) and Firebase work like in a real browser.
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    // Firebase Console -> Authentication -> Sign-in method -> Google -> "Web client ID" (ends with .apps.googleusercontent.com)
    private static final String WEB_CLIENT_ID = "911143426593-ecmf3a8g72fnm42m93tfc93fkv99gu5p.apps.googleusercontent.com";
    // ── Push notifications (Firebase Cloud Messaging) ──
    // Firebase Console -> Project settings -> Your apps -> Android app "com.dotswar.app" -> App ID
    // (looks like 1:911143426593:android:0123456789abcdef). Until it is pasted, push is simply off.
    private static final String FCM_APP_ID = "PASTE_ANDROID_APP_ID";
    private static final String FCM_API_KEY = "AIzaSyCxGB68ogFcdRk5Aeav_6rZmCNmIHY8_K0";
    private static final int REQ_NOTIF = 77;
    private boolean pageReady = false;
    private String pendingPushJs = null;

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
            // The WebView engine can be killed by the system (low memory during a phone call).
            // Instead of letting the app crash, rebuild the screen: the game offers to resume the autosaved battle.
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                webView = null;
                recreate();
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                pushInsets(); // the page is ready: hand it the current safe-area insets
                pageReady = true;
                if (pendingPushJs != null) { final String pj = pendingPushJs; pendingPushJs = null; view.postDelayed(() -> js(pj), 2500); }
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

        createNotificationChannel();
        handlePushIntent(getIntent());
        checkPlayUpdate();
        webView.loadUrl(START_URL);
    }

    // A tapped notification opens the app with the payload as extras: {type:"invite", room:"1234"} or {type:"friend"}
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePushIntent(intent);
    }
    private void handlePushIntent(Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        String room = intent.getExtras().getString("room");
        String type = intent.getExtras().getString("type");
        if (room == null && type == null) return;
        String code = "openPushTarget({type:" + jsStr(type == null ? "" : type) + ",room:" + jsStr(room == null ? "" : room) + "})";
        if (pageReady) js(code); else pendingPushJs = code;
    }
    // ── Google Play in-app update: if Play has a newer version, show Google's own update screen ──
    private static final int REQ_UPDATE = 78;
    private AppUpdateManager updateManager;
    private void checkPlayUpdate() {
        try {
            updateManager = AppUpdateManagerFactory.create(this);
            updateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                        && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    try { updateManager.startUpdateFlowForResult(info, this, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(), REQ_UPDATE); }
                    catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}   // not installed from Play (debug APK): nothing to do
    }
    private void resumePlayUpdate() {
        if (updateManager == null) return;
        try {
            updateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
                if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    try { updateManager.startUpdateFlowForResult(info, this, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(), REQ_UPDATE); }
                    catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel("invites", "Приглашения и друзья", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Приглашения в бой и заявки в друзья");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
    private void startPush() {
        if (FCM_APP_ID.startsWith("PASTE_")) { js("onPushError('Android App ID')"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        fetchPushToken();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIF) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) fetchPushToken();
        else js("showToast(T('pushDenied'),3500)");
    }
    private void fetchPushToken() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseOptions o = new FirebaseOptions.Builder()
                        .setApplicationId(FCM_APP_ID).setApiKey(FCM_API_KEY)
                        .setProjectId("dots-2d4e4").setGcmSenderId("911143426593").build();
                FirebaseApp.initializeApp(this, o);
            }
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t -> {
                if (t.isSuccessful() && t.getResult() != null) {
                    final String tok = t.getResult();
                    runOnUiThread(() -> js("onPushToken('android'," + jsStr(tok) + ")"));
                } else {
                    final String m = t.getException() == null ? "token" : t.getException().getClass().getSimpleName();
                    runOnUiThread(() -> js("onPushError(" + jsStr(m) + ")"));
                }
            });
        } catch (Exception e) {
            js("onPushError(" + jsStr(e.getClass().getSimpleName()) + ")");
        }
    }

    /** Called from the page: window.AndroidBridge.googleSignIn() -> native Google sign-in -> onGoogleIdToken(token) in JS */
    private class Bridge {
        @JavascriptInterface
        public void googleSignIn() {
            runOnUiThread(MainActivity.this::startGoogleSignIn);
        }
        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.dotswar.app"))); } catch (Exception ignored) {}
                }
            });
        }
        @JavascriptInterface
        public void enablePush() {
            runOnUiThread(MainActivity.this::startPush);
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
                    final String msg = t + (m.isEmpty() ? "" : ": " + m) + " | app SHA-1: " + signingSha1();
                    runOnUiThread(() -> js("showToast(T('gFail')+' — '+" + jsStr(msg) + ",8000)"));
                }
            });
    }
    // SHA-1 of the certificate this installed APK is actually signed with —
    // must match an Android OAuth client in Google Cloud for sign-in to work.
    private String signingSha1() {
        try {
            android.content.pm.Signature[] sigs;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) { if (i > 0) sb.append(':'); sb.append(String.format("%02X", d[i])); }
            return sb.toString();
        } catch (Exception e) { return "?"; }
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
        if (webView != null) { webView.evaluateJavascript("window.appPause&&appPause()", null); webView.onPause(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) { webView.onResume(); webView.evaluateJavascript("window.appResume&&appResume()", null); }
        resumePlayUpdate();
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
