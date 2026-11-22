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
import android.media.AudioFocusRequest;
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
 * The adhan at each prayer time for the city in gradle.properties. It plays in full over Home and apps, and only its
 * opening phrase over HDMI or a channel. Fajr uses its own recording. Only the Kahf shortcut runs it.
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
    private static final int BANNER_MS = 30_000;
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    private static volatile MediaPlayer player;
    private static Runnable dismissBanner;
    private static AudioManager audio;
    private static AudioFocusRequest focus;

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

    /** "HH:mm" for Fajr..Isha on that day, from Aladhan (noor.method), cached per day and method for offline days. */
    private static String[] times(Context c, Calendar day) {
        String date = String.format(Locale.US, "%02d-%02d-%d", day.get(Calendar.DAY_OF_MONTH),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.YEAR));
        String key = date + "|" + BuildConfig.METHOD;
        SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        String cached = p.getString(key, null);
        if (cached != null) return cached.split(",");
        try {
            JSONObject t = new JSONObject(get("https://api.aladhan.com/v1/timings/" + date + "?latitude=" + LAT
                    + "&longitude=" + LON + "&method=" + BuildConfig.METHOD)).getJSONObject("data").getJSONObject("timings");
            String[] out = new String[KEYS.length];
            for (int i = 0; i < KEYS.length; i++) out[i] = t.getString(KEYS[i]).substring(0, 5);
            p.edit().putString(key, String.join(",", out)).apply();
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
        if (INPUTS.equals(top)) playOnInput(app, prayer);
        else playFull(app, prayer, HOME.equals(top));
    }

    /** Fajr has its own recording for «الصلاة خير من النوم»; without one it stays silent. */
    private static int recording(Context app, int prayer, boolean opening) {
        String name = (prayer == 0 ? "adhan_fajr" : "adhan") + (opening ? "_clip" : "");
        return app.getResources().getIdentifier(name, "raw", app.getPackageName());
    }

    private static void playFull(Context app, int prayer, boolean onHome) {
        String text = "🕌 حان الآن موعد أذان " + NAMES[prayer];
        int raw = recording(app, prayer, false);
        stop();
        MediaPlayer full = raw == 0 ? null : open(app, raw);
        if (full == null) {
            banner(app, text, BANNER_MS);
            return;
        }
        player = full;
        audio = app.getSystemService(AudioManager.class);
        // Transient focus pauses the app underneath and lets it resume when the adhan ends.
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(ATTRIBUTES).build();
        audio.requestAudioFocus(focus);
        full.setOnCompletionListener(mp -> stop());
        full.start();
        banner(app, text, onHome ? full.getDuration() : BANNER_MS, onHome);
        watch(app, full);
    }

    /** Over HDMI or a channel: a small line at the bottom and only the adhan's opening phrase. */
    private static void playOnInput(Context app, int prayer) {
        Reminder.showCompact(app, "🕌 حان الآن موعد أذان " + NAMES[prayer]);
        int raw = recording(app, prayer, true);
        if (raw == 0) return;
        stop();
        MediaPlayer clip = open(app, raw);
        if (clip == null) return;
        player = clip;
        clip.setOnCompletionListener(mp -> stop());
        clip.start();
    }

    private static MediaPlayer open(Context app, int raw) {
        return MediaPlayer.create(app, raw, ATTRIBUTES, app.getSystemService(AudioManager.class).generateAudioSessionId());
    }

    /** Moving to HDMI or a channel mid-adhan ends it, since those only get the opening phrase. */
    private static void watch(Context app, MediaPlayer playing) {
        long since = System.currentTimeMillis();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            while (player == playing) {
                SystemClock.sleep(1500);
                if (inputOpenedSince(app, since)) {
                    main.post(() -> {
                        if (player == playing) stop();
                    });
                    return;
                }
            }
        }, "adhan-watch").start();
    }

    private static boolean inputOpenedSince(Context c, long since) {
        UsageEvents ev = c.getSystemService(UsageStatsManager.class).queryEvents(since, System.currentTimeMillis());
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && INPUTS.equals(e.getPackageName())) return true;
        }
        return false;
    }

    private static void stop() {
        if (dismissBanner != null) dismissBanner.run();
        if (focus != null) {
            audio.abandonAudioFocusRequest(focus);
            focus = null;
        }
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
