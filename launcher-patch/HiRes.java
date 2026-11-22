package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/**
 * Draws another drawable at three times its on-screen size (2160p art on a 720p interface) and averages every
 * 3x3 block down to one pixel, so edges get nine samples each instead of the renderer's own antialiasing.
 */
final class HiRes extends Drawable implements Drawable.Callback {
    private static final int SCALE = 3;

    private final Drawable source;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap frame;
    private boolean stale = true;

    HiRes(Drawable source) {
        this.source = source;
        source.setCallback(this);
    }

    /** Swaps a button's start icon for its high-resolution rendering, keeping its size. */
    static void apply(TextView button) {
        Drawable[] icons = button.getCompoundDrawablesRelative();
        if (icons[0] == null || icons[0] instanceof HiRes) return;
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new HiRes(icons[0]), icons[1], icons[2], icons[3]);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int w = bounds.width(), h = bounds.height();
        if (w <= 0 || h <= 0) return;
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            stale = true;
        }
        if (stale) {
            render(w, h);
            stale = false;
        }
        canvas.drawBitmap(frame, bounds.left, bounds.top, paint);
    }

    private void render(int w, int h) {
        int bw = w * SCALE, bh = h * SCALE;
        Bitmap big = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        source.setBounds(0, 0, bw, bh);
        source.draw(new Canvas(big));
        int[] in = new int[bw * bh];
        big.getPixels(in, 0, bw, 0, 0, bw, bh);
        big.recycle();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < SCALE; dy++) {
                    int row = (y * SCALE + dy) * bw + x * SCALE;
                    for (int dx = 0; dx < SCALE; dx++) {
                        int c = in[row + dx], ca = c >>> 24;
                        a += ca;   // colours are weighted by their alpha, so clear pixels do not darken an edge
                        r += (c >> 16 & 0xFF) * ca;
                        g += (c >> 8 & 0xFF) * ca;
                        b += (c & 0xFF) * ca;
                    }
                }
                if (a != 0) out[y * w + x] = a / (SCALE * SCALE) << 24 | r / a << 16 | g / a << 8 | b / a;
            }
        }
        frame.setPixels(out, 0, w, 0, 0, w, h);
    }

    private void refresh() {
        stale = true;
        invalidateSelf();
    }

    @Override public int getIntrinsicWidth() {
        return source.getIntrinsicWidth();
    }

    @Override public int getIntrinsicHeight() {
        return source.getIntrinsicHeight();
    }

    @Override public int getOpacity() {
        return source.getOpacity();
    }

    @Override public Drawable mutate() {
        source.mutate();
        return this;
    }

    @Override public boolean isStateful() {
        return source.isStateful();
    }

    @Override protected boolean onStateChange(int[] state) {
        boolean changed = source.setState(state);
        if (changed) refresh();
        return changed;
    }

    @Override public void setTintList(ColorStateList tint) {
        source.setTintList(tint);
        refresh();
    }

    @Override public void setTintMode(PorterDuff.Mode mode) {
        source.setTintMode(mode);
        refresh();
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public void invalidateDrawable(Drawable who) {
        refresh();
    }

    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override public void unscheduleDrawable(Drawable who, Runnable what) {
        unscheduleSelf(what);
    }
}
