package com.blackboxstream.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

final class Ui {
    static final int BG = 0xff0c1015;
    static final int CARD = 0xff151c25;
    static final int BORDER = 0xff2b3645;
    static final int INK = 0xfff4f7fa;
    static final int MUTED = 0xffa8b6c7;
    static final int MINT = 0xff76f2ce;
    static final int MINT_DARK = 0xff15352f;
    static final int RED = 0xffffb4ac;
    static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface DISPLAY = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);

    private Ui() { }

    static int dp(Context c, float n) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n, c.getResources().getDisplayMetrics()));
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static void pad(View v, int x, int y) { v.setPadding(dp(v.getContext(), x), dp(v.getContext(), y), dp(v.getContext(), x), dp(v.getContext(), y)); }
    static void gap(LinearLayout root, int height) { root.addView(new View(root.getContext()), new LinearLayout.LayoutParams(1, dp(root.getContext(), height))); }
    static void space(LinearLayout root, int width) { root.addView(new View(root.getContext()), new LinearLayout.LayoutParams(dp(root.getContext(), width), 1)); }
    static void flex(LinearLayout root) { root.addView(new View(root.getContext()), new LinearLayout.LayoutParams(0, 0, 1)); }

    static GradientDrawable shape(Context c, int color, int radius, int line) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        if (line != 0) d.setStroke(dp(c, 1), line);
        return d;
    }

    static void card(View v, int color, int radius, int border) { v.setBackground(shape(v.getContext(), color, radius, border)); }

    static void click(View v, int fill, int radius, int border, View.OnClickListener action) {
        Context c = v.getContext();
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2976f2ce), shape(c, fill, radius, border), null));
        v.setFocusable(true);
        v.setOnClickListener(action);
    }

    static TextView text(Context c, String label, int size, int color, Typeface font) {
        TextView view = new TextView(c);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if (font != null) view.setTypeface(font);
        view.setIncludeFontPadding(false);
        return view;
    }

    static TextView kicker(Context c, String label) {
        TextView t = text(c, label, 10, MINT, MONO);
        t.setLetterSpacing(.12f);
        return t;
    }

    static TextView action(Context c, String label, boolean primary, View.OnClickListener click) {
        TextView b = text(c, label, 14, primary ? BG : INK, BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 48));
        pad(b, 12, 8);
        click(b, primary ? MINT : CARD, 13, primary ? 0 : BORDER, click);
        return b;
    }

    static void singleLine(TextView t) { t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END); }

    static String clock(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return String.format(Locale.ROOT, "%.0f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", n / (1024.0 * 1024 * 1024));
    }

    static void fitWindow(Activity activity, View root) {
        Window w = activity.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            // PhoneWindow may not have installed its decor yet during Activity.onCreate().
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.view.WindowInsetsController bars = v.getWindowInsetsController();
                if (bars != null) bars.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                android.graphics.Insets window = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(window.left, window.top, window.right, Math.max(window.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        }
    }

    /** Small, resolution-independent artwork; no remote poster requests on launch. */
    static final class OrbitArt extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean sound;
        private Shader background, glow;

        OrbitArt(Context c, boolean sound) {
            super(c);
            this.sound = sound;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            if (w == 0 || h == 0) return;
            background = new LinearGradient(0, 0, w, h, sound ? 0xff182035 : 0xff152b32,
                    sound ? 0xff252645 : 0xff163936, Shader.TileMode.CLAMP);
            glow = new RadialGradient(w * .80f, h * .47f, Math.max(w, h) * .57f,
                    sound ? 0x66806efd : 0x6644e5b1, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight(), x = w * .80f, y = h * .49f;
            p.setStyle(Paint.Style.FILL);
            p.setShader(background); c.drawRect(0, 0, w, h, p);
            p.setShader(glow); c.drawRect(0, 0, w, h, p); p.setShader(null);
            p.setColor(sound ? 0x339b9ce7 : 0x3965bcaa);
            p.setStrokeWidth(1);
            float step = dp(getContext(), 24);
            for (float i = step; i < w; i += step) c.drawLine(i, 0, i, h, p);
            for (float i = step; i < h; i += step) c.drawLine(0, i, w, i, p);
            float unit = Math.min(h, w) / 7f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 1));
            for (int i = 1; i <= 5; i++) {
                p.setColor(sound ? 0xffb9aaff : 0xff89f5d0);
                p.setAlpha(105 - i * 12);
                c.drawCircle(x, y, unit * (i * .60f + .75f), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(sound ? 0xffe1d9ff : MINT);
            if (sound) {
                for (int i = -5; i <= 5; i++) {
                    float v = (1f - Math.abs(i) / 6f) * unit * (.66f + (i % 2 == 0 ? .23f : -.15f));
                    float cx = x + i * unit / 3.4f;
                    c.drawRoundRect(cx - unit / 13, y - v, cx + unit / 13, y + v, unit / 13, unit / 13, p);
                }
            } else {
                path.reset(); path.moveTo(x - unit * .35f, y - unit * .60f);
                path.lineTo(x + unit * .70f, y); path.lineTo(x - unit * .35f, y + unit * .60f); path.close();
                c.drawPath(path, p);
            }
        }
    }
}
