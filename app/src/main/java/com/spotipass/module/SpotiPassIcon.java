package com.spotipass.module;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class SpotiPassIcon extends Drawable {

    private static final float VIEWPORT = 108f;

    private final Paint shieldPaint;
    private final Paint arrowPaint;
    private final Path shieldPath;
    private final Path arrowPath;

    SpotiPassIcon() {
        shieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shieldPaint.setColor(Color.parseColor("#1976D2"));
        shieldPaint.setStyle(Paint.Style.FILL);

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStyle(Paint.Style.FILL);

        // M54,20 L30,30 L30,55 C30,72 40,84 54,90 C68,84 78,72 78,55 L78,30 Z
        shieldPath = new Path();
        shieldPath.moveTo(54, 20);
        shieldPath.lineTo(30, 30);
        shieldPath.lineTo(30, 55);
        shieldPath.cubicTo(30, 72, 40, 84, 54, 90);
        shieldPath.cubicTo(68, 84, 78, 72, 78, 55);
        shieldPath.lineTo(78, 30);
        shieldPath.close();

        // M44,50 L60,50 L55,43 L59,40 L70,54 L59,68 L55,65 L60,58 L44,58 Z
        arrowPath = new Path();
        arrowPath.moveTo(44, 50);
        arrowPath.lineTo(60, 50);
        arrowPath.lineTo(55, 43);
        arrowPath.lineTo(59, 40);
        arrowPath.lineTo(70, 54);
        arrowPath.lineTo(59, 68);
        arrowPath.lineTo(55, 65);
        arrowPath.lineTo(60, 58);
        arrowPath.lineTo(44, 58);
        arrowPath.close();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        int save = canvas.save();
        float sx = bounds.width() / VIEWPORT;
        float sy = bounds.height() / VIEWPORT;
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(sx, sy);

        canvas.drawPath(shieldPath, shieldPaint);
        canvas.drawPath(arrowPath, arrowPaint);

        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        shieldPaint.setAlpha(alpha);
        arrowPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        shieldPaint.setColorFilter(colorFilter);
        arrowPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 108;
    }

    @Override
    public int getIntrinsicHeight() {
        return 108;
    }
}
