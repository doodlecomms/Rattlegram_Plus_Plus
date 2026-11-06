package com.bi3bju.rattlegram_plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/** PTT sequencing against a fake radio, with the rig thread and the main-thread clock driven by hand. */
public class PttCoordinatorTest {
    /** A radio that records PTT commands and can be told to fail. */
    static final class FakeRig implements RigBackend {
        final List<String> commands = new ArrayList<>();
        int failOn, failOff;

        @Override
        public void open(RigSettings settings, String serialPath) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public double getFrequency() {
            return 145_050_000;
        }

        @Override
        public void setFrequency(double hz) {
        }

        @Override
        public long[] getMode() {
            return new long[]{RigModes.FM, 0};
        }

        @Override
        public void setMode(long mode, long width) {
        }

        @Override
        public void setPtt(boolean on) throws RigException {
            commands.add(on ? "ON" : "OFF");
            if (on && failOn > 0) {
                --failOn;
                throw new RigException("rig busy");
            }
            if (!on && failOff > 0) {
                --failOff;
                throw new RigException("timeout");
            }
        }
    }

    /** The rig thread: runs queued commands only when told to. */
    static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runAll() {
            while (!queue.isEmpty())
                queue.poll().run();
        }
    }

    /** The main thread's postDelayed with a clock moved by hand. */
    static final class ManualClock implements PttCoordinator.Scheduler {
        static final class Task {
            final long due;
            final Runnable run;

            Task(long due, Runnable run) {
                this.due = due;
                this.run = run;
            }
        }

        final List<Task> tasks = new ArrayList<>();
        long now;

        @Override
        public Object postDelayed(Runnable task, long delayMs) {
            Task t = new Task(now + delayMs, task);
            tasks.add(t);
            return t;
        }

        @Override
        public void cancel(Object token) {
            tasks.remove(token);
        }

        /** Moves time forward, running everything that falls due, in order. */
        void advance(long ms) {
            long end = now + ms;
            while (true) {
                Task next = null;
                for (Task t : tasks)
                    if (t.due <= end && (next == null || t.due < next.due))
                        next = t;
                if (next == null)
                    break;
                tasks.remove(next);
                now = Math.max(now, next.due);
                next.run.run();
            }
            now = end;
        }
    }

    FakeRig rig;
    ManualExecutor rigThread;
    ManualClock clock;
    List<String> problems;
    PttCoordinator ptt;
    int ready;
    String failed;

    @Before
    public void setUp() {
        rig = new FakeRig();
        rigThread = new ManualExecutor();
        clock = new ManualClock();
        problems = new ArrayList<>();
        ptt = new PttCoordinator(rig, rigThread, clock, problems::add);
        ready = 0;
        failed = null;
    }

    /** Runs the rig thread and the main thread until nothing more happens within ms. */
    void settle(long ms) {
        for (int i = 0; i < 20; ++i) {
            rigThread.runAll();
            clock.advance(0);
        }
        clock.advance(ms);
        rigThread.runAll();
        clock.advance(0);
    }

    void key(long delayMs) {
        ptt.key(delayMs, () -> ++ready, message -> failed = message);
    }

    @Test
    public void audioStartsOnlyAfterPttConfirmedAndKeyUpDelay() {
        key(150);
        assertTrue(ptt.isKeying());
        assertEquals(0, ready);
        assertTrue("nothing sent before the rig thread runs", rig.commands.isEmpty());
        rigThread.runAll();
        clock.advance(0);
        assertEquals(List.of("ON"), rig.commands);
        clock.advance(149);
        assertEquals("not before the key-up delay", 0, ready);
        clock.advance(1);
        assertEquals(1, ready);
        assertTrue(ptt.isKeyed());
        assertFalse(ptt.isKeying());
    }

    @Test
    public void releaseUnkeysAfterTheRemainingAudio() {
        key(0);
        settle(0);
        ptt.release(800);
        clock.advance(799);
        rigThread.runAll();
        assertEquals(List.of("ON"), rig.commands);
        settle(1);
        assertEquals(List.of("ON", "OFF"), rig.commands);
        assertFalse(ptt.isKeyed());
        assertTrue(problems.isEmpty());
    }

    @Test
    public void failedKeyingNeverStartsAudioAndTurnsPttOff() {
        rig.failOn = 1;
        key(150);
        settle(1000);
        assertEquals(0, ready);
        assertEquals("rig busy", failed);
        assertEquals("PTT is forced off after a failed ON", List.of("ON", "OFF"), rig.commands);
        assertFalse(ptt.isKeyed());
        assertFalse(ptt.isKeying());
    }

    @Test
    public void cancellingWhileKeyingNeverStartsAudioAndEndsUnkeyed() {
        key(150);
        ptt.releaseNow(); // cancel before the ON reached the radio
        settle(1000);
        assertEquals(0, ready);
        assertEquals(List.of("ON", "OFF"), rig.commands);
        assertFalse(ptt.isKeyed());
    }

    @Test
    public void cancellingDuringKeyUpDelayNeverStartsAudio() {
        key(500);
        rigThread.runAll();
        clock.advance(100);
        ptt.releaseNow();
        settle(1000);
        assertEquals(0, ready);
        assertEquals(List.of("ON", "OFF"), rig.commands);
    }

    @Test
    public void nextTransmissionBeforeReleaseStaysKeyed() {
        key(150);
        settle(150);
        ptt.release(800);
        clock.advance(300);
        key(150); // next picture part
        settle(0);
        assertEquals("no second key-up, no unkey in between", List.of("ON"), rig.commands);
        assertEquals(2, ready);
        ptt.release(100);
        settle(100);
        assertEquals(List.of("ON", "OFF"), rig.commands);
    }

    @Test
    public void nextTransmissionAfterReleaseKeysAgainAfterTheOff() {
        key(0);
        settle(0);
        ptt.release(0);
        clock.advance(0); // OFF queued, not yet sent
        key(0);
        settle(0);
        assertEquals(List.of("ON", "OFF", "ON"), rig.commands);
        assertEquals(2, ready);
        assertTrue(ptt.isKeyed());
    }

    @Test
    public void watchdogUnkeysAStuckTransmission() {
        key(0);
        settle(0);
        clock.advance(PttCoordinator.WATCHDOG_MS - 1);
        rigThread.runAll();
        assertEquals(List.of("ON"), rig.commands);
        settle(1);
        assertEquals(List.of("ON", "OFF"), rig.commands);
        assertEquals(1, problems.size());
        assertNull("null means the watchdog acted", problems.get(0));
    }

    @Test
    public void releaseCancelsTheWatchdog() {
        key(0);
        settle(0);
        ptt.release(0);
        settle(PttCoordinator.WATCHDOG_MS * 2);
        assertEquals(List.of("ON", "OFF"), rig.commands);
        assertTrue(problems.isEmpty());
    }

    @Test
    public void unkeyIsRetriedAndAFinalFailureIsReported() {
        key(0);
        settle(0);
        rig.failOff = PttCoordinator.OFF_ATTEMPTS;
        ptt.releaseNow();
        settle(0);
        assertEquals(1 + PttCoordinator.OFF_ATTEMPTS, rig.commands.size());
        assertEquals(List.of("timeout"), problems);
    }

    @Test
    public void unkeyRetrySucceeds() {
        key(0);
        settle(0);
        rig.failOff = 1;
        ptt.releaseNow();
        settle(0);
        assertEquals(List.of("ON", "OFF", "OFF"), rig.commands);
        assertTrue(problems.isEmpty());
    }

    @Test
    public void releasingWhenNotKeyedSendsNothing() {
        ptt.releaseNow();
        ptt.release(100);
        settle(1000);
        assertTrue(rig.commands.isEmpty());
    }

    @Test
    public void repeatedTransmissionsNeverLeavePttOn() {
        for (int i = 0; i < 50; ++i) {
            key(i % 3 * 50);
            settle(i % 3 * 50);
            if (i % 7 == 3)
                ptt.releaseNow(); // a cancelled one
            else
                ptt.release(i % 5 * 100);
            settle(i % 5 * 100);
        }
        settle(PttCoordinator.WATCHDOG_MS);
        assertEquals("OFF", rig.commands.get(rig.commands.size() - 1));
        assertFalse(ptt.isKeyed());
        // ON and OFF strictly alternate: the radio is never told ON twice or OFF twice in a row
        Iterator<String> it = rig.commands.iterator();
        String last = "OFF";
        while (it.hasNext()) {
            String c = it.next();
            assertTrue("ON/OFF alternate", !c.equals(last));
            last = c;
        }
        assertTrue(problems.isEmpty());
    }
}
