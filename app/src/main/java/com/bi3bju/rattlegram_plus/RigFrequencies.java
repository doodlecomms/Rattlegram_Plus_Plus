package com.bi3bju.rattlegram_plus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Frequency helpers for the Tune window: display format, tuning steps and the saved-frequency list. Plain Java
 * so they can be unit tested; nothing here talks to the radio.
 */
final class RigFrequencies {
    /** Tuning steps offered, in Hz. */
    static final int[] STEPS_HZ = {5_000, 6_250, 10_000, 12_500, 25_000, 100_000, 1_000_000};
    static final int DEFAULT_STEP_HZ = 5_000;
    static final int MAX_SAVED = 30;
    private static final int MAX_NAME = 24;

    /** One saved frequency, with an optional name. */
    static final class Saved {
        final long hz;
        final String name;

        Saved(long hz, String name) {
            this.hz = hz;
            this.name = name == null ? "" : name;
        }
    }

    private RigFrequencies() {}

    /** "146.550 MHz": at least three decimals, more only when needed (145.5125 MHz). */
    static String format(double hz) {
        String s = String.format(Locale.US, "%.6f", hz / 1e6);
        int keep = s.indexOf('.') + 4;
        int end = s.length();
        while (end > keep && s.charAt(end - 1) == '0')
            --end;
        return s.substring(0, end) + " MHz";
    }

    /**
     * The next frequency up (direction > 0) or down from hz on a grid of stepHz. A frequency between steps goes
     * to the nearest grid line in that direction first.
     */
    static long step(double hz, int stepHz, int direction) {
        double units = hz / stepHz;
        long n = direction > 0 ? (long) Math.floor(units + 1e-6) + 1 : (long) Math.ceil(units - 1e-6) - 1;
        return Math.max(0, n * (long) stepHz);
    }

    /** Frequency typed in MHz, in Hz, or -1 if it is not a sensible radio frequency. */
    static long parseMhz(String text) {
        if (text == null)
            return -1;
        try {
            double mhz = Double.parseDouble(text.trim().replace(',', '.'));
            if (!(mhz > 0.01 && mhz < 100_000))
                return -1;
            return Math.round(mhz * 1e6);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static boolean isStep(int hz) {
        for (int s : STEPS_HZ)
            if (s == hz)
                return true;
        return false;
    }

    /** Saved list from its stored text, one "hz<TAB>name" per line; bad lines are skipped. */
    static List<Saved> parseSaved(String stored) {
        List<Saved> list = new ArrayList<>();
        if (stored == null || stored.isEmpty())
            return list;
        for (String line : stored.split("\n")) {
            String[] f = line.split("\t", 2);
            try {
                long hz = Long.parseLong(f[0].trim());
                if (hz > 0 && list.size() < MAX_SAVED)
                    list.add(new Saved(hz, f.length > 1 ? f[1] : ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return list;
    }

    static String serializeSaved(List<Saved> list) {
        StringBuilder sb = new StringBuilder();
        for (Saved s : list) {
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(s.hz).append('\t').append(s.name);
        }
        return sb.toString();
    }

    /** A saved-list name without tabs or line breaks, and not too long. */
    static String cleanName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\t\\r\\n]+", " ").trim();
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    /** Adds or renames hz in the list (one entry per frequency), keeping it sorted and short. */
    static List<Saved> withSaved(List<Saved> list, long hz, String name) {
        List<Saved> out = new ArrayList<>();
        for (Saved s : list)
            if (s.hz != hz)
                out.add(s);
        out.add(new Saved(hz, cleanName(name)));
        out.sort((a, b) -> Long.compare(a.hz, b.hz));
        while (out.size() > MAX_SAVED)
            out.remove(out.size() - 1);
        return out;
    }

    static List<Saved> without(List<Saved> list, long hz) {
        List<Saved> out = new ArrayList<>();
        for (Saved s : list)
            if (s.hz != hz)
                out.add(s);
        return out;
    }
}
