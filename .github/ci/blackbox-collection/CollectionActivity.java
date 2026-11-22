package com.blackbox.stream.collectionpilot;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;

/** The pilot has no loopback listener, external navigation, or WebView-origin storage. */
public final class CollectionActivity extends Activity {
    private static final String ORIGIN = "https://music.blackbox.invalid/";
    private static final int INK = Color.rgb(20, 19, 23);
    private WebView web;
    private LocalPlayer player;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        try {
            OfflineLibrary library = OfflineLibrary.open(this);
            player = new LocalPlayer(this, library);
            web = new WebView(this);
            web.setBackgroundColor(INK);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }

                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    if ("GET".equals(request.getMethod()) && url.startsWith(ORIGIN)) {
                        String file = url.substring(ORIGIN.length());
                        String mime;
                        switch (file) {
                            case "index.html": mime = "text/html"; break;
                            case "music.css": mime = "text/css"; break;
                            case "music.js": mime = "application/javascript"; break;
                            case "kwaku.svg": case "espresso.svg": case "mix.svg": case "icon.svg":
                                mime = "image/svg+xml"; break;
                            default: return blocked();
                        }
                        try {
                            Map<String, String> headers = new HashMap<>();
                            headers.put("Cache-Control", "no-store");
                            headers.put("X-Content-Type-Options", "nosniff");
                            headers.put("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
                            return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers,
                                    getAssets().open("collection/" + file));
                        } catch (IOException missing) { return blocked(); }
                    }
                    return blocked();
                }
            });
            web.addJavascriptInterface(new Bridge(library, player), "OfflineMusic");
            setContentView(web);
            web.loadUrl(ORIGIN + "index.html");
        } catch (IOException | JSONException error) {
            TextView message = new TextView(this);
            message.setBackgroundColor(INK);
            message.setTextColor(Color.WHITE);
            message.setPadding(28, 28, 28, 28);
            message.setText("Your music collection could not open. Restart BlackBox Music to try again.");
            setContentView(message);
        }
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    private final class Bridge {
        private final OfflineLibrary library;
        private final LocalPlayer local;
        Bridge(OfflineLibrary library, LocalPlayer local) { this.library = library; this.local = local; }
        @JavascriptInterface public String library() { return library.snapshot(); }
        @JavascriptInterface public String queue(String action, String value) { return library.command(action, value); }
        @JavascriptInterface public String player() { return local.snapshot(); }
        @JavascriptInterface public void playback(String action, String value) {
            if (action == null || value == null || action.length() > 10 || value.length() > 100) return;
            runOnUiThread(() -> local.command(action, value));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (player != null) player.foreground(true);
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (player != null) player.foreground(false);
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.pilotBack && window.pilotBack()", handled -> {
            if (!"true".equals(handled)) super.onBackPressed();
        });
    }

    @Override protected void onDestroy() {
        if (player != null) player.close();
        if (web != null) {
            web.removeJavascriptInterface("OfflineMusic");
            setContentView(new android.widget.FrameLayout(this));
            web.destroy();
        }
        super.onDestroy();
    }
}
