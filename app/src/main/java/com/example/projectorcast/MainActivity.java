package com.example.projectorcast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Экран-«пульт»: браузер на планшете + управление показом на проекторе.
 *
 * Сам показ и плавающая кнопка живут в {@link ProjectionService}, поэтому
 * трансляция продолжается, даже когда это окно свёрнуто.
 */
public class MainActivity extends Activity {

    private static final String HOME_HTML =
            "<!doctype html><html><head>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{font-family:sans-serif;margin:0;padding:24px;background:#111;color:#eee}"
            + "h1{font-size:20px;margin:0 0 12px}"
            + "a.btn{display:block;margin:10px 0;padding:14px;background:#1e88e5;color:#fff;"
            + "text-decoration:none;border-radius:8px;font-size:16px}"
            + "p{color:#aaa;line-height:1.6}</style></head><body>"
            + "<h1>Проектор WebCast</h1>"
            + "<p>① «Подключить проектор» — выберите проектор.<br>"
            + "② Откройте страницу с видео (адрес сверху).<br>"
            + "③ «На проектор ▶» — видео уйдёт на проектор.<br>"
            + "④ «Затемнить» или плавающая кнопка «ТВ» — гасим планшет, показ идёт.</p>"
            + "<a class='btn' href='https://www.w3schools.com/html/mov_bbb.mp4'>▶ Тестовое видео (короткое)</a>"
            + "<a class='btn' href='https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4'>▶ Тестовое видео (Big Buck Bunny)</a>"
            + "<a class='btn' href='https://www.google.com'>Открыть Google</a>"
            + "</body></html>";

    private WebView web;
    private EditText urlBar;
    private TextView status;
    private FrameLayout webContainer;
    private ProgressBar progress;

    private DisplayManager displayManager;
    private boolean projecting = false;
    private String lastUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlBar = findViewById(R.id.url_bar);
        status = findViewById(R.id.status);
        webContainer = findViewById(R.id.web_container);
        progress = findViewById(R.id.progress);

        setupWebView();
        webContainer.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                loadFromBar();
                return true;
            }
            return false;
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        findViewById(R.id.btn_fwd).setOnClickListener(v -> { if (web.canGoForward()) web.goForward(); });
        findViewById(R.id.btn_reload).setOnClickListener(v -> web.reload());
        findViewById(R.id.btn_go).setOnClickListener(v -> loadFromBar());

        findViewById(R.id.btn_connect).setOnClickListener(v -> showConnectDialog());
        findViewById(R.id.btn_project).setOnClickListener(v -> project());
        findViewById(R.id.btn_blackout).setOnClickListener(v -> blackout());
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopProjection());

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, null);

        maybeRequestNotifications();

        web.loadDataWithBaseURL("about:blank", HOME_HTML, "text/html", "UTF-8", null);
        updateStatus();
    }

    private void setupWebView() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    view.loadUrl(u);
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!isHome(url)) {
                    lastUrl = url;
                    urlBar.setText(url);
                    if (projecting) refreshProjector(url); // держим проектор на текущей странице
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    private boolean isHome(String url) {
        return url == null || url.startsWith("about:") || url.startsWith("data:");
    }

    private void loadFromBar() {
        String q = urlBar.getText().toString().trim();
        if (TextUtils.isEmpty(q)) return;
        hideKeyboard();
        web.loadUrl(normalizeUrl(q));
    }

    private String normalizeUrl(String q) {
        if (q.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) return q;
        if (q.contains(" ") || !q.contains(".")) {
            return "https://www.google.com/search?q=" + Uri.encode(q);
        }
        return "https://" + q;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    // ---- Подключение проектора (Miracast) -----------------------------------

    private void showConnectDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.connect_title)
                .setMessage(R.string.connect_message)
                .setPositiveButton(R.string.open, (d, w) -> openCastPicker())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openCastPicker() {
        // Сначала пробуем открыть сразу Samsung Smart View, затем — системные экраны трансляции.
        Intent[] candidates = new Intent[]{
                new Intent("com.samsung.android.smartmirroring.action.SMART_VIEW"),
                new Intent("com.samsung.android.smartmirroring.action.CONNECT"),
                new Intent(Settings.ACTION_CAST_SETTINGS),
                new Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS)
        };
        for (Intent i : candidates) {
            if (i.resolveActivity(getPackageManager()) != null) {
                try {
                    startActivity(i);
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        Toast.makeText(this, R.string.no_cast_settings, Toast.LENGTH_LONG).show();
    }

    private Display targetDisplay() {
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return displays.length > 0 ? displays[0] : null;
    }

    // ---- Вывод на проектор (через сервис) -----------------------------------

    private void project() {
        if (!hasOverlayPermission()) {
            promptOverlayPermission();
            return;
        }
        if (targetDisplay() == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.no_projector_title)
                    .setMessage(R.string.no_projector_message)
                    .setPositiveButton(R.string.connect_projector, (d, w) -> showConnectDialog())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        String url = lastUrl != null ? lastUrl : web.getUrl();
        if (url == null || isHome(url)) {
            Toast.makeText(this, R.string.open_page_first, Toast.LENGTH_LONG).show();
            return;
        }
        sendToService(ProjectionService.ACTION_PROJECT, url);
        projecting = true;
        updateStatus();
        Toast.makeText(this, R.string.sent_to_projector, Toast.LENGTH_SHORT).show();
    }

    private void refreshProjector(String url) {
        sendToService(ProjectionService.ACTION_PROJECT, url);
    }

    private void blackout() {
        if (!projecting) {
            Toast.makeText(this, R.string.blackout_needs_projection, Toast.LENGTH_SHORT).show();
            return;
        }
        sendToService(ProjectionService.ACTION_TOGGLE_BLACKOUT, null);
    }

    private void stopProjection() {
        sendToService(ProjectionService.ACTION_STOP, null);
        projecting = false;
        updateStatus();
    }

    private void sendToService(String action, String url) {
        Intent i = new Intent(this, ProjectionService.class).setAction(action);
        if (url != null) i.putExtra(ProjectionService.EXTRA_URL, url);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    // ---- Разрешения ----------------------------------------------------------

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void promptOverlayPermission() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_title)
                .setMessage(R.string.overlay_message)
                .setPositiveButton(R.string.grant, (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ---- Статус --------------------------------------------------------------

    private void updateStatus() {
        Display d = targetDisplay();
        if (projecting) {
            status.setText(getString(R.string.status_projecting, d != null ? d.getName() : "?"));
        } else if (d != null) {
            status.setText(getString(R.string.status_ready, d.getName()));
        } else {
            status.setText(R.string.status_no_display);
        }
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            updateStatus();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (projecting && targetDisplay() == null) projecting = false;
            updateStatus();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updateStatus();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
