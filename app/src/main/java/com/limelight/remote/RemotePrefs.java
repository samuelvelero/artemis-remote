package com.limelight.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Preferences of the portrait "remote control" mode.
 */
public class RemotePrefs {
    public static final String KEY_ENABLED = "remote_mode_enabled";
    private static final String KEY_HAPTIC = "remote_haptic";
    private static final String KEY_SOUND = "remote_sound";
    private static final String KEY_POPUP = "remote_popup";
    private static final String KEY_HOLD_LETTERS = "remote_hold_letters";
    private static final String KEY_KEY_HEIGHT = "remote_key_height_dp";
    private static final String KEY_SENSITIVITY = "remote_sensitivity_x10";
    private static final String KEY_ACCEL = "remote_accel";
    private static final String KEY_DIM_SECONDS = "remote_dim_seconds";
    private static final String KEY_SHOW_VIDEO = "remote_show_video";
    private static final String KEY_PHONE_AUDIO = "remote_phone_audio";
    private static final String KEY_SHORTCUTS = "remote_shortcuts";

    public static class Shortcut {
        public final String label;
        public final short[] keys;

        public Shortcut(String label, short[] keys) {
            this.label = label;
            this.keys = keys;
        }
    }

    private final SharedPreferences prefs;

    public boolean haptic, sound, popup, holdLetters, accel, showVideo, phoneAudio;
    public int keyHeightDp, sensitivityX10, dimSeconds;
    public List<Shortcut> shortcuts;

    public RemotePrefs(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        load();
    }

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED, true);
    }

    public void load() {
        haptic = prefs.getBoolean(KEY_HAPTIC, true);
        sound = prefs.getBoolean(KEY_SOUND, true);
        popup = prefs.getBoolean(KEY_POPUP, true);
        holdLetters = prefs.getBoolean(KEY_HOLD_LETTERS, false);
        accel = prefs.getBoolean(KEY_ACCEL, true);
        showVideo = prefs.getBoolean(KEY_SHOW_VIDEO, false);
        phoneAudio = prefs.getBoolean(KEY_PHONE_AUDIO, false);
        keyHeightDp = prefs.getInt(KEY_KEY_HEIGHT, 46);
        sensitivityX10 = prefs.getInt(KEY_SENSITIVITY, 15);
        dimSeconds = prefs.getInt(KEY_DIM_SECONDS, 20);
        shortcuts = parseShortcuts(prefs.getString(KEY_SHORTCUTS, null));
    }

    public void save() {
        prefs.edit()
                .putBoolean(KEY_HAPTIC, haptic)
                .putBoolean(KEY_SOUND, sound)
                .putBoolean(KEY_POPUP, popup)
                .putBoolean(KEY_HOLD_LETTERS, holdLetters)
                .putBoolean(KEY_ACCEL, accel)
                .putBoolean(KEY_SHOW_VIDEO, showVideo)
                .putBoolean(KEY_PHONE_AUDIO, phoneAudio)
                .putInt(KEY_KEY_HEIGHT, keyHeightDp)
                .putInt(KEY_SENSITIVITY, sensitivityX10)
                .putInt(KEY_DIM_SECONDS, dimSeconds)
                .putString(KEY_SHORTCUTS, serializeShortcuts(shortcuts))
                .apply();
    }

    public static List<Shortcut> defaultShortcuts() {
        List<Shortcut> l = new ArrayList<>();
        l.add(new Shortcut("Alt+Tab", new short[]{Vk.ALT, Vk.TAB}));
        l.add(new Shortcut("Alt+F4", new short[]{Vk.ALT, Vk.F1 + 3}));
        l.add(new Shortcut("Ctrl+Shift+Esc", new short[]{Vk.CTRL, Vk.SHIFT, Vk.ESC}));
        l.add(new Shortcut("Win+Tab", new short[]{Vk.WIN, Vk.TAB}));
        l.add(new Shortcut("Copiar", new short[]{Vk.CTRL, 'C'}));
        l.add(new Shortcut("Pegar", new short[]{Vk.CTRL, 'V'}));
        l.add(new Shortcut("Cortar", new short[]{Vk.CTRL, 'X'}));
        l.add(new Shortcut("Deshacer", new short[]{Vk.CTRL, 'Z'}));
        l.add(new Shortcut("Sel. todo", new short[]{Vk.CTRL, 'A'}));
        return l;
    }

    private static List<Shortcut> parseShortcuts(String s) {
        if (s == null) {
            return defaultShortcuts();
        }
        List<Shortcut> l = new ArrayList<>();
        for (String item : s.split(";")) {
            int bar = item.lastIndexOf('|');
            if (bar <= 0) {
                continue;
            }
            try {
                String[] parts = item.substring(bar + 1).split(",");
                short[] keys = new short[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    keys[i] = Short.parseShort(parts[i]);
                }
                l.add(new Shortcut(item.substring(0, bar), keys));
            } catch (NumberFormatException ignored) {
            }
        }
        return l;
    }

    private static String serializeShortcuts(List<Shortcut> l) {
        StringBuilder sb = new StringBuilder();
        for (Shortcut sc : l) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(sc.label.replace(';', ' ').replace('|', ' ')).append('|');
            for (int i = 0; i < sc.keys.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(sc.keys[i]);
            }
        }
        return sb.toString();
    }
}
