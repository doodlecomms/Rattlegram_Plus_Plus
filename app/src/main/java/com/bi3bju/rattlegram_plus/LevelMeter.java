package com.bi3bju.rattlegram_plus;

/**
 * Input level meter for setting up audio levels (Decoder Settings → Show Input Level): the peak level of
 * the received audio in dBFS (0 dB is the loudest a sample can be), held briefly and falling back slowly
 * so short peaks stay readable. Pure Java so it can be tested off the phone.
 */
final class LevelMeter {
    enum Zone { SILENT, LOW, GOOD, LOUD, CLIPPING }

    /** Shown for digital silence (all samples zero). */
    static final double FLOOR_DB = -90;
    /** How fast the held peak falls, in dB per second. */
    static final double FALL_DB_PER_SECOND = 20;

    private double heldDb = FLOOR_DB;
    private boolean clipped;

    /**
     * Adds one chunk of 16-bit samples (interleaved channels count as samples too).
     *
     * @param seconds how long the chunk lasts, for the fall-back of the held peak
     */
    void add(short[] samples, int count, double seconds) {
        int peak = 0;
        int n = Math.min(count, samples.length);
        for (int i = 0; i < n; ++i) {
            int v = Math.abs((int) samples[i]);
            if (v > peak)
                peak = v;
        }
        double db = toDb(peak);
        double fallen = heldDb - FALL_DB_PER_SECOND * seconds;
        heldDb = Math.max(Math.max(db, fallen), FLOOR_DB);
        // A clipped chunk keeps the meter red until the peak has fallen back below "loud"
        if (db >= CLIP_DB)
            clipped = true;
        else if (heldDb < LOUD_DB)
            clipped = false;
    }

    double levelDb() {
        return heldDb;
    }

    Zone zone() {
        if (clipped)
            return Zone.CLIPPING;
        return zoneFor(heldDb);
    }

    /** Fraction of the meter bar to fill, from FLOOR_DB (0) to 0 dBFS (1). */
    double fill() {
        return Math.max(0, Math.min(1, (heldDb - FLOOR_DB) / -FLOOR_DB));
    }

    void reset() {
        heldDb = FLOOR_DB;
        clipped = false;
    }

    /** At or above this the converter is (nearly) at full scale: the waveform is cut off. */
    static final double CLIP_DB = -0.5;
    /** Louder than this leaves little headroom. */
    static final double LOUD_DB = -6;
    /** Quieter than this the received audio is mostly converter noise. */
    static final double LOW_DB = -40;
    /** Quieter than this there is next to nothing coming in. */
    static final double SILENT_DB = -60;

    static Zone zoneFor(double db) {
        if (db >= CLIP_DB)
            return Zone.CLIPPING;
        if (db >= LOUD_DB)
            return Zone.LOUD;
        if (db >= LOW_DB)
            return Zone.GOOD;
        if (db >= SILENT_DB)
            return Zone.LOW;
        return Zone.SILENT;
    }

    static double toDb(int peak) {
        if (peak <= 0)
            return FLOOR_DB;
        return Math.max(FLOOR_DB, 20 * Math.log10(peak / 32768.0));
    }
}
