package com.blackboxstream.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import org.json.JSONArray;

/** Official provider player in a separate WebView. It has no access to the catalogue's Android bridge. */
public final class MusicVideoActivity extends Activity {
    static final String EXTRA_VIDEO = "video";
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout content;
    private WebView web;
    private TextView status;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private String id;
    private boolean loaded, requested, visible;
    private boolean soundCloud;
    private long started;
    private int epoch;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible || !loaded || web == null) return;
            int load = epoch;
            web.evaluateJavascript("window.bbxStatus?.()", value -> {
                if (!visible || !loaded || load != epoch || web == null) return;
                try {
                    JSONArray data = new JSONArray(value);
                    int error = data.optInt(0), state = data.optInt(1, -1);
                    if (error != 0) status.setText(error == 101 || error == 150 || error == 100
                            ? "This recording isn't available in the embedded player. Go back to link an authorized source."
                            : "The online provider couldn't play this recording. Try reloading or link an authorized file.");
                    else if (state == 1 || state == 2 || state == 0) status.setText(provider() + " · online only · no offline copy");
                    else if (data.optBoolean(2)) status.setText("Tap Play in the " + provider() + " player to start this recording.");
                    else if (SystemClock.uptimeMillis() - started > 22000)
                        status.setText("The online player is taking too long. Check your connection or link another source for this recording.");
                } catch (org.json.JSONException pending) {
                    if (SystemClock.uptimeMillis() - started > 22000) status.setText("The online player couldn't load. Try again.");
                }
                main.postDelayed(this, 750);
            });
        }
    };

    static boolean validId(String id) { return id != null && id.matches("(?:[A-Za-z0-9_-]{11}|sc:[1-9][0-9]{0,11})"); }

    private String provider() { return soundCloud ? "SoundCloud" : "YouTube"; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra(EXTRA_VIDEO);
        if (!validId(id) || V90Api.expired()) { finish(); return; }
        soundCloud = id.startsWith("sc:");
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        Ui.fitWindow(this, root);
        setContentView(root);
        content = Ui.column(this);
        Ui.pad(content, 16, 16);
        scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        content.addView(Ui.action(this, "← Music", false, v -> finish()));
        Ui.gap(content, 16);
        content.addView(Ui.text(this, "BlackBox Music", 25, Ui.INK, Ui.DISPLAY));
        content.addView(Ui.text(this, "Official " + provider() + " player · online listening only", 13, Ui.MUTED, null));
        Ui.gap(content, 12);
        status = Ui.text(this, "Opening the selected recording…", 14, Ui.MUTED, null);
        try {
            web = new WebView(this);
            web.setBackgroundColor(Color.BLACK);
            web.setKeepScreenOn(true);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            s.setSupportMultipleWindows(false);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            // This activity only opens after an explicit Play action in the catalogue.
            s.setMediaPlaybackRequiresUserGesture(false);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    Uri url = request.getUrl();
                    return request.isForMainFrame() || !"https".equals(url.getScheme()) || "blackbox.invalid".equals(url.getHost());
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    Uri url = request.getUrl();
                    if (!"blackbox.invalid".equals(url.getHost())) return null;
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame() && loaded) status.setText("The online player couldn't load. Check your connection or try again.");
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                    if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                    fullscreen = view; fullscreenCallback = callback;
                    scroll.setVisibility(View.GONE);
                    root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                }
                @Override public void onHideCustomView() { hideFullscreen(); }
            });
            content.addView(web, new LinearLayout.LayoutParams(-1, Ui.dp(this, 230)));
        } catch (RuntimeException missing) {
            status.setText("Android System WebView is unavailable. Update it to use the online player.");
        }
        Ui.gap(content, 12);
        content.addView(status);
        content.addView(Ui.action(this, "Reload player", false, v -> load()));
    }

    private void load() {
        if (web == null) return;
        final String origin = "https://" + getPackageName();
        // The package-name origin provides the required Referer. No remote document runs with Android privileges.
        web.loadDataWithBaseURL(origin + "/music/", soundCloud ? soundCloudPage(id.substring(3)) : youtubePage(origin, id), "text/html", "UTF-8", null);
        loaded = true; epoch++; started = SystemClock.uptimeMillis();
        status.setText("Opening the selected recording…");
        main.removeCallbacks(poll);
        main.postDelayed(poll, 750);
    }

    private static String frameHeader(String host) {
        return "<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<meta name=referrer content=strict-origin-when-cross-origin>"
                + "<meta http-equiv=Content-Security-Policy content=\"default-src 'none'; script-src 'unsafe-inline' " + host
                + "; style-src 'unsafe-inline'; frame-src " + host + "; base-uri 'none'; form-action 'none'\">"
                + "<style>html,body,iframe{width:100%;height:100%;margin:0;border:0;background:#000;display:block}</style>";
    }

    private static String youtubePage(String origin, String id) {
        return frameHeader("https://www.youtube.com")
                + "<iframe id='recording' title='Official music video player' allow='autoplay; encrypted-media; fullscreen' allowfullscreen "
                + "src='https://www.youtube.com/embed/" + id + "?enablejsapi=1&amp;playsinline=1&amp;rel=0&amp;origin=" + origin + "'></iframe>"
                + "<script>let player, blocked=false; window.bbxMusicPlayerError=0; window.bbxMusicPlayerReady=false;"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('recording',{events:{onReady(e){window.bbxMusicPlayerReady=true;"
                + "const r=document.getElementById('recording').getBoundingClientRect();"
                + "if(!document.hidden&&r.width>=200&&r.height>=200)e.target.playVideo()},"
                + "onError(e){window.bbxMusicPlayerError=e.data},onAutoplayBlocked(){blocked=true}}})};"
                + "function bbxPlayerState(){return {ready:bbxMusicPlayerReady,error:bbxMusicPlayerError,state:player?.getPlayerState?.(),"
                + "seconds:player?.getCurrentTime?.(),duration:player?.getDuration?.(),url:player?.getVideoUrl?.()}};"
                + "function bbxStatus(){return [bbxMusicPlayerError,player?.getPlayerState?.(),blocked]};"
                + "function bbxSeek(s){player?.seekTo(s,true)};function bbxPause(){player?.pauseVideo?.()};</script>"
                + "<script src='https://www.youtube.com/iframe_api'></script></html>";
    }

    private static String soundCloudPage(String id) {
        return frameHeader("https://w.soundcloud.com")
                + "<iframe id='recording' title='Official SoundCloud audio player' allow=autoplay "
                + "src='https://w.soundcloud.com/player/?url=https%3A%2F%2Fapi.soundcloud.com%2Ftracks%2F" + id
                + "&amp;auto_play=false&amp;visual=true&amp;show_reposts=false'></iframe>"
                + "<script src='https://w.soundcloud.com/player/api.js'></script>"
                + "<script>const recording={ready:false,error:0,state:-1,seconds:0,duration:0,id:''};"
                + "const widget=window.SC?.Widget('recording'),ev=window.SC?.Widget.Events;"
                + "if(widget){widget.bind(ev.READY,()=>{recording.ready=true;"
                + "widget.getCurrentSound(sound=>{recording.id=String(sound?.id||sound?.urn?.split(':').pop()||'');});"
                + "widget.getDuration(ms=>recording.duration=ms/1000);if(!document.hidden)widget.play();});"
                + "widget.bind(ev.PLAY,()=>recording.state=1);widget.bind(ev.PAUSE,()=>recording.state=2);"
                + "widget.bind(ev.FINISH,()=>recording.state=0);widget.bind(ev.ERROR,()=>recording.error=1);"
                + "widget.bind(ev.PLAY_PROGRESS,e=>{recording.seconds=e.currentPosition/1000;});}"
                + "function bbxPlayerState(){return recording};function bbxStatus(){return [recording.error,recording.state,false]};"
                + "function bbxSeek(s){widget?.seekTo(s*1000)};function bbxPause(){widget?.pause()};</script></html>";
    }

    @Override protected void onResume() {
        super.onResume();
        if (V90Api.expired()) { finish(); return; }
        visible = true;
        if (web != null) {
            web.onResume();
            if (!requested) { requested = true; load(); }
            else if (loaded) { main.removeCallbacks(poll); main.postDelayed(poll, 750); }
        }
    }

    @Override protected void onPause() {
        visible = false; epoch++; main.removeCallbacks(poll);
        if (web != null) { web.evaluateJavascript("window.bbxPause?.()", null); web.onPause(); }
        super.onPause();
    }

    @Override protected void onStop() {
        // A provider embed is not a background-audio source. Stop it when this view is left.
        if (web != null && loaded) {
            hideFullscreen(); loaded = false; web.stopLoading(); web.loadUrl("about:blank");
            status.setText("Playback paused while you were away. Reload to listen again.");
        }
        super.onStop();
    }

    private void hideFullscreen() {
        if (fullscreen == null) return;
        root.removeView(fullscreen); fullscreen = null;
        scroll.setVisibility(View.VISIBLE);
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    @Override public void onBackPressed() {
        if (fullscreen != null) hideFullscreen(); else finish();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(poll);
        hideFullscreen();
        if (web != null) {
            web.stopLoading();
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy(); web = null;
        }
        super.onDestroy();
    }
}
