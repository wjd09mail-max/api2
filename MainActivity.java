package com.stockalert;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.stockalert.core.AppCore;

/** 앱 화면: 내장 웹서버(127.0.0.1)의 감시 화면을 WebView 로 표시 */
public class MainActivity extends Activity {
    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int waitCount = 0;

    private static final String LOADING = "<html><body style='background:#0e131d;color:#8592ad;font-family:sans-serif;"
            + "display:flex;align-items:center;justify-content:center;height:90vh'>불러오는 중…</body></html>";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0E131D);
        getWindow().setNavigationBarColor(0xFF121A28);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0E131D);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) handler.postDelayed(MainActivity.this::loadApp, 1000);
            }
        });
        web.setWebChromeClient(new WebChromeClient());   // JS 확인창(confirm/alert) 표시 허용
        web.loadDataWithBaseURL(null, LOADING, "text/html", "utf-8", null);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        AlertService.start(this);
        loadApp();
        handler.postDelayed(this::askBatteryOnce, 2500);
    }

    private void loadApp() {
        AppCore c = AlertService.core();
        if (c == null || !c.isServing()) {
            if (++waitCount == 40) Toast.makeText(this, "시작이 지연되고 있습니다. 잠시 후 다시 열어 주세요.", Toast.LENGTH_LONG).show();
            if (waitCount < 200) handler.postDelayed(this::loadApp, 300);
            return;
        }
        web.loadUrl(c.appUrl());
    }

    private boolean handleUrl(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        String host = u.getHost() == null ? "" : u.getHost();
        if ("app".equals(scheme)) {
            if ("battery".equals(host)) {
                requestBatteryExemption(true);
            } else if ("exit".equals(host)) {
                Intent stop = new Intent(this, AlertService.class);
                stop.setAction(AlertService.ACTION_STOP);
                startService(stop);
                finishAndRemoveTask();
            }
            return true;
        }
        boolean local = "127.0.0.1".equals(host) || "localhost".equals(host);
        if (local && "/kakao/login".equals(u.getPath())) {
            // 카카오 로그인은 휴대폰 기본 브라우저에서 (카카오톡 앱 로그인 연동)
            openExternal(u);
            return true;
        }
        if (local) return false;
        if ("http".equals(scheme) || "https".equals(scheme)) {
            openExternal(u);
            return true;
        }
        return true;
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            Toast.makeText(this, "브라우저를 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBatteryExemption(boolean userAsked) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (userAsked) Toast.makeText(this, "이미 배터리 최적화에서 제외되어 있습니다 ✅", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void askBatteryOnce() {
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (!sp.getBoolean("battery_asked", false)) {
            sp.edit().putBoolean("battery_asked", true).apply();
            requestBatteryExemption(false);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true);   // 뒤로가기로 앱이 꺼지지 않게 (감시는 계속)
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
