package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a Salahi card live, so the TV rasterises its text at the tile's real size instead of shrinking an image.
 * Returns null when the card font was not bundled, and the tile falls back to the app's banner.
 */
final class CardArt extends Drawable {
    private static final int W = 640, H = 360;
    private static final float TEXT_RIGHT = W - 48, TITLE_CENTER = 115.6f, SUBTITLE_CENTER = 210;
    private static final float TITLE_SIZE = 72, SUBTITLE_SIZE = 50;
    private static final Map<String, int[]> COLORS = new HashMap<>();

    static {
        COLORS.put("app.noortv.kahf", new int[] {0xFF1E6B4E, 0xFF0F3D2E});
        COLORS.put("app.noortv.baqara", new int[] {0xFF836528, 0xFF3D2A0F});
        COLORS.put("app.noortv.sabah", new int[] {0xFFD8842C, 0xFF7A3B12});
        COLORS.put("app.noortv.masa", new int[] {0xFF384693, 0xFF141A3D});
        COLORS.put("app.noortv.ruqya", new int[] {0xFF1E757B, 0xFF0D3B40});
        COLORS.put("com.majnioui.tv.prayer", new int[] {0xFF1F8A63, 0xFF0C3D2F});
    }

    private static Typeface titleFont, subtitleFont;

    private final Paint background = new Paint(Paint.DITHER_FLAG);
    private final Paint title = text();
    private final Paint subtitle = text();
    private final String titleText, subtitleText;

    private CardArt(int[] colors, String titleText, String subtitleText) {
        this.titleText = titleText;
        this.subtitleText = subtitleText;
        float cx = .72f * W, cy = .22f * H;
        background.setShader(new RadialGradient(cx, cy, (float) Math.hypot(cx, H - cy),
                new int[] {colors[0], colors[1], colors[1]}, new float[] {0, .78f, 1}, Shader.TileMode.CLAMP));
        title.setTypeface(titleFont);
        subtitle.setTypeface(subtitleFont);
    }

    static Drawable of(Context context, PackageManager pm, String pkg) {
        int[] colors = COLORS.get(pkg);
        if (colors == null || !loadFonts(context)) return null;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            Resources res = pm.getResourcesForApplication(info);
            int id = res.getIdentifier("reciter", "string", pkg);
            if (id == 0) id = res.getIdentifier("place", "string", pkg);
            if (id == 0) return null;
            return new HiRes(new CardArt(colors, String.valueOf(pm.getApplicationLabel(info)), res.getString(id)));
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return null;
        }
    }

    private static boolean loadFonts(Context context) {
        if (titleFont != null) return true;
        try {
            subtitleFont = Typeface.createFromAsset(context.getAssets(), "card_subtitle.ttf");
            titleFont = Typeface.createFromAsset(context.getAssets(), "card_title.ttf");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Paint text() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setHinting(Paint.HINTING_ON);
        return paint;
    }

    /** Whole-pixel size and position, so the stems land on the TV's pixels instead of between two of them. */
    private static void line(Canvas canvas, String value, Paint paint, float size, float right, float center) {
        paint.setTextSize(Math.max(1, Math.round(size)));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(value, Math.round(right), Math.round(center - (metrics.ascent + metrics.descent) / 2), paint);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float scaleX = bounds.width() / (float) W, scaleY = bounds.height() / (float) H;
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        int unscaled = canvas.save();
        canvas.scale(scaleX, scaleY);
        canvas.drawRect(0, 0, W, H, background);
        canvas.restoreToCount(unscaled);
        line(canvas, titleText, title, TITLE_SIZE * scaleX, TEXT_RIGHT * scaleX, TITLE_CENTER * scaleY);
        line(canvas, subtitleText, subtitle, SUBTITLE_SIZE * scaleX, TEXT_RIGHT * scaleX, SUBTITLE_CENTER * scaleY);
        canvas.restoreToCount(saved);
    }

    // No intrinsic size: the tile sets the bounds to its real pixels and draws with no scaling.
    @Override public int getIntrinsicWidth() {
        return -1;
    }

    @Override public int getIntrinsicHeight() {
        return -1;
    }

    @Override public void setAlpha(int alpha) {
        background.setAlpha(alpha);
        title.setAlpha(alpha);
        subtitle.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        background.setColorFilter(filter);
        title.setColorFilter(filter);
        subtitle.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
