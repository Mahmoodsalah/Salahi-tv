package app.noortv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * The adhan at each prayer time for the city in gradle.properties. With the home screen up and nothing playing it plays the adhan in
 * res/raw (tools/fetch-adhan.sh) under a banner; while a film, a channel or HDMI is on it only shows the banner, so nothing is
 * cut off. Fajr is banner only: most adhan recordings lack «الصلاة خير من النوم». Only the Kahf shortcut runs it.
 */
public class Adhan extends BroadcastReceiver {
    static final String FIRE = "app.noortv.ADHAN";
    static final double LAT = BuildConfig.LAT, LON = BuildConfig.LON;
    static final String HOME = "com.gathin.tvhome";
    private static final String[] KEYS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] NAMES = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    private static final String DREAM = "android.service.dreams.DreamActivity";
    /** The TV's own app for HDMI and channels. */
    static final String INPUTS = "com.tcl.tv";
    /** The recording's first natural pause, after the opening takbir. */
    private static final int INPUT_CLIP_MS = 11_000;

    private static volatile MediaPlayer player;
    private static Runnable dismissBanner;

    /** Sets the alarm for the next prayer; fetches today's and tomorrow's times in the background. */
    static void schedule(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            Calendar now = Calendar.getInstance();
            for (int dayOffset = 0; dayOffset < 2; dayOffset++) {
                Calendar day = (Calendar) now.clone();
                day.add(Calendar.DAY_OF_YEAR, dayOffset);
                String[] times = times(app, day);
                if (times == null) continue;
                for (int i = 0; i < KEYS.length; i++) {
                    String[] hm = times[i].split(":");
                    Calendar at = (Calendar) day.clone();
                    at.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                    at.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                    at.set(Calendar.SECOND, 0);
                    at.set(Calendar.MILLISECOND, 0);
                    if (at.getTimeInMillis() > now.getTimeInMillis() + 30_000) {
                        set(app, at.getTimeInMillis(), i);
                        return;
                    }
                }
            }
        }, "adhan").start();
    }

    private static void set(Context c, long when, int prayer) {
        Intent fire = new Intent(c, Adhan.class).setAction(FIRE).putExtra("p", prayer);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, fire,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    /** "HH:mm" for Fajr..Isha on that day, from Aladhan (noor.method), cached per day for offline days. */
    private static String[] times(Context c, Calendar day) {
        String date = String.format(Locale.US, "%02d-%02d-%d", day.get(Calendar.DAY_OF_MONTH),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.YEAR));
        SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        String cached = p.getString(date, null);
        if (cached != null) return cached.split(",");
        try {
            JSONObject t = new JSONObject(get("https://api.aladhan.com/v1/timings/" + date + "?latitude=" + LAT
                    + "&longitude=" + LON + "&method=" + BuildConfig.METHOD)).getJSONObject("data").getJSONObject("timings");
            String[] out = new String[KEYS.length];
            for (int i = 0; i < KEYS.length; i++) out[i] = t.getString(KEYS[i]).substring(0, 5);
            p.edit().putString(date, String.join(",", out)).apply();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!BuildConfig.REMINDERS || !FIRE.equals(i.getAction())) return;
        Context app = c.getApplicationContext();
        int prayer = i.getIntExtra("p", 1);
        schedule(app);
        if (!app.getSystemService(PowerManager.class).isInteractive()) return; // TV is off
        String top = topApp(app);
        if (INPUTS.equals(top)) {
            playOnInput(app, prayer);
            return;
        }
        boolean idle = prayer != 0 && HOME.equals(top)
                && !app.getSystemService(AudioManager.class).isMusicActive();
        fire(app, prayer, idle);
    }

    /** Banner always; the adhan itself only when play is true. */
    static void fire(Context app, int prayer, boolean play) {
        String text = "🕌 حان الآن موعد أذان " + NAMES[prayer];
        if (!play) {
            banner(app, text, 30_000);
            return;
        }
        int raw = app.getResources().getIdentifier("adhan", "raw", app.getPackageName());
        if (raw == 0) {
            banner(app, text, 30_000);
            return;
        }
        stop();
        player = open(app, raw);
        if (player == null) {
            banner(app, text, 30_000);
            return;
        }
        player.setOnCompletionListener(mp -> stop());
        player.start();
        banner(app, text, player.getDuration(), true);
        watch(app, player);
    }

    /** Over HDMI or a channel: a small line at the bottom and only the adhan's opening phrase. */
    private static void playOnInput(Context app, int prayer) {
        Reminder.showCompact(app, "🕌 حان الآن موعد أذان " + NAMES[prayer]);
        if (prayer == 0) return;
        int raw = app.getResources().getIdentifier("adhan", "raw", app.getPackageName());
        if (raw == 0) return;
        stop();
        MediaPlayer clip = open(app, raw);
        if (clip == null) return;
        player = clip;
        clip.setOnCompletionListener(mp -> stop());
        clip.start();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (player == clip) stop();
        }, INPUT_CLIP_MS);
    }

    private static MediaPlayer open(Context app, int raw) {
        return MediaPlayer.create(app, raw, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                app.getSystemService(AudioManager.class).generateAudioSessionId());
    }

    /** Stops the adhan as soon as an app is opened over the home screen. */
    private static void watch(Context app, MediaPlayer playing) {
        long since = System.currentTimeMillis();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            while (player == playing) {
                SystemClock.sleep(1500);
                if (appOpenedSince(app, since)) {
                    main.post(() -> {
                        if (player == playing) stop();
                    });
                    return;
                }
            }
        }, "adhan-watch").start();
    }

    private static boolean appOpenedSince(Context c, long since) {
        UsageEvents ev = c.getSystemService(UsageStatsManager.class).queryEvents(since, System.currentTimeMillis());
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED
                    && !DREAM.equals(e.getClassName()) && !HOME.equals(e.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private static void stop() {
        if (dismissBanner != null) dismissBanner.run();
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
        player = null;
    }

    /** The app in front, from usage events (GET_USAGE_STATS is granted over adb), or null if unknown. */
    static String topApp(Context c) {
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - 24 * 3600_000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String top = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && !DREAM.equals(e.getClassName())) {
                top = e.getPackageName();
            }
        }
        return top;
    }

    private static void banner(Context c, String text, int ms) {
        banner(c, text, ms, false);
    }

    /** holdScreen keeps the screensaver away for as long as the banner is up. */
    private static void banner(Context c, String text, int ms, boolean holdScreen) {
        if (!Settings.canDrawOverlays(c)) return;
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            WindowManager wm = c.getSystemService(WindowManager.class);
            TextView v = new TextView(c);
            v.setText(text);
            v.setTextColor(Color.WHITE);
            v.setTextSize(30);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setPadding(56, 24, 56, 24);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE60F3D2E);
            bg.setCornerRadius(48);
            bg.setStroke(3, 0xFFFFE08A);
            v.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | (holdScreen ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0),
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = 215; // below the clock block, so the weather and next prayer stay readable
            try {
                wm.addView(v, lp);
            } catch (Exception e) {
                return;
            }
            Runnable remove = () -> {
                try {
                    wm.removeView(v);
                } catch (Exception ignored) {
                }
            };
            main.postDelayed(remove, ms);
            if (holdScreen) {
                dismissBanner = () -> {
                    dismissBanner = null;
                    main.removeCallbacks(remove);
                    remove.run();
                };
            }
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
