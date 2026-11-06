package com.bi3bju.rattlegram_plus;

import java.util.Map;

/**
 * Saved Hamlib rig-control settings. Plain Java so the defaults can be unit tested. Only the configuration is
 * saved; whether a radio is connected never is, so nothing reconnects or keys on its own after a restart.
 */
final class RigSettings {
    static final String KEY_ENABLED = "rigEnabled";
    static final String KEY_MODEL = "rigModel";
    static final String KEY_DEVICE = "rigDevice";
    static final String KEY_BAUD = "rigBaud";
    static final String KEY_CAT_PTT = "rigCatPtt";
    static final String KEY_KEY_UP_DELAY = "rigKeyUpDelay";
    static final String KEY_DATA_MODE = "rigDataMode";

    static final int NO_MODEL = 0;
    /** Time between the radio confirming PTT and the audio starting, for its transmitter to settle. */
    static final int DEFAULT_KEY_UP_DELAY_MS = 150;
    static final int MAX_KEY_UP_DELAY_MS = 2000;
    static final int[] BAUD_RATES = {1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200};

    boolean enabled;
    int model = NO_MODEL;
    /** USB adapter as "vendorId:productId" (hex); device IDs change every time it is plugged in. */
    String device = "";
    int baudRate = 4800;
    boolean catPtt = true;
    int keyUpDelayMs = DEFAULT_KEY_UP_DELAY_MS;
    /** Put the radio in its data mode (FM to PKT on the FT-857) while connected, and back afterwards. */
    boolean dataMode;

    /** Settings from saved preferences; an empty map (a fresh install) gives Hamlib turned off. */
    static RigSettings from(Map<String, ?> prefs) {
        RigSettings s = new RigSettings();
        s.enabled = get(prefs, KEY_ENABLED, false);
        s.model = get(prefs, KEY_MODEL, NO_MODEL);
        s.device = get(prefs, KEY_DEVICE, "");
        s.baudRate = get(prefs, KEY_BAUD, 4800);
        s.catPtt = get(prefs, KEY_CAT_PTT, true);
        s.keyUpDelayMs = clampDelay(get(prefs, KEY_KEY_UP_DELAY, DEFAULT_KEY_UP_DELAY_MS));
        s.dataMode = get(prefs, KEY_DATA_MODE, false);
        return s;
    }

    static int clampDelay(int ms) {
        return Math.max(0, Math.min(MAX_KEY_UP_DELAY_MS, ms));
    }

    /** A sensible starting baud rate within what the rig model supports. */
    static int defaultBaud(int rateMin, int rateMax) {
        int rate = Math.max(rateMin, 4800);
        return rateMax > 0 ? Math.min(rate, rateMax) : rate;
    }

    /** Whether transmissions must be keyed through Hamlib: on, and set to key the radio by CAT. */
    boolean requiresCatPtt() {
        return enabled && catPtt && model != NO_MODEL;
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Map<String, ?> prefs, String key, T fallback) {
        Object value = prefs.get(key);
        if (value == null || value.getClass() != fallback.getClass())
            return fallback;
        return (T) value;
    }
}
