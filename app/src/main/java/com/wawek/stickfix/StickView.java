package com.wawek.stickfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Live view of one stick: the gate with raw (grey) and shaped (blue) positions
 * on the left, and the response curve with the current deflection on the right.
 */
final class StickView extends View {
    private static final int RAW = 0xFF9AA3AD, OUT = 0xFF4FC3F7, GRID = 0xFF3A3F47, DEAD = 0x55E57373, TEXT = 0xFFB0B8C0;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private final String title;
    private StickConfig cfg = new StickConfig();
    private float rx, ry, ox, oy;

    StickView(Context ctx, String title) {
        super(ctx);
        this.title = title;
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        text.setColor(TEXT);
        text.setTextSize(12 * density);
    }

    void setConfig(StickConfig c) {
        cfg = c;
        invalidate();
    }

    void setSample(float rx, float ry, float ox, float oy) {
        this.rx = rx;
        this.ry = ry;
        this.ox = ox;
        this.oy = oy;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.min(w / 2, (int) (230 * density)));
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), pad = 8 * density;
        float size = h - 2 * pad;
        drawGate(c, pad, pad, size);
        drawCurve(c, size + 3 * pad, pad, Math.min(size, getWidth() - size - 4 * pad), size);
    }

    private void drawGate(Canvas c, float x0, float y0, float size) {
        float cx = x0 + size / 2, cy = y0 + size / 2, rad = size / 2;
        stroke.setStrokeWidth(density);
        stroke.setPathEffect(null);
        stroke.setColor(GRID);
        c.drawCircle(cx, cy, rad, stroke);
        c.drawLine(cx - rad, cy, cx + rad, cy, stroke);
        c.drawLine(cx, cy - rad, cx, cy + rad, stroke);

        fill.setColor(DEAD);
        c.drawCircle(cx, cy, (float) (rad * cfg.inner), fill);
        stroke.setColor(0xFF6C7A89);
        stroke.setPathEffect(new DashPathEffect(new float[]{4 * density, 4 * density}, 0));
        c.drawCircle(cx, cy, (float) (rad * Math.min(cfg.outer, 1.4)), stroke);
        stroke.setPathEffect(null);

        fill.setColor(RAW);
        c.drawCircle(cx + rx * rad, cy + ry * rad, 5 * density, fill);
        fill.setColor(OUT);
        c.drawCircle(cx + ox * rad, cy + oy * rad, 7 * density, fill);

        c.drawText(title, x0, y0 + text.getTextSize(), text);
        double rawMag = Math.hypot(rx, ry), outMag = Math.hypot(ox, oy);
        String v = String.format(java.util.Locale.ROOT, "raw %.1f%%  out %.1f%%", rawMag * 100, outMag * 100);
        c.drawText(v, x0, y0 + size, text);
    }

    private void drawCurve(Canvas c, float x0, float y0, float w, float h) {
        if (w <= 0) return;
        stroke.setStrokeWidth(density);
        stroke.setColor(GRID);
        c.drawRect(x0, y0, x0 + w, y0 + h, stroke);
        c.drawLine(x0, y0 + h, x0 + w, y0, stroke);  // linear reference

        path.reset();
        int steps = 120;
        for (int i = 0; i <= steps; i++) {
            double r = (double) i / steps;
            float px = x0 + (float) r * w, py = y0 + h - (float) cfg.shape(r) * h;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        stroke.setColor(OUT);
        stroke.setStrokeWidth(2 * density);
        c.drawPath(path, stroke);

        double rawMag = Math.min(1, Math.hypot(rx, ry));
        float mx = x0 + (float) rawMag * w;
        stroke.setColor(RAW);
        stroke.setStrokeWidth(density);
        c.drawLine(mx, y0, mx, y0 + h, stroke);
        fill.setColor(OUT);
        c.drawCircle(mx, y0 + h - (float) Math.min(1, Math.hypot(ox, oy)) * h, 4 * density, fill);
        c.drawText("in → out", x0 + 4 * density, y0 + text.getTextSize(), text);
    }
}
