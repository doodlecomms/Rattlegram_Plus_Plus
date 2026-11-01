package com.bi3bju.rattlegram_plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class RigSettingsTest {
    @Test
    public void freshInstallHasHamlibOff() {
        RigSettings s = RigSettings.from(new HashMap<>());
        assertFalse(s.enabled);
        assertEquals(RigSettings.NO_MODEL, s.model);
        assertFalse("a fresh install never requires CAT PTT", s.requiresCatPtt());
    }

    @Test
    public void unrelatedSettingsLeaveHamlibOff() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("callSign", "N0CALL");
        prefs.put("radioMuteSpeaker", true);
        prefs.put("encryptMessage", true);
        assertFalse(RigSettings.from(prefs).enabled);
    }

    @Test
    public void savedSettingsAreRestored() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put(RigSettings.KEY_ENABLED, true);
        prefs.put(RigSettings.KEY_MODEL, 1022);
        prefs.put(RigSettings.KEY_DEVICE, "10c4:ea60");
        prefs.put(RigSettings.KEY_BAUD, 9600);
        prefs.put(RigSettings.KEY_CAT_PTT, true);
        prefs.put(RigSettings.KEY_KEY_UP_DELAY, 250);
        RigSettings s = RigSettings.from(prefs);
        assertTrue(s.enabled);
        assertEquals(1022, s.model);
        assertEquals("10c4:ea60", s.device);
        assertEquals(9600, s.baudRate);
        assertEquals(250, s.keyUpDelayMs);
        assertTrue(s.requiresCatPtt());
    }

    @Test
    public void noConnectionStateIsEverSaved() {
        // Only configuration keys exist; nothing can restore "connected" or "keyed" after the app restarts
        for (String key : new String[]{RigSettings.KEY_ENABLED, RigSettings.KEY_MODEL, RigSettings.KEY_DEVICE,
                RigSettings.KEY_BAUD, RigSettings.KEY_CAT_PTT, RigSettings.KEY_KEY_UP_DELAY})
            assertFalse(key.toLowerCase().contains("connect") || key.toLowerCase().contains("keyed"));
    }

    @Test
    public void catPttOffOrNoModelDoesNotRequireCatPtt() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put(RigSettings.KEY_ENABLED, true);
        prefs.put(RigSettings.KEY_MODEL, 1022);
        prefs.put(RigSettings.KEY_CAT_PTT, false);
        assertFalse("PTT stays with VOX when CAT PTT is off", RigSettings.from(prefs).requiresCatPtt());
        prefs.put(RigSettings.KEY_CAT_PTT, true);
        prefs.put(RigSettings.KEY_MODEL, RigSettings.NO_MODEL);
        assertFalse(RigSettings.from(prefs).requiresCatPtt());
    }

    @Test
    public void wrongTypesFallBackToDefaults() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put(RigSettings.KEY_ENABLED, "yes");
        prefs.put(RigSettings.KEY_MODEL, "1022");
        RigSettings s = RigSettings.from(prefs);
        assertFalse(s.enabled);
        assertEquals(RigSettings.NO_MODEL, s.model);
    }

    @Test
    public void keyUpDelayIsClamped() {
        assertEquals(0, RigSettings.clampDelay(-5));
        assertEquals(RigSettings.MAX_KEY_UP_DELAY_MS, RigSettings.clampDelay(60_000));
        Map<String, Object> prefs = new HashMap<>();
        prefs.put(RigSettings.KEY_KEY_UP_DELAY, 99_999);
        assertEquals(RigSettings.MAX_KEY_UP_DELAY_MS, RigSettings.from(prefs).keyUpDelayMs);
    }

    @Test
    public void defaultBaudFitsTheModel() {
        assertEquals(4800, RigSettings.defaultBaud(4800, 38400)); // FT-857
        assertEquals(4800, RigSettings.defaultBaud(300, 19200));
        assertEquals(9600, RigSettings.defaultBaud(9600, 115200));
        assertEquals(1200, RigSettings.defaultBaud(300, 1200));
    }

    @Test
    public void modelSearchMatchesAnyPartOfTheName() {
        HamlibRig.Model ft857 = new HamlibRig.Model(1022, "Yaesu", "FT-857");
        assertTrue(RigModelAdapter.matches(ft857, "857"));
        assertTrue(RigModelAdapter.matches(ft857, "yaesu 857"));
        assertTrue(RigModelAdapter.matches(ft857, "1022"));
        assertTrue(RigModelAdapter.matches(ft857, "  FT-857 "));
        assertFalse(RigModelAdapter.matches(ft857, "icom"));
        assertFalse(RigModelAdapter.matches(ft857, "yaesu 897"));
    }
}
