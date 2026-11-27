package com.bi3bju.rattlegram_plus;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Optional replay protection (Danger Zone → Replay Protection, off by default). While it is on, every
 * encrypted message carries the time it was sent, as {@code ~T:} plus seven base-36 digits of Unix seconds
 * and a newline, at the very start of the text inside the encryption (before a hidden call sign), so it
 * cannot be changed without the password. Received encrypted messages are then only accepted when that
 * time is within the allowed difference from this phone's clock, and each one only once, so a recording
 * of an encrypted message cannot be played again later to repeat it.
 *
 * <p>The time is UTC (Unix seconds), so time zones do not matter; the clocks themselves must agree. A
 * time stamp from a station is always removed before the message is shown, whether or not protection is
 * on here. Pure Java so it can be tested off the phone.
 */
final class ReplayGuard {
    enum Verdict { OK, NO_STAMP, TOO_OLD, FROM_FUTURE, REPLAYED }

    static final String PREFIX = "~T:";
    private static final int DIGITS = 7;
    /** Bytes the time stamp adds to a message before encryption. */
    static final int OVERHEAD = PREFIX.length() + DIGITS + 1;
    /** The allowed clock differences offered, in seconds; the second one is the default. */
    static final int[] WINDOWS_SECONDS = {30, 120, 600, 3600};
    static final int DEFAULT_WINDOW_SECONDS = 120;
    private static final int MAX_REMEMBERED = 500;

    boolean enabled;
    int windowSeconds = DEFAULT_WINDOW_SECONDS;

    /** Ciphertexts already accepted → when they leave the window and need no longer be remembered. */
    private final Map<String, Long> seen = new LinkedHashMap<>();

    static String stamp(String text, long nowMs) {
        String digits = Long.toString(Math.max(0, nowMs / 1000), 36);
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = digits.length(); i < DIGITS; ++i)
            sb.append('0');
        return sb.append(digits).append('\n').append(text).toString();
    }

    /** The send time in Unix seconds, or -1 if the text carries no (valid) time stamp. */
    static long stampOf(String plain) {
        if (plain == null || plain.length() < OVERHEAD || !plain.startsWith(PREFIX) || plain.charAt(OVERHEAD - 1) != '\n')
            return -1;
        String digits = plain.substring(PREFIX.length(), OVERHEAD - 1);
        for (int i = 0; i < digits.length(); ++i)
            if (Character.digit(digits.charAt(i), 36) < 0 || Character.isUpperCase(digits.charAt(i)))
                return -1;
        return Long.parseLong(digits, 36);
    }

    /** The text without its time stamp (unchanged if it has none); null stays null. */
    static String strip(String plain) {
        return stampOf(plain) < 0 ? plain : plain.substring(OVERHEAD);
    }

    /** Checks a freshly received, decrypted message; remembers it if accepted. */
    Verdict check(String plain, String ciphertext, long nowMs) {
        Verdict v = checkTime(plain, nowMs);
        if (v != Verdict.OK)
            return v;
        for (Iterator<Long> it = seen.values().iterator(); it.hasNext(); )
            if (it.next() < nowMs)
                it.remove();
        String key = ciphertext.trim();
        if (seen.containsKey(key))
            return Verdict.REPLAYED;
        seen.put(key, stampOf(plain) * 1000 + windowSeconds * 1000L);
        if (seen.size() > MAX_REMEMBERED)
            seen.remove(seen.keySet().iterator().next());
        return Verdict.OK;
    }

    /**
     * Checks only the time, against when the message was received: for messages that arrived before the
     * password was entered and are decrypted later.
     */
    Verdict checkTime(String plain, long receivedMs) {
        long sent = stampOf(plain);
        if (sent < 0)
            return Verdict.NO_STAMP;
        long ageMs = receivedMs - sent * 1000;
        if (ageMs > windowSeconds * 1000L)
            return Verdict.TOO_OLD;
        if (-ageMs > windowSeconds * 1000L)
            return Verdict.FROM_FUTURE;
        return Verdict.OK;
    }

    /** How far outside the window a stamped message is, in whole seconds (0 if inside or unstamped). */
    long secondsOutside(String plain, long receivedMs) {
        long sent = stampOf(plain);
        if (sent < 0)
            return 0;
        long off = Math.abs(receivedMs / 1000 - sent) - windowSeconds;
        return Math.max(0, off);
    }

    /** Remembers a message we sent ourselves, so a repeater's copy of it is not taken as new. */
    void rememberSent(String ciphertext, long nowMs) {
        seen.put(ciphertext.trim(), nowMs + windowSeconds * 1000L);
        if (seen.size() > MAX_REMEMBERED)
            seen.remove(seen.keySet().iterator().next());
    }

    void forget() {
        seen.clear();
    }

    /** "30 s", "2 min", "1 h" for the window choices. */
    static String describe(int seconds) {
        if (seconds % 3600 == 0)
            return String.format(Locale.US, "%d h", seconds / 3600);
        if (seconds % 60 == 0)
            return String.format(Locale.US, "%d min", seconds / 60);
        return String.format(Locale.US, "%d s", seconds);
    }
}
