package com.jack.zcodeapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * ZCode Remote - shell for the ZCode "Web Remote Control" pairing page.
 * <p>
 * The PC shows a QR code; this app scans it (ScanActivity), opens the pairing
 * URL in its own window with a foreground keep-alive service, and remembers
 * the URL so relaunching the app reconnects to the same session.
 */
public class MainActivity extends Activity {

    static final String FALLBACK_URL = "https://zcode.z.ai";
    private static final String PREFS = "zremote";
    private static final String KEY_URL = "last_url";
    private static final int REQ_FILE_CHOOSER = 51001;
    private static final int REQ_NOTIFY = 51002;
    private static final int REQ_SCAN = 51003;

    SharedPreferences prefs;
    String lastUrl;
    public static final String EXTRA_CONNECT_URL = "connect_url";
    String pendingPairingUrl;   // pairing URL to return to after in-app login completes
    boolean autoReturnArmed;    // an auto-return is scheduled
    boolean autoReturnBlocked;  // one return already fired; no re-arm until remote/new scan
    long armedAt;

    WebView webView;
    FrameLayout root;
    LinearLayout errorOverlay;
    TextView errorTitle;
    TextView errorHint;
    LinearLayout helperBar;     // persistent escape hatch shown off the remote page
    Button helperReturn;
    Button helperBrowser;       // hand the session link to the user's real browser
    android.app.Dialog popupDialog; // live OAuth/login popup window
    ValueCallback<Uri[]> filePathCallback;

    final Handler uiHandler = new Handler(Looper.getMainLooper());
    final Runnable pollNavigation = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                String url = webView.getUrl();
                if (url != null && !url.startsWith("about:")) {
                    handleNavigation(url); // catches SPA pushState hops onPageFinished misses
                }
            }
            uiHandler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        lastUrl = prefs.getString(KEY_URL, null);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0E14);
        root.setFitsSystemWindows(true);

        webView = buildWebView();
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        errorOverlay = buildErrorOverlay();
        root.addView(errorOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildHelperBar();

        setContentView(root);
        requestNotificationsIfNeeded();
        startService(new Intent(this, KeepAliveService.class));
        uiHandler.postDelayed(pollNavigation, 800);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            String connectUrl = getIntent() != null
                    ? getIntent().getStringExtra(EXTRA_CONNECT_URL) : null;
            Uri viewData = getIntent() != null ? getIntent().getData() : null;
            if (connectUrl != null && isPairingUrl(connectUrl)) {
                // Arrived from the notification-bar scan flow.
                setSessionUrl(connectUrl);
            } else if (viewData != null && Intent.ACTION_VIEW.equals(getIntent().getAction())) {
                // Opened from the system (e.g. camera scan → "open with ZCode Remote").
                openIncomingLink(viewData.toString());
            } else if (lastUrl != null) {
                webView.loadUrl(lastUrl);
            } else {
                showOverlay("尚未连接电脑",
                        "扫电脑端 ZCode 的二维码连接；首次需登录一次（与电脑端相同账号）");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        String connectUrl = intent.getStringExtra(EXTRA_CONNECT_URL);
        if (connectUrl != null && isPairingUrl(connectUrl)) {
            setSessionUrl(connectUrl);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            openIncomingLink(intent.getData().toString());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView buildWebView() {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true); // needed by the file chooser
        s.setSupportZoom(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Login flows (OAuth/SSO popups) need window.open to work; popups are
        // re-routed into this WebView by onCreateWindow below.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // Pretend to be plain Chrome: some SPAs gate features on the WebView UA token.
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true); // SSO login may hop domains

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (scheme.equals("http") || scheme.equals("https")) {
                    return false; // stay inside the shell
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || url.startsWith("about:")) {
                    return;
                }
                handleNavigation(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showOverlay("无法连接电脑", "请确认电脑上的 ZCode 还在线，或重新扫码配对");
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Renderer crashed: rebuild the WebView instead of showing a dead white view.
                root.removeView(webView);
                webView.destroy();
                webView = buildWebView();
                root.addView(webView, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                webView.loadUrl(lastUrl != null ? lastUrl : FALLBACK_URL);
                return true;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                    android.os.Message resultMsg) {
                // OAuth/SSO providers open a REAL popup window and rely on
                // window.opener / window.close() to hand the result back.
                // Re-routing it into the main WebView breaks that chain (the
                // provider then redirects to its own site and login never
                // completes), so show it in a proper dialog window instead.
                android.util.Log.d("zremote-nav", "onCreateWindow popup");
                final WebView popup = new WebView(MainActivity.this);
                WebSettings ps = popup.getSettings();
                ps.setJavaScriptEnabled(true);
                ps.setDomStorageEnabled(true);
                ps.setUserAgentString(webView.getSettings().getUserAgentString());
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);

                popup.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView w) {
                        if (popupDialog != null) {
                            popupDialog.dismiss();
                            popupDialog = null;
                        }
                    }
                });
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                        if (!scheme.equals("http") && !scheme.equals("https")) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                            } catch (ActivityNotFoundException ignored) {
                            }
                            return true;
                        }
                        return false; // keep the OAuth flow inside the popup
                    }
                });

                android.app.Dialog dialog = new android.app.Dialog(MainActivity.this);
                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                dialog.setContentView(popup, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.white);
                dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(android.content.DialogInterface d) {
                        popupDialog = null;
                    }
                });
                android.view.Window w = dialog.getWindow();
                if (w != null) {
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                }
                popupDialog = dialog;
                dialog.show();

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (String res : request.getResources()) {
                            // The remote page never legitimately needs mic/camera here.
                            if (!PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                                    && !PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                                request.grant(request.getResources());
                                return;
                            }
                        }
                        request.deny();
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
        });

        wv.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                    String mimeType, long contentLength) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        });

        wv.setKeepScreenOn(true); // it is a task dashboard; keep the display on while watching
        return wv;
    }

    private LinearLayout buildErrorOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF0B0E14);

        errorTitle = new TextView(this);
        errorTitle.setTextColor(0xFFE5E7EB);
        errorTitle.setTextSize(18);
        errorTitle.setGravity(Gravity.CENTER);

        errorHint = new TextView(this);
        errorHint.setTextColor(0xFF9CA3AF);
        errorHint.setTextSize(14);
        errorHint.setGravity(Gravity.CENTER);
        errorHint.setPadding(dp(24), dp(8), dp(24), dp(24));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button retry = overlayButton("重新连接", 0xFF2563EB, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                errorOverlay.setVisibility(View.GONE);
                webView.loadUrl(lastUrl != null ? lastUrl : FALLBACK_URL);
            }
        });
        retry.setVisibility(lastUrl != null ? View.VISIBLE : View.GONE);
        row.addView(retry);

        Button rescan = overlayButton("重新扫码", 0xFF4B5563, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        row.addView(rescan, lp);

        // Login lives inside this app too — offer it up front so first-time
        // users authenticate BEFORE scanning, avoiding the login bounce.
        Button loginFirst = overlayButton("先去登录", 0xFF7C3AED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                errorOverlay.setVisibility(View.GONE);
                webView.loadUrl("https://zcode.z.ai/login");
            }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = dp(12);
        row.addView(loginFirst, lp2);

        // PC panel has a "复制链接" button: paste the copied pairing link here.
        Button paste = overlayButton("粘贴链接连接", 0xFF4B5563, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                CharSequence text = cm == null || cm.getPrimaryClip() == null
                        ? null : cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null && isPairingUrl(text.toString().trim())) {
                    setSessionUrl(text.toString().trim());
                } else {
                    Toast.makeText(MainActivity.this,
                            "剪贴板里没有配对链接，请先在电脑端点「复制链接」并发到手机",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = dp(12);
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        row2.addView(paste, lp3);

        box.addView(errorTitle);
        box.addView(errorHint);
        box.addView(row);
        box.addView(row2);
        box.setVisibility(View.GONE);
        return box;
    }

    private Button overlayButton(String label, int bg, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(bg);
        b.setOnClickListener(onClick);
        return b;
    }

    /**
     * Slim bar docked above the page: visible whenever the WebView is NOT on
     * the pairing/remote page. One tap reliably returns to the remote session,
     * no matter how the site's login flow rerouted us.
     */
    private void buildHelperBar() {
        helperBar = new LinearLayout(this);
        helperBar.setOrientation(LinearLayout.HORIZONTAL);
        helperBar.setGravity(Gravity.CENTER);
        helperBar.setBackgroundColor(0xF2141820);
        helperBar.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView text = new TextView(this);
        text.setText("不在远程控制页？");
        text.setTextColor(0xFF9CA3AF);
        text.setTextSize(13);
        helperBar.addView(text);

        helperReturn = new Button(this);
        helperReturn.setText("回到远程控制");
        helperReturn.setTextSize(13);
        helperReturn.setTextColor(0xFFFFFFFF);
        helperReturn.setBackgroundColor(0xFF2563EB);
        helperReturn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingPairingUrl = null;
                autoReturnArmed = false;
                autoReturnBlocked = false;
                errorOverlay.setVisibility(View.GONE);
                webView.loadUrl(lastUrl != null ? lastUrl : FALLBACK_URL);
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(dp(10), 0, 0, 0);
        helperBar.addView(helperReturn, rp);

        // Escape hatch #2: the user's real browser already holds the z.ai
        // login, so it can always open the session link directly.
        helperBrowser = new Button(this);
        helperBrowser.setText("用浏览器打开");
        helperBrowser.setTextSize(13);
        helperBrowser.setTextColor(0xFFFFFFFF);
        helperBrowser.setBackgroundColor(0xFF4B5563);
        helperBrowser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(lastUrl)));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(dp(10), 0, 0, 0);
        helperBar.addView(helperBrowser, bp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(6);
        helperBar.setVisibility(View.GONE);
        root.addView(helperBar, lp);
    }

    /**
     * Central navigation state machine, fed both by onPageFinished and by a
     * 800ms URL poll (SPA pushState hops do not fire page events).
     */
    private void handleNavigation(String url) {
        android.util.Log.d("zremote-nav", "url=" + url
                + " armed=" + autoReturnArmed + " blocked=" + autoReturnBlocked
                + " lastUrl=" + lastUrl);
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost();
        String path = u.getPath() == null ? "" : u.getPath();
        boolean onRemote = host.equals("zcode.z.ai") && path.contains("/remote/");
        boolean onLogin = host.equals("zcode.z.ai") && path.contains("/login");

        if (onRemote) {
            errorOverlay.setVisibility(View.GONE);
            helperBar.setVisibility(View.GONE);
            pendingPairingUrl = null;
            autoReturnArmed = false;
            autoReturnBlocked = false;
            if (!url.equals(lastUrl)) {
                lastUrl = url; // sid may rotate; always keep the freshest pairing URL
                prefs.edit().putString(KEY_URL, url).apply();
            }
            return;
        }

        // Off the remote page: show the escape-hatch bar when a session exists —
        // but never on a login form (zcode's or an auth provider's), where it
        // would only distract mid-login.
        boolean onAnyLoginPage = url.contains("/login");
        boolean showBar = (lastUrl != null || onLogin) && !onAnyLoginPage;
        helperBar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        helperReturn.setVisibility(lastUrl != null ? View.VISIBLE : View.GONE);
        helperBrowser.setVisibility(lastUrl != null ? View.VISIBLE : View.GONE);
        if (!showBar) {
            return;
        }

        // Best-effort auto-return, fired at most once per arming: after login
        // completes and we land back on zcode.z.ai away from /login (e.g. the
        // marketing page), jump to the pairing URL. Blocked afterwards so a
        // stale/dead link can never loop; a scan or the helper bar re-opens it.
        if (autoReturnArmed && host.equals("zcode.z.ai") && !onLogin
                && SystemClock.elapsedRealtime() - armedAt > 5000) {
            pendingPairingUrl = null;
            autoReturnArmed = false;
            autoReturnBlocked = true;
            errorOverlay.setVisibility(View.GONE);
            webView.loadUrl(lastUrl);
        } else if (!autoReturnArmed && !autoReturnBlocked && lastUrl != null && !onLogin) {
            pendingPairingUrl = lastUrl;
            autoReturnArmed = true;
            armedAt = SystemClock.elapsedRealtime();
        }
    }

    private void showOverlay(String title, String hint) {
        errorTitle.setText(title);
        errorHint.setText(hint);
        webView.stopLoading();
        webView.loadUrl("about:blank");
        errorOverlay.setVisibility(View.VISIBLE);
    }

    private void startScan() {
        startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
    }

    /** A link arrived from outside (system camera scan, share). Only genuine
     *  pairing links become the saved session; anything else is just browsed. */
    private void openIncomingLink(String url) {
        if (isPairingUrl(url)) {
            setSessionUrl(url);
        } else {
            webView.loadUrl(url);
        }
    }

    private static boolean isPairingUrl(String url) {
        Uri u = Uri.parse(url);
        String path = u.getPath() == null ? "" : u.getPath();
        return "zcode.z.ai".equals(u.getHost()) && path.contains("/remote/");
    }

    private void setSessionUrl(String url) {
        lastUrl = url;
        pendingPairingUrl = null;
        autoReturnArmed = false;
        autoReturnBlocked = false;
        prefs.edit().putString(KEY_URL, url).apply();
        errorOverlay.setVisibility(View.GONE);
        webView.loadUrl(url);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String url = data.getStringExtra(ScanActivity.EXTRA_URL);
                if (url != null) {
                    setSessionUrl(url);
                    return;
                }
            }
            if (lastUrl == null) {
                showOverlay("尚未连接电脑", "点下方按钮扫描电脑端 ZCode 显示的二维码");
            }
            return;
        }
        if (requestCode == REQ_FILE_CHOOSER && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (errorOverlay.getVisibility() == View.VISIBLE) {
            moveTaskToBack(true);
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            // Retreat to the background instead of finishing: the keep-alive
            // service holds the remote connection while the user does something else.
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(pollNavigation);
        stopService(new Intent(this, KeepAliveService.class));
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
