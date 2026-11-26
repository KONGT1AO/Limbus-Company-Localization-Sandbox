package com.lody.virtual.client.stub;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.compat.LimbusAuthenticationCompat;

/**
 * 宿主内置的 Firebase Auth / Apple OAuth 授权浏览器页面。
 *
 * <p>Firebase Auth 网页在授权结束后生成的 Android {@code intent://} 回调会显式绑定原版游戏包名
 * {@code com.ProjectMoon.LimbusCompany}，外部 Chrome 因此会直接锁死打开原版游戏，无法回到汉化容器。
 * 本页面在宿主前台进程内完成网页授权，并在 {@link WebViewClient#shouldOverrideUrlLoading} 中直接截获
 * {@code intent://} / {@code genericidp://} / {@code recaptcha://} 回调，剥离原版包名锁定后精准转发回
 * 容器内的游戏认证页面。</p>
 */
public final class LimbusOAuthBrowserActivity extends Activity {
    private static final String TAG = "LimbusVA";
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";
    private static final int LIMBUS_USER_ID = 0;

    private WebView webView;
    private ProgressBar progressBar;
    private boolean redirectForwarded;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri initialUri = getIntent() == null ? null : getIntent().getData();
        if (initialUri == null || !LimbusAuthenticationCompat.isTrustedFirebaseAuthBrowserUrl(
                Intent.ACTION_VIEW,
                initialUri.getScheme(),
                initialUri.getHost(),
                initialUri.getPath())) {
            Log.w(TAG, "Reject untrusted OAuth browser initial uri scheme="
                    + (initialUri == null ? null : initialUri.getScheme())
                    + " host=" + (initialUri == null ? null : initialUri.getHost()));
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        applySystemBarInsets(root);

        // 顶部简洁操作栏（深色工业风，方便横竖屏随时关闭或刷新）
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#181A1F"));
        int padH = dp(12);
        topBar.setPadding(padH, 0, padH, 0);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView closeBtn = new TextView(this);
        closeBtn.setText("关闭");
        closeBtn.setTextColor(Color.parseColor("#E8EAED"));
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        closeBtn.setPadding(dp(8), dp(8), dp(12), dp(8));
        closeBtn.setOnClickListener(v -> finish());
        topBar.addView(closeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView titleView = new TextView(this);
        titleView.setText("Apple / 网页账号授权");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(titleView, titleLp);

        TextView reloadBtn = new TextView(this);
        reloadBtn.setText("刷新");
        reloadBtn.setTextColor(Color.parseColor("#E8EAED"));
        reloadBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        reloadBtn.setPadding(dp(12), dp(8), dp(8), dp(8));
        reloadBtn.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
            }
        });
        topBar.addView(reloadBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(false);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        String cleanedUa = LimbusAuthenticationCompat.stripWebViewMarkerFromUserAgent(
                settings.getUserAgentString());
        if (!cleanedUa.isEmpty()) {
            settings.setUserAgentString(cleanedUa);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar == null) {
                    return;
                }
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    if (progressBar.getVisibility() != View.VISIBLE) {
                        progressBar.setVisibility(View.VISIBLE);
                    }
                    progressBar.setProgress(newProgress);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                return handleNavigationUrl(uri == null ? null : uri.toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigationUrl(url);
            }
        });

        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(webView, webLp);
        setContentView(root);

        Log.i(TAG, "LimbusOAuthBrowserActivity loading url="
                + initialUri.getScheme() + "://" + initialUri.getHost() + initialUri.getPath());
        webView.loadUrl(initialUri.toString());
    }

    private boolean handleNavigationUrl(String url) {
        if (url == null || url.isEmpty()) {
            return true;
        }
        String redirectUrl = LimbusAuthenticationCompat.extractFirebaseAuthRedirectUrl(url);
        if (redirectUrl != null) {
            forwardOAuthCallbackToContainer(url, redirectUrl);
            return true;
        }
        if (LimbusAuthenticationCompat.isAllowedOAuthWebViewNavigationUrl(url)) {
            return false;
        }
        Log.w(TAG, "LimbusOAuthBrowserActivity blocked unsupported navigation scheme="
                + Uri.parse(url).getScheme());
        return true;
    }

    private void forwardOAuthCallbackToContainer(String rawUrl, String redirectUrl) {
        if (redirectForwarded) {
            return;
        }
        Uri callbackUri = Uri.parse(redirectUrl);
        String targetActivity = LimbusAuthenticationCompat.resolveFirebaseAuthRedirectActivity(
                callbackUri.getScheme(),
                callbackUri.getHost(),
                callbackUri.getPath());
        if (targetActivity == null) {
            Log.w(TAG, "LimbusOAuthBrowserActivity rejected invalid callback target");
            return;
        }
        redirectForwarded = true;
        Intent virtualIntent = new Intent(Intent.ACTION_VIEW, callbackUri);
        virtualIntent.addCategory(Intent.CATEGORY_DEFAULT);
        virtualIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        virtualIntent.setClassName(LIMBUS_PACKAGE, targetActivity);
        virtualIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        java.util.List<String> copiedKeys = new java.util.ArrayList<>();
        if (rawUrl != null && rawUrl.regionMatches(true, 0, "intent://", 0, "intent://".length())) {
            try {
                Intent parsed = Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME);
                Bundle parsedExtras = parsed == null ? null : parsed.getExtras();
                if (parsedExtras != null) {
                    for (String key : parsedExtras.keySet()) {
                        if (!LimbusAuthenticationCompat.isAllowedFirebaseAuthCallbackExtraKey(key)) {
                            continue;
                        }
                        @SuppressWarnings("deprecation")
                        Object value = parsedExtras.get(key);
                        if (value instanceof String) {
                            virtualIntent.putExtra(key, (String) value);
                            copiedKeys.add(key);
                        } else if (value instanceof Boolean) {
                            virtualIntent.putExtra(key, (Boolean) value);
                            copiedKeys.add(key);
                        } else if (value instanceof Integer) {
                            virtualIntent.putExtra(key, (Integer) value);
                            copiedKeys.add(key);
                        } else if (value instanceof Long) {
                            virtualIntent.putExtra(key, (Long) value);
                            copiedKeys.add(key);
                        }
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "LimbusOAuthBrowserActivity failed to parse intent:// extras", error);
            }
        }

        int result = VActivityManager.get().startActivity(virtualIntent, LIMBUS_USER_ID);
        Log.i(TAG, "LimbusOAuthBrowserActivity forwarded callback scheme="
                + callbackUri.getScheme() + " target=" + targetActivity
                + " extraKeys=" + copiedKeys + " result=" + result);
        // 延后 150ms 关闭授权页，确保跨进程 scheduleNewIntent 严格在目标页 onResume 之前送达。
        if (webView != null) {
            webView.postDelayed(this::finish, 150L);
        } else {
            finish();
        }
    }

    private void applySystemBarInsets(View root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout()
                            | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.setWebViewClient(new WebViewClient());
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
