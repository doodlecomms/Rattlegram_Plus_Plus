package com.bi3bju.rattlegram_plus;

import java.util.concurrent.Executor;

/**
 * Keys and unkeys the radio around Rattlegram's own transmissions when PTT goes through Hamlib (CAT).
 * <p>
 * All public methods are called on the main thread, and all state lives there. PTT commands run in order on
 * the single rig thread, so an "off" is never overtaken by an "on" or the other way round.
 * <ul>
 * <li>{@link #key}: PTT on, and only once the radio confirmed it and the key-up delay passed is the audio
 * started ({@code onReady}). If PTT cannot be set, nothing is sent ({@code onFailed}) and PTT is turned off.</li>
 * <li>{@link #release}: PTT off after the audio still in the output buffer has played. A transmission that
 * starts before that (the next picture part) keeps the radio keyed instead.</li>
 * <li>{@link #releaseNow}: cancel and unkey at once (errors, cancelling, closing the app).</li>
 * <li>A watchdog unkeys the radio if it stays keyed far longer than any Rattlegram transmission.</li>
 * </ul>
 */
final class PttCoordinator {
    interface Scheduler {
        /** Runs on the main thread after delayMs; the returned token cancels it. */
        Object postDelayed(Runnable task, long delayMs);

        void cancel(Object token);
    }

    interface Listener {
        /**
         * Something went wrong with PTT that the user must know about: the radio could not be unkeyed (message
         * from Hamlib), or the watchdog had to unkey it (message null).
         */
        void onPttProblem(String message);
    }

    interface FailureCallback {
        void onFailed(String message);
    }

    /** Longest the radio may stay keyed for one transmission before the watchdog unkeys it. */
    static final long WATCHDOG_MS = 30_000;
    static final int OFF_ATTEMPTS = 3;

    private final RigBackend rig;
    private final Executor rigThread;
    private final Scheduler main;
    private final Listener listener;

    private int generation;
    /** PTT is on, or "on" is the last command sent. */
    private boolean keyed;
    /** An "on" command is on its way and not confirmed yet. */
    private boolean keying;
    private Object releaseToken, watchdogToken, readyToken;

    PttCoordinator(RigBackend rig, Executor rigThread, Scheduler main, Listener listener) {
        this.rig = rig;
        this.rigThread = rigThread;
        this.main = main;
        this.listener = listener;
    }

    boolean isKeyed() {
        return keyed;
    }

    /** True from {@link #key} until the audio may start or keying failed. */
    boolean isKeying() {
        return keying || readyToken != null;
    }

    void key(long keyUpDelayMs, Runnable onReady, FailureCallback onFailed) {
        final int gen = ++generation;
        cancelRelease();
        if (keyed && !keying) {
            // Still keyed from the previous transmission (picture parts): no new key-up needed
            armWatchdog();
            onReady.run();
            return;
        }
        keying = true;
        rigThread.execute(() -> {
            String error = null;
            try {
                rig.setPtt(true);
            } catch (RigBackend.RigException e) {
                error = e.getMessage();
                try {
                    rig.setPtt(false); // in case the radio keyed anyway
                } catch (RigBackend.RigException ignored) {
                }
            }
            final String failure = error;
            main.postDelayed(() -> {
                keying = false;
                if (gen != generation)
                    return; // cancelled meanwhile; the release that cancelled it is queued after this command
                if (failure != null) {
                    keyed = false;
                    onFailed.onFailed(failure);
                    return;
                }
                keyed = true;
                armWatchdog();
                readyToken = main.postDelayed(() -> {
                    readyToken = null;
                    if (gen == generation && keyed)
                        onReady.run();
                }, Math.max(0, keyUpDelayMs));
            }, 0);
        });
    }

    /** Unkeys the radio after delayMs (the audio still to be played plus a short tail). */
    void release(long delayMs) {
        ++generation;
        cancelReady();
        cancelRelease();
        if (!keyed && !keying)
            return;
        releaseToken = main.postDelayed(() -> {
            releaseToken = null;
            sendOff();
        }, Math.max(0, delayMs));
    }

    /** Cancels any pending transmission and unkeys the radio now. */
    void releaseNow() {
        ++generation;
        cancelReady();
        cancelRelease();
        if (keyed || keying)
            sendOff();
    }

    private void sendOff() {
        cancelWatchdog();
        // The desired state is "off" from here on: a new key() sends a fresh "on", queued after this "off"
        keyed = false;
        rigThread.execute(() -> {
            String error = null;
            for (int attempt = 0; attempt < OFF_ATTEMPTS; ++attempt) {
                try {
                    rig.setPtt(false);
                    error = null;
                    break;
                } catch (RigBackend.RigException e) {
                    error = e.getMessage();
                }
            }
            if (error != null) {
                final String failure = error;
                main.postDelayed(() -> listener.onPttProblem(failure), 0);
            }
        });
    }

    private void armWatchdog() {
        cancelWatchdog();
        watchdogToken = main.postDelayed(() -> {
            watchdogToken = null;
            if (keyed) {
                listener.onPttProblem(null);
                releaseNow();
            }
        }, WATCHDOG_MS);
    }

    private void cancelWatchdog() {
        if (watchdogToken != null) {
            main.cancel(watchdogToken);
            watchdogToken = null;
        }
    }

    private void cancelRelease() {
        if (releaseToken != null) {
            main.cancel(releaseToken);
            releaseToken = null;
        }
    }

    private void cancelReady() {
        if (readyToken != null) {
            main.cancel(readyToken);
            readyToken = null;
        }
    }
}
