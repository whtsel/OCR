package com.blackboxstream.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;

/** Native diagnostic screen. No WebView, bridge, shared files, or caller-controlled inputs. */
public final class MusicSaveCheckActivity extends Activity {
    private static final int BG = 0xff131219, PANEL = 0xff211e2b, TEXT = 0xfff6f2ff, MUTED = 0xffc6bfd4;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EnumMap<SaveCheck.Arm, TextView> results = new EnumMap<>(SaveCheck.Arm.class);
    private final EnumMap<SaveCheck.Arm, Button> saves = new EnumMap<>(SaveCheck.Arm.class), plays = new EnumMap<>(SaveCheck.Arm.class);
    private SaveCheck check;
    private TextView network, advice, playerClock;
    private LinearLayout playerPanel;
    private Button stop, seek;
    private MediaPlayer player;
    private SaveCheck.Arm playingArm;
    private boolean prepared, offlineEligible, seeking, marked;
    private int seekReached = -1;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); main.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        check = SaveCheck.open(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        body.setBackgroundColor(BG);
        scroll.addView(body);
        setContentView(scroll);

        text(body, "BLACKBOX MUSIC  /  SAVE CHECK", 12, 0xffc5aaf8, true);
        text(body, "Espresso", 29, TEXT, true);
        text(body, "Sabrina Carpenter · Short n’ Sweet · 2:55", 15, MUTED, false);
        text(body, SaveCheckBuild.FIXTURES ? "Synthetic audio only · no provider requests"
                : "One recording · two Android save paths", 13, MUTED, false);
        network = text(body, "", 14, TEXT, true);
        network.setId(R.id.connection);
        advice = text(body, "", 14, MUTED, false);

        card(body, SaveCheck.Arm.PILOT, "Archived p11 whole-file helper", R.id.pilot_save, R.id.pilot_result, R.id.pilot_play);
        card(body, SaveCheck.Arm.FULL, "v90 Offline 1.5.1 whole-file runner", R.id.full_save, R.id.full_result, R.id.full_play);
        stop = button(body, "Stop current test", R.id.stop_transfer, check::stop);

        playerPanel = panel(body);
        playerClock = text(playerPanel, "", 15, TEXT, true);
        playerClock.setId(R.id.player_clock);
        seek = button(playerPanel, "Seek past halfway", R.id.offline_seek, this::seek);
        button(playerPanel, "Close player", View.NO_ID, () -> { closePlayer(); render(); });
        playerPanel.setVisibility(View.GONE);
        button(body, "Copy results", R.id.copy_results, this::copy);
        text(body, "After saving, force-stop this tester. Turn on airplane mode and turn Wi-Fi off. Reopen, play each saved file, then seek past halfway.",
                14, MUTED, false);
        render();
    }

    private void card(LinearLayout body, SaveCheck.Arm arm, String caption, int saveId, int resultId, int playId) {
        LinearLayout card = panel(body);
        text(card, arm.label, 18, TEXT, true);
        text(card, caption, 13, MUTED, false);
        TextView status = text(card, "", 14, TEXT, false);
        status.setId(resultId);
        results.put(arm, status);
        saves.put(arm, button(card, arm.label + " save", saveId, () -> {
            closePlayer();
            check.begin(arm);
            render();
        }));
        plays.put(arm, button(card, arm == SaveCheck.Arm.PILOT ? "Play pilot file" : "Play v90 file", playId, () -> play(arm)));
    }

    private boolean airplane() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private void render() {
        SaveCheck.Link link = check.link();
        boolean running = check.running();
        if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show(network, link.handle == 0 && airplane() ? "Airplane mode · offline" : link.caption());
        show(advice, check.gate(link));
        for (SaveCheck.Arm arm : SaveCheck.Arm.values()) {
            show(results.get(arm), check.result(arm));
            saves.get(arm).setEnabled(check.canStart(arm, link));
            Button play = plays.get(arm);
            play.setVisibility(check.saved(arm) == null ? View.GONE : View.VISIBLE);
            play.setEnabled(!running);
        }
        stop.setVisibility(running ? View.VISIBLE : View.GONE);
        if (offlineEligible && (link.handle != 0 || !airplane())) offlineEligible = false;
        if (player == null || !prepared) return;
        try {
            int where = player.getCurrentPosition(), duration = player.getDuration();
            boolean playing = player.isPlaying();
            if (offlineEligible && !marked && !seeking && seekReached >= 0 && playing
                    && where > duration / 2 && where >= seekReached + 1000) {
                marked = check.markOfflinePass(playingArm);
            }
            String hint = marked ? " · Cold offline seek passed" : playing ? " · Playing" : " · Finished";
            show(playerClock, playingArm.label + " · " + clock(where) + " / " + clock(duration) + hint);
        } catch (IllegalStateException problem) { playerError("Could not play this saved test file."); }
    }

    private void play(SaveCheck.Arm arm) {
        if (check.running()) return;
        File audio = check.saved(arm);
        if (audio == null) { render(); return; }
        closePlayer();
        playingArm = arm;
        offlineEligible = check.cold(arm) && check.link().handle == 0 && airplane();
        playerPanel.setVisibility(View.VISIBLE);
        show(playerClock, "Opening " + arm.label + " saved file…");
        seek.setEnabled(false);
        MediaPlayer opened = new MediaPlayer();
        player = opened;
        try (FileInputStream source = new FileInputStream(audio)) {
            opened.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            opened.setDataSource(source.getFD());
            opened.setOnPreparedListener(ready -> {
                if (ready != player) return;
                try {
                    if (Math.abs(ready.getDuration() - SaveCheck.SECONDS * 1000L) > 6000) {
                        playerError("Saved audio duration did not match the control.");
                        return;
                    }
                    prepared = true;
                    ready.start();
                    seek.setEnabled(true);
                    render();
                } catch (IllegalStateException problem) { playerError("Could not start this saved file."); }
            });
            opened.setOnSeekCompleteListener(completed -> {
                if (completed != player || !seeking) return;
                seeking = false;
                int at = completed.getCurrentPosition();
                if (at > completed.getDuration() / 2) seekReached = at;
                render();
            });
            opened.setOnErrorListener((failed, what, extra) -> {
                if (failed == player) playerError("This saved file could not be decoded.");
                return true;
            });
            opened.prepareAsync();
        } catch (IOException | RuntimeException problem) { playerError("Could not open this saved test file."); }
    }

    private void seek() {
        if (!prepared || player == null) return;
        try {
            int position = player.getDuration() * 7 / 10;
            seeking = true;
            seekReached = -1;
            if (Build.VERSION.SDK_INT >= 26) player.seekTo(position, MediaPlayer.SEEK_CLOSEST);
            else player.seekTo(position);
            if (!player.isPlaying()) player.start();
        } catch (IllegalStateException problem) { playerError("Could not seek this saved file."); }
    }

    private void playerError(String message) {
        closePlayer();
        playerPanel.setVisibility(View.VISIBLE);
        seek.setEnabled(false);
        show(playerClock, message);
    }

    private void closePlayer() {
        if (player != null) { player.release(); player = null; }
        playingArm = null;
        offlineEligible = prepared = seeking = marked = false;
        seekReached = -1;
        if (playerPanel != null) playerPanel.setVisibility(View.GONE);
    }

    private void copy() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("BlackBox test results", check.report()));
        Toast.makeText(this, "Results copied. Nothing sent automatically.", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onStart() { super.onStart(); main.post(refresh); }
    @Override protected void onStop() { main.removeCallbacks(refresh); closePlayer(); super.onStop(); }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private static void show(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    private static String clock(int ms) { return String.format(Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60); }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout view = column();
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL);
        background.setCornerRadius(dp(15));
        view.setBackground(background);
        view.setPadding(dp(13), dp(11), dp(13), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        parent.addView(view, lp);
        return view;
    }

    private TextView text(LinearLayout parent, String content, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(2), 0, dp(2));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button button(LinearLayout parent, String label, int id, Runnable handler) {
        Button view = new Button(this);
        view.setId(id);
        view.setText(label);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setOnClickListener(ignored -> handler.run());
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
}
