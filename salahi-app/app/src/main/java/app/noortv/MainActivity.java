package app.noortv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/** Plays this shortcut's recitation from the APK and resumes where the listener stopped. */
public class MainActivity extends Activity {
    private static final String AUDIO = "recitation.mp3";
    private static final String POSITION = "position";
    private static final int SEEK_MS = 30_000;
    private static final int RESTART_MARGIN_MS = 5_000;
    private static final long TICK_MS = 500;
    private static final int TICKS_PER_SAVE = 10;
    private static final int PROGRESS_MAX = 1000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private SharedPreferences prefs;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private MediaPlayer player;
    private boolean prepared;
    private int ticks;
    private boolean resumeAfterFocus;

    private TextView status;
    private TextView time;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (BuildConfig.REMINDERS) {
            Reminder.schedule(this);
            Adhan.schedule(this);
        }
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        audio = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .build();
        setContentView(buildScreen());
    }

    @Override
    protected void onStart() {
        super.onStart();
        open();
    }

    @Override
    protected void onStop() {
        savePosition();
        close();
        super.onStop();
    }

    private void open() {
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        prepared = false;
        status.setText("جاري التحميل…");
        try (AssetFileDescriptor file = getAssets().openFd(AUDIO)) {
            mp.setAudioAttributes(attributes);
            mp.setDataSource(file);
            mp.setOnPreparedListener(p -> onPrepared(mp));
            mp.setOnCompletionListener(p -> onFinished(mp));
            mp.setOnErrorListener((p, what, extra) -> onFailed(mp));
            mp.prepareAsync();
        } catch (Exception e) {
            onFailed(mp);
        }
    }

    private void close() {
        resumeAfterFocus = false;
        ui.removeCallbacks(tick);
        audio.abandonAudioFocusRequest(focus);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
    }

    private void onPrepared(MediaPlayer mp) {
        if (mp != player) return;
        prepared = true;
        int saved = prefs.getInt(POSITION, 0);
        if (saved > 0 && saved < mp.getDuration() - RESTART_MARGIN_MS) {
            mp.seekTo(saved, MediaPlayer.SEEK_CLOSEST);
        }
        play();
    }

    private void onFinished(MediaPlayer mp) {
        if (mp != player) return;
        prefs.edit().putInt(POSITION, 0).apply();
        mp.seekTo(0);
        stopTicking();
        status.setText("خلصت. دوس OK للتشغيل من الأول");
        render();
    }

    private boolean onFailed(MediaPlayer mp) {
        if (mp == player) {
            prepared = false;
            stopTicking();
            status.setText("الملف مش راضي يشتغل");
        }
        return true;
    }

    private void onFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeAfterFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && prepared && player.isPlaying();
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocus) {
            resumeAfterFocus = false;
            play();
        }
    }

    private void play() {
        if (!prepared || player.isPlaying()) return;
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        player.start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("شغّال");
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void pause() {
        if (!prepared || !player.isPlaying()) return;
        player.pause();
        savePosition();
        stopTicking();
        status.setText("متوقف");
        render();
    }

    private void stopTicking() {
        ui.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void seekBy(int delta) {
        if (!prepared) return;
        int target = Math.max(0, Math.min(player.getDuration() - 1000, player.getCurrentPosition() + delta));
        player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        prefs.edit().putInt(POSITION, target).apply();
        renderAt(target);
    }

    private void onTick() {
        if (player == null || !prepared) return;
        render();
        if (++ticks % TICKS_PER_SAVE == 0) savePosition();
        ui.postDelayed(tick, TICK_MS);
    }

    private void savePosition() {
        if (prepared && player != null) prefs.edit().putInt(POSITION, player.getCurrentPosition()).apply();
    }

    private void render() {
        if (prepared) renderAt(player.getCurrentPosition());
    }

    private void renderAt(int position) {
        int duration = player.getDuration();
        progress.setProgress(duration > 0 ? (int) (position * (long) PROGRESS_MAX / duration) : 0);
        time.setText(clock(position) + " / " + clock(duration));
    }

    private static String clock(int ms) {
        int s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() == 0 && prepared) {
                    if (player.isPlaying()) pause();
                    else play();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_MS);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(120, 60, 120, 60);
        root.setBackgroundResource(R.drawable.player_bg);

        root.addView(label(getString(R.string.app_name), 48, Color.WHITE, true));
        root.addView(label(getString(R.string.reciter), 22, 0xCCFFFFFF, false));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        progress.setMax(PROGRESS_MAX);
        progress.setProgressTintList(ColorStateList.valueOf(0xFFFFE08A));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16);
        bar.topMargin = 56;
        root.addView(progress, bar);

        time = label("0:00 / 0:00", 24, Color.WHITE, false);
        time.setTextDirection(View.TEXT_DIRECTION_LTR);
        root.addView(time);

        status = label("", 26, 0xFFFFE08A, true);
        root.addView(status);

        root.addView(label("زرار OK: تشغيل أو إيقاف  ·  يمين: تقديم 30 ثانية  ·  شمال: رجوع 30 ثانية",
                18, 0x99FFFFFF, false));
        return root;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, 12, 0, 12);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }
}
