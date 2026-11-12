package com.limelight.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gboard-like portrait keyboard (Spanish Latin America) with PC keys: number row,
 * sticky Ctrl/Win/Alt, Tab and arrows. Letters and digits are sent as virtual keys so
 * shortcuts and games work; ñ, accents and symbols are sent as UTF-8 text so they do
 * not depend on the keyboard layout configured on the PC.
 */
public class RemoteKeyboardView extends View {
    public interface Sink {
        void sendKey(short vk, boolean down, byte modifiers);
        void sendText(String text);
    }

    private static final int T_CHAR = 0, T_FUNC = 1, T_SHIFT = 2, T_SYM = 3, T_MOD = 4;
    private static final int M_SHIFT = 0, M_CTRL = 1, M_ALT = 2, M_WIN = 3;
    private static final int OFF = 0, ONCE = 1, LOCK = 2;
    private static final short[] MOD_VK = {Vk.SHIFT, Vk.CTRL, Vk.ALT, Vk.WIN};
    private static final byte[] MOD_FLAG = {0x01, 0x02, 0x04, 0x08};

    private static final int LONG_PRESS_MS = 320;
    private static final int REPEAT_DELAY_MS = 400;
    private static final int REPEAT_INTERVAL_MS = 45;

    private static final int C_BG = 0xFF202124;
    private static final int C_KEY = 0xFF4A4D52;
    private static final int C_KEY_FUNC = 0xFF2F3237;
    private static final int C_KEY_PRESSED = 0xFF6B6F76;
    private static final int C_ACCENT = 0xFF8AB4F8;
    private static final int C_TEXT = 0xFFE8EAED;
    private static final int C_HINT = 0xFF9AA0A6;
    private static final int C_POPUP = 0xFF5F6368;

    private static final Locale ES = new Locale("es");

    private static class Key {
        String label;
        String text;
        String hint;
        String[] alts;
        int vk;
        int type = T_CHAR;
        int mod;
        float weight = 1f;
        boolean preferText;
        boolean repeat;
        boolean letter;
        boolean accentKey;
        final RectF r = new RectF();
    }

    private static class Touch {
        Key key;
        boolean held;       // key is held down on the host (func-style)
        boolean shiftWrapped;
        boolean altsShown;
        int altIndex;
        float altLeft, altCellW;
    }

    private final Sink sink;
    private final RemotePrefs prefs;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;

    private final List<List<Key>> letters = new ArrayList<>();
    private final List<List<Key>> symbols = new ArrayList<>();
    private List<List<Key>> layer;

    private final int[] mods = new int[4];
    private long lastShiftTap;
    private final SparseArray<Touch> touches = new SparseArray<>();
    private Touch popupTouch;

    public RemoteKeyboardView(Context context, RemotePrefs prefs, Sink sink) {
        super(context);
        this.prefs = prefs;
        this.sink = sink;
        density = getResources().getDisplayMetrics().density;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        setHapticFeedbackEnabled(true);
        buildLayers();
        layer = letters;
    }

    // ---------------------------------------------------------------- layout definition

    private static Key ch(String c, String... alts) {
        Key k = new Key();
        k.label = k.text = c;
        k.preferText = true;
        if (alts.length > 0) {
            k.alts = alts;
        }
        return k;
    }

    private static Key letter(char c, String... alts) {
        Key k = ch(String.valueOf(c), alts);
        k.vk = Character.toUpperCase(c);
        k.preferText = false;
        k.letter = true;
        return k;
    }

    private static Key digit(char c, String shifted) {
        Key k = ch(String.valueOf(c), shifted);
        k.vk = c;
        k.preferText = false;
        k.hint = shifted;
        return k;
    }

    private static Key func(String label, int vk, float weight, boolean repeat) {
        Key k = new Key();
        k.label = label;
        k.vk = vk;
        k.type = T_FUNC;
        k.weight = weight;
        k.repeat = repeat;
        return k;
    }

    private static Key special(String label, int type, float weight) {
        Key k = new Key();
        k.label = label;
        k.type = type;
        k.weight = weight;
        return k;
    }

    private static Key mod(String label, int mod) {
        Key k = special(label, T_MOD, 1.25f);
        k.mod = mod;
        return k;
    }

    private static List<Key> row(Key... keys) {
        List<Key> l = new ArrayList<>();
        for (Key k : keys) {
            l.add(k);
        }
        return l;
    }

    private List<Key> numberRow() {
        return row(digit('1', "!"), digit('2', "\""), digit('3', "#"), digit('4', "$"), digit('5', "%"),
                digit('6', "&"), digit('7', "/"), digit('8', "("), digit('9', ")"), digit('0', "="));
    }

    private List<Key> spaceRow(String switchLabel) {
        Key comma = ch(",", ";", "¿", "¡", "'", "\"");
        comma.vk = Vk.OEM_COMMA;
        Key period = ch(".", ":", "?", "!", "-", "_", "@");
        period.vk = Vk.OEM_PERIOD;
        Key enter = func("↵", Vk.ENTER, 1.5f, true);
        enter.accentKey = true;
        return row(special(switchLabel, T_SYM, 1.5f), comma, func("", Vk.SPACE, 5f, true), period, enter);
    }

    private List<Key> pcRow() {
        Key tab = func("Tab", Vk.TAB, 1.25f, true);
        Key left = func("←", Vk.LEFT, 1.25f, true);
        Key up = func("↑", Vk.UP, 1.25f, true);
        Key down = func("↓", Vk.DOWN, 1.25f, true);
        Key right = func("→", Vk.RIGHT, 1.25f, true);
        return row(mod("Ctrl", M_CTRL), mod("Win", M_WIN), mod("Alt", M_ALT), tab, left, up, down, right);
    }

    /** Esc + F1–F12, always on screen as the top row of every layer. */
    private List<Key> functionRow() {
        List<Key> l = new ArrayList<>();
        l.add(func("Esc", Vk.ESC, 1.4f, false));
        for (int i = 0; i < 12; i++) {
            l.add(func("F" + (i + 1), Vk.F1 + i, 1f, false));
        }
        return l;
    }

    private void buildLayers() {
        Key enie = ch("ñ");
        enie.vk = Vk.OEM_3;
        enie.letter = true;

        letters.add(functionRow());
        letters.add(numberRow());
        letters.add(row(letter('q'), letter('w'), letter('e', "é", "€"), letter('r'), letter('t'),
                letter('y'), letter('u', "ú", "ü"), letter('i', "í"), letter('o', "ó"), letter('p')));
        letters.add(row(letter('a', "á", "@"), letter('s'), letter('d'), letter('f'), letter('g'),
                letter('h'), letter('j'), letter('k'), letter('l'), enie));
        letters.add(row(special("⇧", T_SHIFT, 1.5f), letter('z'), letter('x'), letter('c', "ç"),
                letter('v'), letter('b'), letter('n', "ñ"), letter('m'),
                func("⌫", Vk.BACKSPACE, 1.5f, true)));
        letters.add(spaceRow("?123"));
        letters.add(pcRow());

        symbols.add(functionRow());
        symbols.add(numberRow());
        symbols.add(row(ch("@"), ch("#"), ch("$", "€", "£"), ch("_"), ch("&"), ch("-", "~", "–", "—"),
                ch("+", "±"), ch("("), ch(")"), ch("/")));
        symbols.add(row(ch("*", "^", "°"), ch("\""), ch("'", "`", "´"), ch(":"), ch(";"), ch("!"),
                ch("?"), ch("¿"), ch("¡"), ch("\\")));
        symbols.add(row(ch("|"), ch("{"), ch("}"), ch("["), ch("]"), ch("<"), ch(">"), ch("=", "≠", "≈"),
                ch("%", "‰"), func("⌫", Vk.BACKSPACE, 1f, true)));
        symbols.add(spaceRow("ABC"));
        symbols.add(pcRow());
    }

    // ---------------------------------------------------------------- measure / layout

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (totalRowWeight(letters) * prefs.keyHeightDp * density + 6 * density);
        setMeasuredDimension(w, h);
    }

    private static final float FUNCTION_ROW_WEIGHT = 0.8f;

    private static float rowWeight(int index) {
        return index == 0 ? FUNCTION_ROW_WEIGHT : 1f;
    }

    private static float totalRowWeight(List<List<Key>> rows) {
        float t = 0;
        for (int i = 0; i < rows.size(); i++) {
            t += rowWeight(i);
        }
        return t;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutKeys(letters, w, h);
        layoutKeys(symbols, w, h);
    }

    private void layoutKeys(List<List<Key>> rows, int w, int h) {
        float pad = 3 * density;
        float unitH = (h - 2 * pad) / totalRowWeight(rows);
        float y = pad;
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Key> row = rows.get(ri);
            float rowH = unitH * rowWeight(ri);
            float total = 0;
            for (Key k : row) {
                total += k.weight;
            }
            float unit = (w - 2 * pad) / total;
            float x = pad;
            for (Key k : row) {
                float kw = k.weight * unit;
                k.r.set(x, y, x + kw, y + rowH);
                x += kw;
            }
            y += rowH;
        }
    }

    public void applyPrefs() {
        requestLayout();
        invalidate();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        // Not drawColor(): the panel does not clip children (so key popups can overflow
        // upwards), and drawColor would paint over every view above the keyboard.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(C_BG);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        float gapX = 2.5f * density, gapY = 4 * density, radius = 6 * density;
        boolean upper = mods[M_SHIFT] != OFF;

        for (List<Key> row : layer) {
            for (Key k : row) {
                tmp.set(k.r.left + gapX, k.r.top + gapY, k.r.right - gapX, k.r.bottom - gapY);
                boolean pressed = isPressed(k);
                int bg;
                int fg = C_TEXT;
                if (k.type == T_MOD || k.type == T_SHIFT) {
                    int state = mods[k.type == T_SHIFT ? M_SHIFT : k.mod];
                    bg = state == LOCK ? C_ACCENT : (state == ONCE ? C_KEY_PRESSED : C_KEY_FUNC);
                    if (state == LOCK) {
                        fg = C_BG;
                    } else if (state == ONCE) {
                        fg = C_ACCENT;
                    }
                } else if (k.accentKey) {
                    bg = pressed ? 0xFFAECBFA : C_ACCENT;
                    fg = C_BG;
                } else if (pressed) {
                    bg = C_KEY_PRESSED;
                } else if (k.type == T_CHAR || k.vk == Vk.SPACE) {
                    bg = C_KEY;
                } else {
                    bg = C_KEY_FUNC;
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(bg);
                canvas.drawRoundRect(tmp, radius, radius, paint);

                String label = k.letter && upper ? k.label.toUpperCase(ES) : k.label;
                paint.setColor(fg);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.DEFAULT);
                boolean small = label.length() > 1;
                float size = small ? 13 : (k.type == T_CHAR ? 21 : 19);
                if (small && tmp.width() < 34 * density) {
                    size = 11.5f; // narrow F-keys
                }
                paint.setTextSize(size * density);
                float ty = tmp.centerY() - (paint.descent() + paint.ascent()) / 2;
                canvas.drawText(label, tmp.centerX(), ty, paint);

                if (k.hint != null) {
                    paint.setColor(C_HINT);
                    paint.setTextSize(9.5f * density);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(k.hint, tmp.right - 3 * density, tmp.top + 11 * density, paint);
                }
            }
        }

        drawPopup(canvas);
    }

    private boolean isPressed(Key k) {
        for (int i = 0; i < touches.size(); i++) {
            if (touches.valueAt(i).key == k) {
                return true;
            }
        }
        return false;
    }

    private void drawPopup(Canvas canvas) {
        Touch t = popupTouch;
        if (t == null || t.key.type != T_CHAR) {
            return;
        }
        Key k = t.key;
        boolean upper = mods[M_SHIFT] != OFF;
        float radius = 8 * density;
        float h = k.r.height() * 1.05f;
        float bottom = k.r.top - 2 * density;

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT);

        if (t.altsShown) {
            float cell = t.altCellW;
            float left = t.altLeft;
            tmp.set(left, bottom - h, left + cell * k.alts.length, bottom);
            paint.setColor(C_POPUP);
            canvas.drawRoundRect(tmp, radius, radius, paint);
            for (int i = 0; i < k.alts.length; i++) {
                float cx = left + cell * i + cell / 2;
                if (i == t.altIndex) {
                    tmp.set(left + cell * i + 2 * density, bottom - h + 2 * density,
                            left + cell * (i + 1) - 2 * density, bottom - 2 * density);
                    paint.setColor(C_ACCENT);
                    canvas.drawRoundRect(tmp, radius, radius, paint);
                }
                paint.setColor(i == t.altIndex ? C_BG : C_TEXT);
                paint.setTextSize(22 * density);
                String s = upper ? k.alts[i].toUpperCase(ES) : k.alts[i];
                canvas.drawText(s, cx, bottom - h / 2 - (paint.descent() + paint.ascent()) / 2, paint);
            }
        } else if (prefs.popup) {
            float w = Math.max(k.r.width() * 1.25f, 44 * density);
            float left = Math.max(0, Math.min(getWidth() - w, k.r.centerX() - w / 2));
            tmp.set(left, bottom - h, left + w, bottom);
            paint.setColor(C_POPUP);
            canvas.drawRoundRect(tmp, radius, radius, paint);
            paint.setColor(C_TEXT);
            paint.setTextSize(28 * density);
            String s = k.letter && upper ? k.label.toUpperCase(ES) : k.label;
            canvas.drawText(s, tmp.centerX(), tmp.centerY() - (paint.descent() + paint.ascent()) / 2, paint);
        }
    }

    // ---------------------------------------------------------------- touch

    private Key findKey(float x, float y) {
        Key best = null;
        for (List<Key> row : layer) {
            RectF first = row.get(0).r;
            if (y >= first.top && y < first.bottom || (best == null && y < first.top)) {
                for (Key k : row) {
                    best = k;
                    if (x < k.r.right) {
                        break;
                    }
                }
                return best;
            }
        }
        List<Key> last = layer.get(layer.size() - 1);
        for (Key k : last) {
            best = k;
            if (x < k.r.right) {
                break;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                onDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Touch t = touches.get(e.getPointerId(i));
                    if (t != null && t.altsShown) {
                        int n = t.key.alts.length;
                        int ai = (int) ((e.getX(i) - t.altLeft) / t.altCellW);
                        ai = Math.max(0, Math.min(n - 1, ai));
                        if (ai != t.altIndex) {
                            t.altIndex = ai;
                            invalidate();
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                onUp(e.getPointerId(idx), true);
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = touches.size() - 1; i >= 0; i--) {
                    onUp(touches.keyAt(i), false);
                }
                break;
        }
        return true;
    }

    private void onDown(int pointerId, float x, float y) {
        final Key k = findKey(x, y);
        if (k == null) {
            return;
        }
        final Touch t = new Touch();
        t.key = k;
        touches.put(pointerId, t);
        feedback(k);

        switch (k.type) {
            case T_FUNC:
                holdDown(t);
                break;
            case T_CHAR:
                if (prefs.holdLetters && k.vk != 0 && !k.preferText) {
                    holdDown(t);
                } else {
                    popupTouch = t;
                    if (k.alts != null) {
                        handler.postAtTime(() -> showAlts(t), t, SystemClock.uptimeMillis() + LONG_PRESS_MS);
                    }
                }
                break;
            case T_SHIFT: {
                long now = SystemClock.uptimeMillis();
                if (mods[M_SHIFT] == OFF) {
                    mods[M_SHIFT] = ONCE;
                } else if (mods[M_SHIFT] == ONCE && now - lastShiftTap < 400) {
                    mods[M_SHIFT] = LOCK;
                } else {
                    mods[M_SHIFT] = OFF;
                }
                lastShiftTap = now;
                break;
            }
            case T_MOD:
                if (mods[k.mod] == OFF) {
                    mods[k.mod] = ONCE;
                    sink.sendKey(MOD_VK[k.mod], true, flags());
                } else if (mods[k.mod] == ONCE) {
                    mods[k.mod] = LOCK;
                } else {
                    mods[k.mod] = OFF;
                    sink.sendKey(MOD_VK[k.mod], false, flags());
                }
                break;
            case T_SYM:
                layer = layer == letters ? symbols : letters;
                break;
        }
        invalidate();
    }

    private void holdDown(final Touch t) {
        t.held = true;
        pressVk(t, (short) t.key.vk);
        if (t.key.repeat) {
            handler.postAtTime(new Runnable() {
                @Override
                public void run() {
                    sink.sendKey((short) t.key.vk, true, flags());
                    handler.postAtTime(this, t, SystemClock.uptimeMillis() + REPEAT_INTERVAL_MS);
                }
            }, t, SystemClock.uptimeMillis() + REPEAT_DELAY_MS);
        }
    }

    private void showAlts(Touch t) {
        Key k = t.key;
        t.altsShown = true;
        t.altIndex = 0;
        t.altCellW = Math.max(k.r.width(), 40 * density);
        float total = t.altCellW * k.alts.length;
        t.altLeft = Math.max(0, Math.min(getWidth() - total, k.r.centerX() - t.altCellW / 2));
        popupTouch = t;
        if (prefs.haptic) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        invalidate();
    }

    private void onUp(int pointerId, boolean commit) {
        Touch t = touches.get(pointerId);
        if (t == null) {
            return;
        }
        touches.remove(pointerId);
        handler.removeCallbacksAndMessages(t);
        if (popupTouch == t) {
            popupTouch = null;
        }

        if (t.held) {
            releaseVk(t, (short) t.key.vk);
        } else if (commit && t.key.type == T_CHAR) {
            if (t.altsShown) {
                commitText(t.key.alts[t.altIndex]);
            } else {
                commitKey(t.key);
            }
        }
        invalidate();
    }

    // ---------------------------------------------------------------- key sending

    private byte flags() {
        byte f = 0;
        for (int i = 0; i < 4; i++) {
            if (mods[i] != OFF) {
                f |= MOD_FLAG[i];
            }
        }
        return f;
    }

    private boolean commandModifierActive() {
        return mods[M_CTRL] != OFF || mods[M_ALT] != OFF || mods[M_WIN] != OFF;
    }

    private void pressVk(Touch t, short vk) {
        if (mods[M_SHIFT] != OFF) {
            sink.sendKey(Vk.SHIFT, true, (byte) (flags() & ~0x01));
            t.shiftWrapped = true;
        }
        sink.sendKey(vk, true, flags());
    }

    private void releaseVk(Touch t, short vk) {
        sink.sendKey(vk, false, flags());
        if (t.shiftWrapped) {
            sink.sendKey(Vk.SHIFT, false, (byte) (flags() & ~0x01));
        }
        consumeOnce();
    }

    private void consumeOnce() {
        for (int i = 0; i < 4; i++) {
            if (mods[i] == ONCE) {
                mods[i] = OFF;
                if (i != M_SHIFT) {
                    sink.sendKey(MOD_VK[i], false, flags());
                }
            }
        }
        invalidate();
    }

    private void commitKey(Key k) {
        if (k.vk != 0 && (commandModifierActive() || !k.preferText)) {
            tapVk((short) k.vk);
        } else {
            commitText(k.text);
        }
    }

    private void commitText(String s) {
        if (mods[M_SHIFT] != OFF) {
            s = s.toUpperCase(ES);
        }
        sink.sendText(s);
        consumeOnce();
    }

    /** Taps a virtual key honouring the sticky modifiers (used by the F-key row). */
    public void tapVk(short vk) {
        Touch t = new Touch();
        pressVk(t, vk);
        releaseVk(t, vk);
    }

    /** Releases every key and modifier still held on the host. */
    public void releaseAll() {
        for (int i = touches.size() - 1; i >= 0; i--) {
            onUp(touches.keyAt(i), false);
        }
        for (int i = 1; i < 4; i++) {
            if (mods[i] != OFF) {
                mods[i] = OFF;
                sink.sendKey(MOD_VK[i], false, flags());
            }
        }
        mods[M_SHIFT] = OFF;
        invalidate();
    }

    public void feedback(Key k) {
        if (prefs.haptic) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
        if (prefs.sound) {
            int fx = AudioManager.FX_KEYPRESS_STANDARD;
            if (k != null) {
                if (k.vk == Vk.BACKSPACE) {
                    fx = AudioManager.FX_KEYPRESS_DELETE;
                } else if (k.vk == Vk.ENTER) {
                    fx = AudioManager.FX_KEYPRESS_RETURN;
                } else if (k.vk == Vk.SPACE) {
                    fx = AudioManager.FX_KEYPRESS_SPACEBAR;
                }
            }
            audio.playSoundEffect(fx, 0.5f);
        }
    }

    public void feedback() {
        feedback(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }
}
