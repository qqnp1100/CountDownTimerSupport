package in.xiandan.countdowntimer;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CountDownTimerSupportTest {
    private ControlledTimerSupport timer;
    private RecordingListener listener;

    @Before
    public void setUp() {
        timer = new ControlledTimerSupport(3000);
        listener = new RecordingListener();
        timer.setOnCountDownTimerListener(listener);
    }

    @After
    public void tearDown() {
        timer.reset();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void concurrentStopsCancelOnlyOnce() throws Exception {
        timer.start();
        raceCancellation(timer::stop, timer::stop);

        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(TimerState.FINISH, timer.getTimerState());
        assertEquals(1, listener.cancels);
        assertEquals(0, listener.finishes);
    }

    @Test
    public void automaticFinishRacingWithStopFinishesOnlyOnce() throws Exception {
        timer.setMillisInFuture(0);
        timer.start();
        TimerTask task = timer.tasks.get(0);
        task.run();

        raceCancellation(task::run, timer::stop);

        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(TimerState.FINISH, timer.getTimerState());
        assertEquals(1, listener.finishes);
        assertEquals(0, listener.cancels);
    }

    @Test
    public void cancelledTaskCannotFinishRestartedTimer() {
        timer.setMillisInFuture(0);
        timer.start();
        TimerTask oldTask = timer.tasks.get(0);
        oldTask.run();

        timer.reset();
        timer.start();
        oldTask.run();

        assertTrue(timer.isStart());
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(listener.ticks.isEmpty());
        assertEquals(0, listener.finishes);

        timer.tasks.get(1).run();
        timer.tasks.get(1).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, listener.finishes);
    }

    @Test
    public void pauseDropsQueuedTicksAndResumeKeepsRemainingTime() {
        timer.start();
        TimerTask oldTask = timer.tasks.get(0);
        oldTask.run();
        ReflectionHelpers.setField(timer, "mMillisUntilFinished", 1500L);

        timer.pause();
        oldTask.run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(TimerState.PAUSE, timer.getTimerState());
        assertEquals(1500, timer.getMillisUntilFinished());
        assertTrue(listener.ticks.isEmpty());

        timer.resume();
        timer.tasks.get(1).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(timer.isStart());
        assertEquals(Arrays.asList(1500L), listener.ticks);
    }

    @Test
    public void resetAndRestartDropsOldQueuedTicks() {
        timer.start();
        timer.tasks.get(0).run();
        timer.reset();
        timer.start();
        timer.tasks.get(1).run();

        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(3000L), listener.ticks);
        assertEquals(0, listener.cancels);
        assertEquals(0, listener.finishes);
    }

    @Test
    public void stopAndRestartDropsOldCallbacks() {
        timer.start();
        timer.tasks.get(0).run();
        timer.stop();
        timer.start();
        timer.tasks.get(1).run();

        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(3000L), listener.ticks);
        assertEquals(0, listener.cancels);
        assertTrue(timer.isStart());
    }

    @Test
    public void queuedTicksKeepTheirOriginalRemainingTime() {
        timer.start();
        TimerTask task = timer.tasks.get(0);
        task.run();
        timer.setMillisInFuture(0);
        task.run();

        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(3000L, 0L), listener.ticks);
        assertEquals(Arrays.asList("tick", "tick", "finish"), listener.events);
        assertTrue(timer.isFinish());
    }

    @Test
    public void finishListenerCanResetAndRestart() {
        timer.setMillisInFuture(0);
        timer.setOnCountDownTimerListener(new SimpleOnCountDownTimerListener() {
            @Override
            public void onFinish() {
                timer.reset();
                timer.start();
            }
        });
        timer.start();
        timer.tasks.get(0).run();
        timer.tasks.get(0).run();

        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(timer.isStart());
        assertEquals(2, timer.tasks.size());
    }

    private void raceCancellation(Runnable firstAction, Runnable secondAction) throws Exception {
        Timer originalTimer = ReflectionHelpers.getField(timer, "mTimer");
        originalTimer.cancel();
        BlockingTimer blockingTimer = new BlockingTimer();
        ReflectionHelpers.setField(timer, "mTimer", blockingTimer);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = worker(firstAction, firstFailure);
        Thread second = worker(secondAction, secondFailure);
        try {
            first.start();
            assertTrue(blockingTimer.cancelEntered.await(5, TimeUnit.SECONDS));
            second.start();
            // The fixed implementation blocks on the lifecycle lock; the old one finishes
            // the second cancellation and clears mTimer before the first calls purge().
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (second.isAlive() && second.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(!second.isAlive() || second.getState() == Thread.State.BLOCKED);
        } finally {
            blockingTimer.continueCancel.countDown();
            first.join(5000);
            second.join(5000);
        }
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
        assertEquals(1, blockingTimer.cancelCalls.get());
    }

    private static Thread worker(final Runnable action, final AtomicReference<Throwable> failure) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }
        });
    }

    private static class BlockingTimer extends Timer {
        final CountDownLatch cancelEntered = new CountDownLatch(1);
        final CountDownLatch continueCancel = new CountDownLatch(1);
        final AtomicInteger cancelCalls = new AtomicInteger();

        BlockingTimer() {
            super(true);
            super.cancel();
        }

        @Override
        public void cancel() {
            if (cancelCalls.incrementAndGet() == 1) {
                cancelEntered.countDown();
                try {
                    if (!continueCancel.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting for concurrent cancellation");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        }
    }

    private static class ControlledTimerSupport extends CountDownTimerSupport {
        final List<TimerTask> tasks = new ArrayList<>();

        ControlledTimerSupport(long duration) {
            super(duration, 60000);
        }

        @Override
        protected TimerTask createTimerTask() {
            tasks.add(super.createTimerTask());
            // Run the captured tasks explicitly so no test depends on timer delays.
            return new TimerTask() {
                @Override
                public void run() {
                }
            };
        }
    }

    private static class RecordingListener implements OnCountDownTimerListener {
        final List<Long> ticks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int finishes;
        int cancels;

        @Override
        public void onTick(long millisUntilFinished) {
            ticks.add(millisUntilFinished);
            events.add("tick");
        }

        @Override
        public void onFinish() {
            finishes++;
            events.add("finish");
        }

        @Override
        public void onCancel() {
            cancels++;
            events.add("cancel");
        }
    }
}
