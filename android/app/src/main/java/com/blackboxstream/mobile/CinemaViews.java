package com.blackboxstream.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

final class CinemaViews {
    private CinemaViews() { }

    static ImageView art(Context context, Cinema.Film film) {
        ImageView image = new ImageView(context);
        image.setImageResource(film.art);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    static FrameLayout still(Context c, Cinema.Film film, String tag, int height) {
        FrameLayout still = new FrameLayout(c);
        Ui.card(still, Ui.CARD, 14, 0);
        still.setClipToOutline(true);
        still.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(c, height)));
        still.addView(art(c, film), new FrameLayout.LayoutParams(-1, -1));
        if (tag != null) {
            TextView pill = Ui.kicker(c, tag);
            pill.setTextColor(Ui.INK);
            Ui.pad(pill, 10, 6);
            Ui.card(pill, 0xdd0a1118, 8, 0x555baba1);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            params.setMargins(Ui.dp(c, 14), Ui.dp(c, 12), 0, 0);
            still.addView(pill, params);
        }
        return still;
    }

    static void title(Activity activity, LinearLayout pane, Cinema.Film film, boolean featured) {
        pane.addView(still(activity, film, featured ? "FEATURED  /  OPEN FILM" : "BLENDER OPEN MOVIE", featured ? 180 : 218));
        Ui.gap(pane, featured ? 13 : 18);
        pane.addView(Ui.text(activity, film.title, 40, Ui.INK, Ui.DISPLAY));
        Ui.gap(pane, 6);
        pane.addView(Ui.kicker(activity, film.byline()));
        Ui.gap(pane, 9);
        pane.addView(Ui.text(activity, film.story, 14, Ui.MUTED, null));
    }

    static void shelf(Activity c, LinearLayout pane, List<Cinema.Film> films) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout rail = Ui.row(c);
        rail.setGravity(Gravity.TOP);
        for (Cinema.Film film : films) {
            LinearLayout tile = Ui.column(c);
            Ui.pad(tile, 7, 7);
            Ui.click(tile, Ui.CARD, 15, Ui.BORDER, v -> FilmActivity.show(c, film));
            tile.setContentDescription(film.title + ", " + film.byline() + ". Watch or choose a source");
            tile.addView(still(c, film, null, 107));
            Ui.gap(tile, 9);
            TextView name = Ui.text(c, film.title, 15, Ui.INK, Ui.BOLD);
            Ui.singleLine(name);
            tile.addView(name);
            Ui.gap(tile, 5);
            tile.addView(Ui.text(c, film.genre + "  ·  " + film.duration, 11, Ui.MUTED, null));
            Ui.gap(tile, 6);
            rail.addView(tile, new LinearLayout.LayoutParams(Ui.dp(c, 155), -2));
            Ui.space(rail, 9);
        }
        scroll.addView(rail);
        pane.addView(scroll);
    }

    static void browse(Activity c, Catalogue.Filter filter, String query) {
        c.startActivity(new Intent(c, DiscoverActivity.class).putExtra("catalogue_filter", filter.name()).putExtra("catalogue_query", query));
    }
}
