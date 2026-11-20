package com.blackbox.stream.helperstream;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Arrays;

/** The pinned p11 runtime stays untouched. This Activity only starts its private stream adapter. */
public final class HelperStreamActivity extends Activity {
    private static final String TAG = "BlackBoxHelperStream";
    private static final Pattern READY = Pattern.compile("BBX_READY ([0-9]{1,5}/[A-Za-z0-9_-]{24})");
    private final Object lifecycle = new Object();
    private volatile boolean closed;
    private Process process;
    private WebView web;
    private TextView setup;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setup = new TextView(this);
        setup.setPadding(32, 32, 32, 32);
        setup.setText("Starting the bundled helper…");
        setContentView(setup);
        new Thread(this::start, "helper-stream-start").start();
        setup.postDelayed(() -> {
            if (!closed && web == null) {
                synchronized (lifecycle) { if (process != null) process.destroy(); }
                showFailure();
            }
        }, 90000);
    }

    private void start() {
        try {
            Context app = getApplicationContext();
            Class<?> runtime = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
            runtime.getMethod("init", Context.class).invoke(runtime.getMethod("getInstance").invoke(null), app);
            File root = new File(getNoBackupFilesDir(), "youtubedl-android");
            File python = new File(root, "packages/python/usr");
            File pin = new File(root, "yt-dlp/yt-dlp");
            File bin = new File(getApplicationInfo().nativeLibraryDir);
            File scratch = new File(getCacheDir(), "helper-stream");
            if (!scratch.isDirectory() && !scratch.mkdirs()) throw new IOException("Cache directory unavailable");
            for (String file : new String[] {"stream.py", "index.html", "helper-phone.js", "helper-phone.css"}) copy(file, scratch);
            ArrayList<String> command = new ArrayList<>(Arrays.asList(new File(bin, "libpython.so").getAbsolutePath(), "-u",
                    new File(scratch, "stream.py").getAbsolutePath(), pin.getAbsolutePath(), scratch.getAbsolutePath(),
                    scratch.getAbsolutePath(), "--quickjs", new File(bin, "libqjs.so").getAbsolutePath()));
            if (Arrays.asList(getAssets().list("web")).contains("fixture-orbit.m4a")) {
                copy("fixture-orbit.m4a", scratch);
                command.addAll(Arrays.asList("--fixture", new File(scratch, "fixture-orbit.m4a").getAbsolutePath()));
            }
            ProcessBuilder builder = new ProcessBuilder(command).directory(scratch).redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.put("LD_LIBRARY_PATH", python + "/lib");
            env.put("PYTHONHOME", python.getAbsolutePath());
            env.put("SSL_CERT_FILE", python + "/etc/tls/cert.pem");
            env.put("PYTHONUTF8", "1");
            env.put("PYTHONDONTWRITEBYTECODE", "1");
            env.put("HOME", scratch.getAbsolutePath());
            env.put("TMPDIR", scratch.getAbsolutePath());
            env.put("PATH", "/system/bin:" + bin.getAbsolutePath());
            Process child;
            synchronized (lifecycle) {
                if (closed) return;
                child = process = builder.start();
            }
            try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = output.readLine()) != null;) {
                    Matcher ready = READY.matcher(line);
                    if (ready.matches()) {
                        String local = "http://127.0.0.1:" + ready.group(1) + "/";
                        Log.i(TAG, "Pinned runtime and loopback ready");
                        runOnUiThread(() -> showPlayer(local));
                    } else if (line.equals("BBX_PIN_MISMATCH") || line.equals("BBX_LOOPBACK_REQUEST_FAILED")) {
                        Log.w(TAG, line);
                    }
                }
            }
            showFailure();
        } catch (Exception | LinkageError failed) {
            Log.w(TAG, "Helper start failed: " + failed.getClass().getSimpleName());
            showFailure();
        }
    }

    private void copy(String name, File directory) throws IOException {
        try (InputStream in = getAssets().open("web/" + name);
             FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            byte[] bytes = new byte[8192];
            for (int count; (count = in.read(bytes)) != -1;) out.write(bytes, 0, count);
        }
    }

    private void showPlayer(String root) {
        if (closed || web != null) return;
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); // Only the chosen result triggers play.
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !root.equals(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().startsWith(root)) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
                if (request.isForMainFrame()) showFailure();
            }
        });
        setContentView(web);
        web.loadUrl(root);
    }

    private void showFailure() {
        runOnUiThread(() -> {
            if (closed) return;
            setup.setText("The bundled helper could not start. Close and reopen the tester.");
            setContentView(setup);
        });
    }

    @Override public void onDestroy() {
        synchronized (lifecycle) {
            closed = true;
            if (process != null) process.destroy();
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
