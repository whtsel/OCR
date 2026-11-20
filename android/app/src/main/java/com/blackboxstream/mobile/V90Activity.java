package com.blackboxstream.mobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** v90's shipped interface, in a private, non-network WebView origin. No server is exposed. */
public final class V90Activity extends Activity {
    private static final String HOST = "blackbox.invalid", HOME = "https://" + HOST + "/";
    private static final String POLICY = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; "
            + "frame-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; require-trusted-types-for 'script'";
    private static final Set<String> ART_HOSTS = new HashSet<>(Arrays.asList("image.tmdb.org", "cdn.myanimelist.net",
            "s4.anilist.co", "i.ytimg.com", "img.youtube.com", "cdn-images.dzcdn.net", "is1-ssl.mzstatic.com"));
    private static final Set<String> MOBILE_ASSETS = new HashSet<>(Arrays.asList("android-api.js", "android-port.js", "android-catalogue.js", "android-music.js", "android-v90.css"));
    private static final int PICK_MUSIC = 94;
    private int musicFileRequest;
    private String musicFileTitle;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor work = new ThreadPoolExecutor(6, 6, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(80));
    private volatile boolean closed;
    private boolean shownError, resumed;
    private WebView view;
    private V90Api api;
    private LinearLayout root;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = Ui.column(this); root.setBackgroundColor(0xff101017);
        Ui.fitWindow(this, root); setContentView(root);
        api = new V90Api(this);
        try {
            view = new WebView(this);
            view.setBackgroundColor(0xff101017);
            view.setWebViewClient(new ShellClient());
            WebSettings s = view.getSettings();
            s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
            s.setAllowFileAccess(false); s.setAllowContentAccess(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setMediaPlaybackRequiresUserGesture(true);
            view.addJavascriptInterface(new Bridge(), "BlackBoxAndroid");
            root.addView(view, new LinearLayout.LayoutParams(-1, -1));
            view.loadUrl(HOME);
        } catch (RuntimeException missing) { showWebViewError(); }
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (view != null) { view.onResume(); view.evaluateJavascript("window.bbxAndroidResume?.()", null); }
    }
    @Override protected void onPause() { resumed = false; if (view != null) view.onPause(); super.onPause(); }

    @Override public void onBackPressed() {
        if (view == null || shownError) { super.onBackPressed(); return; }
        view.evaluateJavascript("window.bbxAndroidBack ? window.bbxAndroidBack() : false", handled -> {
            if (!"true".equals(handled) && !isFinishing()) finish();
        });
    }

    @Override protected void onDestroy() {
        closed = true;
        if (api != null) api.close();
        work.shutdownNow();
        if (view != null) {
            view.removeJavascriptInterface("BlackBoxAndroid");
            view.stopLoading(); root.removeView(view); view.destroy(); view = null;
        }
        super.onDestroy();
    }

    private void showWebViewError() {
        if (closed || shownError) return;
        shownError = true;
        if (view != null) view.setVisibility(android.view.View.GONE);
        LinearLayout pane = Ui.column(this); Ui.pad(pane, 18, 24); root.addView(pane);
        pane.addView(Ui.text(this, "BlackBox couldn't open its interface", 24, Ui.INK, Ui.DISPLAY)); Ui.gap(pane, 10);
        pane.addView(Ui.text(this, "Enable or update Android System WebView, then reopen BlackBox. Your saved media is still on this phone.", 15, Ui.MUTED, null));
        if (!V90Api.expired()) { Ui.gap(pane, 12); pane.addView(Ui.action(this, "Open phone library", false, v -> launch(MainActivity.class))); }
    }

    private final class Bridge {
        @JavascriptInterface public void request(int id, String method, String path, String body) {
            if (closed || id <= 0) return;
            try { work.execute(() -> reply(id, api.call(method, path, body))); }
            catch (RejectedExecutionException busy) { reply(id, V90Api.fail(503, "BlackBox is handling too many requests. Try again in a moment.")); }
        }
        @JavascriptInterface public void pickMusic(int request, String title) {
            if (closed || request <= 0 || title == null || title.length() > 512) return;
            main.post(() -> {
                if (!active() || musicFileRequest != 0) {
                    musicFileReply(request, "", "Android's file picker is busy. Try again.");
                    return;
                }
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                musicFileRequest = request;
                musicFileTitle = MediaEntry.cleanTitle(title);
                try { startActivityForResult(picker, PICK_MUSIC); }
                catch (android.content.ActivityNotFoundException unavailable) {
                    musicFileRequest = 0;
                    musicFileTitle = null;
                    musicFileReply(request, "", "Android's file picker isn't available.");
                }
            });
        }
        @JavascriptInterface public void open(String action, String id) {
            if (closed || V90Api.expired()) return;
            if ("play".equals(action) && id != null && id.matches("[a-f0-9]{64}")) {
                try {
                    work.execute(() -> {
                        MediaEntry entry = LibraryStore.get(V90Activity.this).get(id);
                        if (entry == null) { main.post(() -> toast("This media is no longer in your Library.")); return; }
                        try { if (entry.torrent()) api.ensureQueued(entry); }
                        catch (IOException | RuntimeException failed) {
                            main.post(() -> toast(V90Api.safeFailure(failed, "Couldn't queue this torrent."))); return;
                        }
                        main.post(() -> {
                            if (!active()) return;
                            if (!TorrentActivity.openSource(V90Activity.this, entry)) startActivity(new Intent(V90Activity.this, PlayerActivity.class).putExtra(PlaybackService.EXTRA_ID, id));
                        });
                    });
                } catch (RejectedExecutionException busy) { main.post(() -> toast("BlackBox is busy. Try again.")); }
                return;
            }
            main.post(() -> {
                if (!active()) return;
                if ("library".equals(action)) launch(MainActivity.class);
                else if ("torrent".equals(action)) launch(TorrentActivity.class);
                else if ("share".equals(action)) launch(LanShareActivity.class);
                else if ("rooms".equals(action)) launch(CommunityActivity.class);
                else if ("music-video".equals(action) && MusicVideoActivity.validId(id))
                    startActivity(new Intent(V90Activity.this, MusicVideoActivity.class).putExtra(MusicVideoActivity.EXTRA_VIDEO, id));
                else if ("browser".equals(action)) openProviderPage(id);
            });
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MUSIC || musicFileRequest == 0) return;
        int token = musicFileRequest;
        String title = musicFileTitle;
        musicFileRequest = 0;
        musicFileTitle = null;
        if (closed || V90Api.expired()) return;
        Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) { musicFileReply(token, "", ""); return; }
        if (!"content".equals(uri.getScheme())) { musicFileReply(token, "", "Choose an audio file from your phone."); return; }
        try {
            work.execute(() -> {
                try {
                    String type = getContentResolver().getType(uri);
                    if (type == null || !type.startsWith("audio/")) {
                        musicFileReply(token, "", "Choose an audio file such as MP3, AAC or FLAC."); return;
                    }
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    MediaEntry entry = new MediaEntry(title, MediaEntry.Kind.MUSIC, uri.toString());
                    LibraryStore.get(this).save(entry, true);
                    musicFileReply(token, entry.id, "");
                } catch (RuntimeException unavailable) {
                    musicFileReply(token, "", "Android couldn't keep access to this file. Save a copy on your phone and try again.");
                }
            });
        } catch (RejectedExecutionException busy) { musicFileReply(token, "", "BlackBox is busy. Try again."); }
    }

    private void musicFileReply(int request, String id, String error) {
        main.post(() -> {
            if (!closed && view != null) view.evaluateJavascript("window.bbxAndroidMusicFile?.(" + request + "," +
                    JSONObject.quote(id) + "," + JSONObject.quote(error) + ")", null);
        });
    }

    private boolean active() { return resumed && !isFinishing() && !isDestroyed() && !closed && !V90Api.expired(); }
    private void launch(Class<? extends Activity> screen) {
        Intent request = new Intent(this, screen);
        if (screen == MainActivity.class) request.putExtra("device_library", true);
        startActivity(request);
    }
    private void openProviderPage(String url) {
        // An explicit click leaves the app. No site is loaded inside the WebView with the Android bridge.
        if (url == null || url.length() > 1800) return;
        try {
            Uri uri = Uri.parse(MediaSources.https(url));
            String host = uri.getHost(), path = uri.getPath();
            if (uri.getPort() != -1 || uri.getUserInfo() != null || path == null) return;
            boolean allowed = "www.youtube.com".equals(host) && ("/results".equals(path) || "/watch".equals(path))
                    || "www.themoviedb.org".equals(host) && path.matches("/(?:[a-z]{2}(?:-[A-Z]{2})?/)?(?:movie|tv)/[A-Za-z0-9_-]+(?:/watch)?/?")
                    || "musicbrainz.org".equals(host) && path.matches("/recording/[a-f0-9-]{36}")
                    || "studio.blender.org".equals(host) && path.matches("/projects/[a-z0-9-]+/?")
                    || "incompetech.com".equals(host) && "/music/royalty-free/index.html".equals(path)
                        && V90Api.param(uri, "isrc", "").matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
                    || "commons.wikimedia.org".equals(host) && OpenAnime.FILMS.stream()
                        .anyMatch(film -> film.attribution().equals(uri.toString()));
            if (allowed) startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (android.content.ActivityNotFoundException unavailable) { toast("No app is available to open this provider page."); }
        catch (IllegalArgumentException invalid) { toast("This provider link is not supported."); }
    }
    private void toast(String text) { if (!closed) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private void reply(int id, V90Api.Reply result) {
        // Quote all provider strings as a JSON string. No network response is evaluated as code.
        String json = JSONObject.quote(result.value.toString()).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        main.post(() -> {
            if (!closed && view != null) view.evaluateJavascript("window.bbxAndroidReply && window.bbxAndroidReply(" + id + "," + result.status + "," + json + ")", null);
        });
    }

    private final class ShellClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
            // The only navigable page is bundled. In particular, external iframes cannot inherit the bridge.
            return !isLocal(request.getUrl()) || !request.isForMainFrame() || !"/".equals(request.getUrl().getPath());
        }
        @Override public void onReceivedError(WebView web, WebResourceRequest req, WebResourceError error) {
            if (req.isForMainFrame()) showWebViewError();
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest req) {
            if (!"GET".equals(req.getMethod()) || !isLocal(req.getUrl())) return response(403, "Forbidden", "text/plain", new byte[0]);
            String path = req.getUrl().getEncodedPath();
            try {
                if ("/".equals(path)) return response(200, "OK", "text/html", index());
                // Read-only and same-origin. A missing prepared recording never invokes the downloader.
                if (path != null && path.matches("/prepared-audio/[a-f0-9]{64}\\.m4a")) {
                    if (req.isForMainFrame()) return response(403, "Forbidden", "text/plain", new byte[0]);
                    String id = path.substring("/prepared-audio/".length(), path.length() - 4);
                    File prepared = api.preparedAudio(id);
                    if (prepared != null) {
                        Map<String, String> responseHeaders = new HashMap<>(headers());
                        responseHeaders.put("Content-Length", Long.toString(prepared.length()));
                        return new WebResourceResponse("audio/mp4", null, 200, "OK", responseHeaders, new FileInputStream(prepared));
                    }
                }
                if (path != null && path.matches("/api/img/(w[0-9]{2,4}|original)/open/[a-z_]+\\.jpg")) {
                    String slug = path.substring(path.lastIndexOf('/') + 1, path.length() - 4).replace('_', '-');
                    Cinema.Film f = Cinema.fromSlug(slug);
                    if (f != null) return response(200, "OK", "image/jpeg", TorrentMeta.slurp(getResources().openRawResource(f.art), 512 * 1024));
                }
                if (path != null && path.matches("/api/img/(w[0-9]{2,4}|original)/anime/[a-z-]+\\.jpg")) {
                    OpenAnime film = OpenAnime.named(path.substring(path.lastIndexOf('/') + 1, path.length() - 4));
                    if (film != null) return artwork(URI.create(film.artwork()), true);
                }
                if (path != null && path.matches("/api/img/(w[0-9]{2,4}|original)/[a-zA-Z0-9_-]{1,90}\\.(jpg|jpeg|png|webp)"))
                    return artwork(URI.create("https://image.tmdb.org/t/p/" + path.substring(9)));
                if ("/api/cover".equals(path)) {
                    String source = V90Api.param(req.getUrl(), "url", "");
                    if (source.length() <= 2048) return artwork(URI.create(MediaSources.https(source)));
                }
                // v90 loads purify.min.js and hls.min.js before its own scripts.
                if (path != null && path.matches("/[a-zA-Z0-9_-]+(?:\\.min)?\\.(js|css|json|txt)")) {
                    String name = path.substring(1);
                    return new WebResourceResponse(mime(name), "UTF-8", 200, "OK", headers(),
                            getAssets().open((MOBILE_ASSETS.contains(name) ? "v90-mobile/" : "v90/") + name));
                }
            } catch (IOException | RuntimeException ignored) { /* No real network navigation or filesystem fallback. */ }
            return response(404, "Not Found", "text/plain", new byte[0]);
        }
    }

    private static boolean isLocal(Uri uri) {
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost()) && uri.getPort() == -1 && uri.getUserInfo() == null;
    }

    private byte[] index() throws IOException {
        String html = new String(TorrentMeta.slurp(getAssets().open("v90/index.html"), 256 * 1024), StandardCharsets.UTF_8);
        // Keep the desktop sources byte-for-byte on disk. Change only how the mobile shell loads them.
        html = html.replace("</head>", "<link rel=\"stylesheet\" href=\"/android-v90.css\" />\n</head>")
                .replace("<script src=\"/app.js", "<script src=\"/android-api.js\"></script>\n<script src=\"/app.js")
                .replaceAll("<script src=\"/(?:fast-source-v74|play-anyway-v74|local-share-v70|ads-v88|source-votes-v74|vote-gate-v75|votes-sync-v74|privacy-panel-v86)\\.js[^\"]*\"></script>", "")
                .replace("</body>", "<script src=\"/android-port.js\"></script>\n<script src=\"/android-catalogue.js\"></script>\n<script src=\"/android-music.js\"></script>\n</body>");
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private static WebResourceResponse artwork(URI uri) throws IOException { return artwork(uri, false); }

    private static WebResourceResponse artwork(URI uri, boolean pinned) throws IOException {
        if (!"https".equals(uri.getScheme()) || !pinned && !ART_HOSTS.contains(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null)
            return response(403, "Forbidden", "text/plain", new byte[0]);
        HttpsURLConnection req = (HttpsURLConnection) uri.toURL().openConnection();
        req.setConnectTimeout(4500); req.setReadTimeout(6000); req.setInstanceFollowRedirects(false);
        req.setRequestProperty("Accept", "image/webp,image/png,image/jpeg");
        req.setRequestProperty("User-Agent", MediaSources.USER_AGENT);
        try {
            int status = req.getResponseCode(); String mime = req.getContentType();
            if (status == 200 && ("image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime)) && req.getContentLengthLong() <= 2 * 1024 * 1024)
                return response(200, "OK", mime, TorrentMeta.slurp(req.getInputStream(), 2 * 1024 * 1024));
            return response(404, "Not Found", "text/plain", new byte[0]);
        } finally { req.disconnect(); }
    }

    private static String mime(String name) {
        return name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".json") ? "application/json" : "text/plain";
    }
    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", POLICY); headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Cache-Control", "no-store"); headers.put("Referrer-Policy", "no-referrer");
        return Collections.unmodifiableMap(headers);
    }
    private static WebResourceResponse response(int status, String reason, String mime, byte[] bytes) {
        return new WebResourceResponse(mime, mime.startsWith("image/") ? null : "UTF-8", status, reason, headers(), new ByteArrayInputStream(bytes));
    }
}
