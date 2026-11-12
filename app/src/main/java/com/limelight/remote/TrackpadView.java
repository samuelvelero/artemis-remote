package com.limelight.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Laptop-style trackpad: one finger moves the cursor, tap clicks, two-finger tap right-clicks,
 * three-finger tap middle-clicks, two-finger drag scrolls, double-tap-and-drag holds the left button.
 */
public class TrackpadView extends View {
    public interface Sink {
        void move(int dx, int dy);
        void button(int button, boolean down); // 1 left, 2 middle, 3 right
        void scroll(int vertical, int horizontal); // high-res units, 120 = one notch
    }

    private static final int TAP_TIMEOUT_MS = 250;
    private static final int DOUBLE_TAP_MS = 300;

    private final Sink sink;
    private final RemotePrefs prefs;
    private final float density;
    private final float slop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean opaque = true;

    private float lastX, lastY, downX, downY;
    private float lastCx, lastCy;
    private float remX, remY, remV, remH;
    private long downTime, lastTapTime;
    private float lastTapX, lastTapY;
    private int maxPointers;
    private boolean moved, potentialDrag, dragging;

    public TrackpadView(Context context, RemotePrefs prefs, Sink sink) {
        super(context);
        this.prefs = prefs;
        this.sink = sink;
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** Opaque = remote-only mode (covers the stream); transparent = video visible behind. */
    public void setOpaque(boolean opaque) {
        this.opaque = opaque;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!opaque) {
            return;
        }
        canvas.drawColor(0xFF121315);
        float m = 8 * density;
        rect.set(m, m, getWidth() - m, getHeight() - m);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1B1D20);
        canvas.drawRoundRect(rect, 14 * density, 14 * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(0xFF34373C);
        canvas.drawRoundRect(rect, 14 * density, 14 * density, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF6B7078);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(15 * density);
        float cy = getHeight() / 2f;
        canvas.drawText("Trackpad", getWidth() / 2f, cy - 12 * density, paint);
        paint.setTextSize(11 * density);
        canvas.drawText("Toque: clic  ·  2 dedos: clic derecho / scroll", getWidth() / 2f, cy + 8 * density, paint);
        canvas.drawText("Doble toque y arrastrar: seleccionar", getWidth() / 2f, cy + 24 * density, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downTime = now;
                maxPointers = 1;
                moved = false;
                dragging = false;
                remX = remY = remV = remH = 0;
                potentialDrag = now - lastTapTime < DOUBLE_TAP_MS
                        && Math.hypot(downX - lastTapX, downY - lastTapY) < 40 * density;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                lastCx = centroidX(e);
                lastCy = centroidY(e);
                if (dragging) {
                    sink.button(1, false);
                    dragging = false;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() == 1 && maxPointers == 1) {
                    float x = e.getX(), y = e.getY();
                    if (!moved && Math.hypot(x - downX, y - downY) > slop) {
                        moved = true;
                        lastX = x;
                        lastY = y;
                        if (potentialDrag) {
                            dragging = true;
                            sink.button(1, true);
                        }
                    }
                    if (moved) {
                        moveCursor(x - lastX, y - lastY);
                        lastX = x;
                        lastY = y;
                    }
                } else if (e.getPointerCount() == 2) {
                    float cx = centroidX(e), cy = centroidY(e);
                    float dx = cx - lastCx, dy = cy - lastCy;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        lastCx = cx;
                        lastCy = cy;
                        dx = dy = 0;
                    }
                    if (moved) {
                        // Natural scrolling: content follows the fingers
                        remV += dy / density * 5f;
                        remH += -dx / density * 5f;
                        int v = (int) remV, h = (int) remH;
                        remV -= v;
                        remH -= h;
                        if (Math.abs(remV) > Math.abs(remH) * 2) {
                            h = 0;
                        }
                        if (v != 0 || h != 0) {
                            sink.scroll(v, h);
                        }
                        lastCx = cx;
                        lastCy = cy;
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP: {
                // Recompute the centroid without the lifted pointer on the next move
                int skip = e.getActionIndex();
                float sx = 0, sy = 0;
                int n = 0;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (i != skip) {
                        sx += e.getX(i);
                        sy += e.getY(i);
                        n++;
                    }
                }
                if (n > 0) {
                    lastCx = sx / n;
                    lastCy = sy / n;
                }
                break;
            }

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    sink.button(1, false);
                    dragging = false;
                } else if (!moved && now - downTime < TAP_TIMEOUT_MS) {
                    int button = maxPointers == 1 ? 1 : (maxPointers == 2 ? 3 : 2);
                    sink.button(button, true);
                    sink.button(button, false);
                    if (maxPointers == 1) {
                        lastTapTime = now;
                        lastTapX = e.getX();
                        lastTapY = e.getY();
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    sink.button(1, false);
                    dragging = false;
                }
                break;
        }
        return true;
    }

    private void moveCursor(float dxPx, float dyPx) {
        float dx = dxPx / density, dy = dyPx / density;
        float factor = prefs.sensitivityX10 / 10f;
        if (prefs.accel) {
            float speed = (float) Math.hypot(dx, dy);
            factor *= 1f + Math.min(speed / 10f, 2f);
        }
        remX += dx * factor;
        remY += dy * factor;
        int ix = (int) remX, iy = (int) remY;
        remX -= ix;
        remY -= iy;
        if (ix != 0 || iy != 0) {
            sink.move(ix, iy);
        }
    }

    private static float centroidX(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            s += e.getX(i);
        }
        return s / e.getPointerCount();
    }

    private static float centroidY(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            s += e.getY(i);
        }
        return s / e.getPointerCount();
    }
}
