package com.limelight.remote;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Portrait remote-control overlay: top bar, trackpad (optionally with the stream visible
 * behind it), shortcut bar, ESC/F-key row and the PC keyboard.
 */
public class RemotePanel extends LinearLayout implements RemoteKeyboardView.Sink, TrackpadView.Sink {
    private static final int C_BG = 0xFF202124;
    private static final int C_CHIP = 0xFF2F3237;
    private static final int C_ACCENT = 0xFF8AB4F8;
    private static final int C_TEXT = 0xFFE8EAED;

    private final Game game;
    private final RemotePrefs prefs;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final LinearLayout topBar;
    private final TrackpadView trackpad;
    private final LinearLayout shortcutRow;
    private final RemoteKeyboardView keyboard;
    private final TextView videoChip, audioChip;

    private boolean dimmed;
    private final Runnable dimRunnable = this::dim;

    public RemotePanel(Game game) {
        super(game);
        this.game = game;
        prefs = new RemotePrefs(game);
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);

        // ---- top bar
        topBar = new LinearLayout(game);
        topBar.setOrientation(HORIZONTAL);
        topBar.setBackgroundColor(C_BG);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        addChip(topBar, "☰", 0, v -> game.showGameMenu(null));
        videoChip = addChip(topBar, "Video", 1, v -> {
            prefs.showVideo = !prefs.showVideo;
            prefs.save();
            applyMode();
        });
        audioChip = addChip(topBar, "Audio", 1, v -> {
            prefs.phoneAudio = !prefs.phoneAudio;
            prefs.save();
            applyAudio();
            toast(prefs.phoneAudio ? "Audio del PC en el celular" : "Audio del celular silenciado");
        });
        addChip(topBar, "Texto", 1, v -> showSendTextDialog());
        addChip(topBar, "PC", 0, this::showPowerMenu);
        addChip(topBar, "⚙", 0, v -> showSettingsDialog());
        addView(topBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ---- trackpad
        trackpad = new TrackpadView(game, prefs, this);
        addView(trackpad, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- keyboard (created before the rows that use it)
        keyboard = new RemoteKeyboardView(game, prefs, this);

        // ---- shortcut bar
        shortcutRow = new LinearLayout(game);
        addView(scroller(shortcutRow), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        rebuildShortcuts();

        // ---- navigation keys (Esc and F1–F12 are the keyboard's top row)
        LinearLayout fRow = new LinearLayout(game);
        addFKey(fRow, "Supr", Vk.DELETE);
        addFKey(fRow, "Ins", Vk.INSERT);
        addFKey(fRow, "Inicio", Vk.HOME);
        addFKey(fRow, "Fin", Vk.END);
        addFKey(fRow, "RePág", Vk.PAGE_UP);
        addFKey(fRow, "AvPág", Vk.PAGE_DOWN);
        addFKey(fRow, "ImpPt", Vk.PRINT_SCREEN);
        addView(scroller(fRow), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addView(keyboard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Layout below/above system bars and cutouts
        setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            topBar.setPadding(dp(4), dp(4) + top, dp(4), dp(4));
            keyboard.setPadding(0, 0, 0, 0);
            setPadding(0, 0, 0, bottom);
            return insets;
        });
        setBackgroundColor(0x00000000);

        topBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) {
                // Never relayout the stream from inside a layout pass
                post(this::applyMode);
            }
        });

        applyMode();
        applyAudio();
    }

    // ------------------------------------------------------------------ helpers

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(game, s, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable chipBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        return d;
    }

    private TextView makeChip(String label) {
        TextView tv = new TextView(game);
        tv.setText(label);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setMinWidth(dp(44));
        tv.setPadding(dp(10), 0, dp(10), 0);
        tv.setBackground(chipBg(C_CHIP));
        return tv;
    }

    private TextView addChip(LinearLayout parent, String label, float weight, OnClickListener l) {
        TextView tv = makeChip(label);
        tv.setOnClickListener(v -> {
            keyboard.feedback();
            l.onClick(v);
        });
        LayoutParams lp = new LayoutParams(weight > 0 ? 0 : LayoutParams.WRAP_CONTENT, dp(36), weight);
        lp.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(tv, lp);
        return tv;
    }

    private HorizontalScrollView scroller(LinearLayout row) {
        row.setPadding(dp(3), dp(3), dp(3), dp(1));
        HorizontalScrollView sv = new HorizontalScrollView(game);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setBackgroundColor(C_BG);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private void addFKey(LinearLayout row, String label, short vk) {
        addChip(row, label, 0, v -> {
            if (connected()) {
                keyboard.tapVk(vk);
            }
        });
    }

    private void rebuildShortcuts() {
        shortcutRow.removeAllViews();
        for (RemotePrefs.Shortcut sc : prefs.shortcuts) {
            TextView chip = addChip(shortcutRow, sc.label, 0, v -> {
                if (connected()) {
                    game.sendKeys(sc.keys);
                }
            });
            chip.setTextColor(C_ACCENT);
        }
        TextView add = addChip(shortcutRow, "+", 0, v -> showShortcutEditor());
        add.setTextColor(0xFF9AA0A6);
    }

    private boolean connected() {
        return game.conn != null && game.connected;
    }

    // ------------------------------------------------------------------ modes

    private void applyMode() {
        trackpad.setOpaque(!prefs.showVideo);
        videoChip.setBackground(chipBg(prefs.showVideo ? C_ACCENT : C_CHIP));
        videoChip.setTextColor(prefs.showVideo ? C_BG : C_TEXT);
        game.setRemoteStreamTop(topBar.getHeight());
        resetDimTimer();
    }

    private void applyAudio() {
        AndroidAudioRenderer.muted = !prefs.phoneAudio;
        audioChip.setBackground(chipBg(prefs.phoneAudio ? C_ACCENT : C_CHIP));
        audioChip.setTextColor(prefs.phoneAudio ? C_BG : C_TEXT);
    }

    /** Called by Game when the panel is shown/hidden (e.g. rotation to landscape). */
    public void setActive(boolean active) {
        setVisibility(active ? VISIBLE : GONE);
        if (active) {
            applyAudio();
            resetDimTimer();
        } else {
            keyboard.releaseAll();
            AndroidAudioRenderer.muted = false;
            handler.removeCallbacks(dimRunnable);
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
            dimmed = false;
        }
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        AndroidAudioRenderer.muted = false;
    }

    // ------------------------------------------------------------------ dimming

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            boolean wasDimmed = dimmed;
            resetDimTimer();
            if (wasDimmed) {
                // The first touch only wakes the screen up
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void resetDimTimer() {
        handler.removeCallbacks(dimRunnable);
        if (dimmed) {
            dimmed = false;
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        }
        if (getVisibility() == VISIBLE && !prefs.showVideo && prefs.dimSeconds > 0) {
            handler.postDelayed(dimRunnable, prefs.dimSeconds * 1000L);
        }
    }

    private void dim() {
        dimmed = true;
        setBrightness(0.01f);
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams lp = game.getWindow().getAttributes();
        lp.screenBrightness = value;
        game.getWindow().setAttributes(lp);
    }

    // ------------------------------------------------------------------ sinks

    @Override
    public void sendKey(short vk, boolean down, byte modifiers) {
        if (connected()) {
            game.conn.sendKeyboardInput(vk, down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP,
                    modifiers, (byte) 0);
        }
    }

    @Override
    public void sendText(String text) {
        if (connected()) {
            game.conn.sendUtf8Text(text);
        }
    }

    @Override
    public void move(int dx, int dy) {
        if (connected()) {
            game.conn.sendMouseMove((short) dx, (short) dy);
        }
    }

    @Override
    public void button(int button, boolean down) {
        if (!connected()) {
            return;
        }
        if (down) {
            game.conn.sendMouseButtonDown((byte) button);
        } else {
            game.conn.sendMouseButtonUp((byte) button);
        }
    }

    @Override
    public void scroll(int vertical, int horizontal) {
        if (!connected()) {
            return;
        }
        if (vertical != 0) {
            game.conn.sendMouseHighResScroll((short) vertical);
        }
        if (horizontal != 0) {
            game.conn.sendMouseHighResHScroll((short) horizontal);
        }
    }

    private void tap(short vk) {
        sendKey(vk, true, (byte) 0);
        sendKey(vk, false, (byte) 0);
    }

    // ------------------------------------------------------------------ send text

    private void showSendTextDialog() {
        final EditText input = new EditText(game);
        input.setHint("Escribe, dicta o pega aquí");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);

        final CheckBox enter = new CheckBox(game);
        enter.setText("Pulsar Enter al final");

        LinearLayout box = new LinearLayout(game);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        box.addView(enter);

        AlertDialog dialog = new AlertDialog.Builder(game)
                .setTitle("Enviar texto al PC")
                .setView(box)
                .setPositiveButton("Enviar", (d, w) -> typeText(input.getText().toString(), enter.isChecked()))
                .setNeutralButton("Pegar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) game.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = cm != null ? cm.getPrimaryClip() : null;
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence s = clip.getItemAt(0).coerceToText(game);
                    input.getText().insert(Math.max(0, input.getSelectionStart()), s);
                }
            });
            input.requestFocus();
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    /** Types text on the host; line breaks become Enter key presses. */
    private void typeText(String text, boolean enterAtEnd) {
        if (!connected() || text.isEmpty()) {
            return;
        }
        final List<String> steps = new ArrayList<>();
        String[] lines = text.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int p = 0;
            while (p < line.length()) {
                int end = Math.min(line.length(), p + 200);
                if (end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))) {
                    end--;
                }
                steps.add(line.substring(p, end));
                p = end;
            }
            if (i < lines.length - 1) {
                steps.add("\n");
            }
        }
        if (enterAtEnd) {
            steps.add("\n");
        }
        handler.post(new Runnable() {
            int i = 0;

            @Override
            public void run() {
                if (i >= steps.size() || !connected()) {
                    return;
                }
                String s = steps.get(i++);
                if (s.equals("\n")) {
                    tap(Vk.ENTER);
                } else if (!s.isEmpty()) {
                    game.conn.sendUtf8Text(s);
                }
                handler.postDelayed(this, 30);
            }
        });
    }

    // ------------------------------------------------------------------ power menu

    private void showPowerMenu(View anchor) {
        PopupMenu menu = new PopupMenu(game, anchor);
        menu.getMenu().add("Reiniciar PC");
        menu.setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(game)
                    .setTitle("¿Reiniciar el PC?")
                    .setMessage("Se abrirá Ejecutar (Win+R) en el PC y se lanzará «shutdown /r /t 0». "
                            + "Se cerrará todo lo que esté abierto y la conexión se cortará.")
                    .setPositiveButton("Reiniciar", (d, w) -> rebootHost())
                    .setNegativeButton("Cancelar", null)
                    .show();
            return true;
        });
        menu.show();
    }

    private void rebootHost() {
        if (!connected()) {
            toast("No hay conexión con el PC");
            return;
        }
        keyboard.releaseAll();
        game.sendKeys(new short[]{Vk.WIN, 'R'});
        handler.postDelayed(() -> sendText("shutdown /r /t 0"), 900);
        handler.postDelayed(() -> tap(Vk.ENTER), 1400);
        toast("Reiniciando el PC…");
    }

    // ------------------------------------------------------------------ settings

    private interface IntSetter {
        void set(int v);
    }

    private interface BoolSetter {
        void set(boolean v);
    }

    private void addSwitch(LinearLayout box, String label, boolean value, BoolSetter setter) {
        Switch sw = new Switch(game);
        sw.setText(label);
        sw.setChecked(value);
        sw.setPadding(0, dp(8), 0, dp(8));
        sw.setOnCheckedChangeListener((b, checked) -> {
            setter.set(checked);
            prefs.save();
            keyboard.applyPrefs();
        });
        box.addView(sw);
    }

    private interface Formatter {
        String format(int v);
    }

    private void addSeek(LinearLayout box, String label, int min, int max, int value,
                         Formatter fmt, IntSetter setter) {
        final TextView tv = new TextView(game);
        tv.setPadding(0, dp(10), 0, 0);
        tv.setText(label + ": " + fmt.format(value));
        SeekBar sb = new SeekBar(game);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = progress + min;
                tv.setText(label + ": " + fmt.format(v));
                setter.set(v);
                keyboard.applyPrefs();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.save();
                resetDimTimer();
            }
        });
        box.addView(tv);
        box.addView(sb);
    }

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(game);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), dp(8));

        addSwitch(box, "Vibración al pulsar", prefs.haptic, v -> prefs.haptic = v);
        addSwitch(box, "Sonido de clic", prefs.sound, v -> prefs.sound = v);
        addSwitch(box, "Popup de la tecla", prefs.popup, v -> prefs.popup = v);
        addSwitch(box, "Mantener letras pulsadas (modo juego, sin tildes por pulsación larga)",
                prefs.holdLetters, v -> prefs.holdLetters = v);
        addSeek(box, "Altura de las teclas", 36, 60, prefs.keyHeightDp,
                v -> v + " dp", v -> prefs.keyHeightDp = v);
        addSeek(box, "Sensibilidad del trackpad", 5, 50, prefs.sensitivityX10,
                v -> String.format("%.1fx", v / 10f), v -> prefs.sensitivityX10 = v);
        addSwitch(box, "Aceleración del cursor", prefs.accel, v -> prefs.accel = v);
        addSeek(box, "Atenuar pantalla tras", 0, 120, prefs.dimSeconds,
                v -> v == 0 ? "nunca" : v + " s", v -> prefs.dimSeconds = v);

        ScrollView sv = new ScrollView(game);
        sv.addView(box);
        new AlertDialog.Builder(game)
                .setTitle("Control remoto")
                .setView(sv)
                .setPositiveButton("Listo", null)
                .setNeutralButton("Editar atajos", (d, w) -> showShortcutEditor())
                .show();
    }

    // ------------------------------------------------------------------ shortcut editor

    private void showShortcutEditor() {
        String[] items = new String[prefs.shortcuts.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = prefs.shortcuts.get(i).label;
        }
        new AlertDialog.Builder(game)
                .setTitle("Atajos (toca uno para borrarlo)")
                .setItems(items, (d, which) -> new AlertDialog.Builder(game)
                        .setMessage("¿Borrar el atajo «" + items[which] + "»?")
                        .setPositiveButton("Borrar", (d2, w2) -> {
                            prefs.shortcuts.remove(which);
                            prefs.save();
                            rebuildShortcuts();
                            showShortcutEditor();
                        })
                        .setNegativeButton("Cancelar", (d2, w2) -> showShortcutEditor())
                        .show())
                .setPositiveButton("Añadir", (d, w) -> showAddShortcutDialog())
                .setNeutralButton("Restaurar", (d, w) -> {
                    prefs.shortcuts = RemotePrefs.defaultShortcuts();
                    prefs.save();
                    rebuildShortcuts();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void showAddShortcutDialog() {
        final List<String> names = new ArrayList<>();
        final List<Short> codes = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            names.add(String.valueOf(c));
            codes.add((short) c);
        }
        for (char c = '0'; c <= '9'; c++) {
            names.add(String.valueOf(c));
            codes.add((short) c);
        }
        for (int i = 0; i < 12; i++) {
            names.add("F" + (i + 1));
            codes.add((short) (Vk.F1 + i));
        }
        String[] extraNames = {"Esc", "Tab", "Enter", "Espacio", "Retroceso", "Supr", "Insert", "Inicio", "Fin",
                "RePág", "AvPág", "Izquierda", "Arriba", "Derecha", "Abajo", "ImpPt", "(ninguna)"};
        short[] extraCodes = {Vk.ESC, Vk.TAB, Vk.ENTER, Vk.SPACE, Vk.BACKSPACE, Vk.DELETE, Vk.INSERT, Vk.HOME,
                Vk.END, Vk.PAGE_UP, Vk.PAGE_DOWN, Vk.LEFT, Vk.UP, Vk.RIGHT, Vk.DOWN, Vk.PRINT_SCREEN, 0};
        for (int i = 0; i < extraNames.length; i++) {
            names.add(extraNames[i]);
            codes.add(extraCodes[i]);
        }

        LinearLayout box = new LinearLayout(game);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);

        final EditText name = new EditText(game);
        name.setHint("Nombre (opcional)");
        name.setSingleLine(true);
        box.addView(name);

        final String[] modNames = {"Ctrl", "Shift", "Alt", "Win"};
        final short[] modCodes = {Vk.CTRL, Vk.SHIFT, Vk.ALT, Vk.WIN};
        final CheckBox[] checks = new CheckBox[4];
        LinearLayout modRow = new LinearLayout(game);
        for (int i = 0; i < 4; i++) {
            checks[i] = new CheckBox(game);
            checks[i].setText(modNames[i]);
            modRow.addView(checks[i], new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(modRow);

        final Spinner spinner = new Spinner(game);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(game, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        box.addView(spinner);

        new AlertDialog.Builder(game)
                .setTitle("Nuevo atajo")
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    List<Short> keys = new ArrayList<>();
                    StringBuilder auto = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        if (checks[i].isChecked()) {
                            keys.add(modCodes[i]);
                            auto.append(modNames[i]).append('+');
                        }
                    }
                    int sel = spinner.getSelectedItemPosition();
                    short code = codes.get(sel);
                    if (code != 0) {
                        keys.add(code);
                        auto.append(names.get(sel));
                    } else if (auto.length() > 0) {
                        auto.setLength(auto.length() - 1);
                    }
                    if (keys.isEmpty()) {
                        toast("Elige al menos una tecla");
                        return;
                    }
                    short[] arr = new short[keys.size()];
                    for (int i = 0; i < arr.length; i++) {
                        arr[i] = keys.get(i);
                    }
                    String label = name.getText().toString().trim();
                    prefs.shortcuts.add(new RemotePrefs.Shortcut(label.isEmpty() ? auto.toString() : label, arr));
                    prefs.save();
                    rebuildShortcuts();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
