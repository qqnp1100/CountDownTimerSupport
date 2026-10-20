package in.xiandan.countdowntimer;

import android.os.Handler;

import java.util.Timer;
import java.util.TimerTask;

/**
 * author  xiandanin
 * created 2017/5/16 11:32
 */
public class CountDownTimerSupport implements ITimerSupport {
    private Timer mTimer;

    private Handler mHandler;

    /**
     * 倒计时时间
     */
    private long mMillisInFuture;

    /**
     * 间隔时间
     */
    private long mCountDownInterval;
    /**
     * 倒计时剩余时间
     */
    private long mMillisUntilFinished;

    private OnCountDownTimerListener mOnCountDownTimerListener;

    private TimerState mTimerState = TimerState.FINISH;

    // 每次启动或主动取消后递增，使旧任务和已入队的回调失效。
    private long mTimerGeneration;

    @Deprecated
    public CountDownTimerSupport() {
        this.mHandler = new Handler();
    }

    public CountDownTimerSupport(long millisInFuture, long countDownInterval) {
        this.setMillisInFuture(millisInFuture);
        this.setCountDownInterval(countDownInterval);
        this.mHandler = new Handler();
    }

    @Override
    public synchronized void start() {
        //防止重复启动 重新启动要先reset再start
        if (mTimer == null && mTimerState != TimerState.START) {
            mTimerGeneration++;
            mTimer = new Timer();
            mTimer.scheduleAtFixedRate(createTimerTask(), 0, mCountDownInterval);
            mTimerState = TimerState.START;
        }
    }

    @Override
    public synchronized void pause() {
        if (mTimer != null && mTimerState == TimerState.START) {
            cancelTimer();
            mTimerGeneration++;
            mTimerState = TimerState.PAUSE;
        }
    }

    @Override
    public synchronized void resume() {
        if (mTimerState == TimerState.PAUSE) {
            start();
        }
    }

    @Override
    public void stop() {
        stopTimer(true);
    }

    @Override
    public synchronized void reset() {
        cancelTimer();
        mTimerGeneration++;
        mMillisUntilFinished = mMillisInFuture;
        mTimerState = TimerState.FINISH;
    }

    private synchronized void stopTimer(final boolean cancel) {
        if (mTimer != null) {
            cancelTimer();
            if (cancel) {
                mTimerGeneration++;
            }
            mMillisUntilFinished = mMillisInFuture;
            mTimerState = TimerState.FINISH;

            final long generation = mTimerGeneration;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (CountDownTimerSupport.this) {
                        if (generation == mTimerGeneration && mOnCountDownTimerListener != null) {
                            if (cancel) {
                                mOnCountDownTimerListener.onCancel();
                            } else {
                                mOnCountDownTimerListener.onFinish();
                            }
                        }
                    }
                }
            });
        }
    }

    private void cancelTimer() {
        // 调用方持有实例锁。cancel() 已清空任务队列，无需再调用 purge()。
        Timer timer = mTimer;
        mTimer = null;
        if (timer != null) {
            timer.cancel();
        }
    }

    public synchronized boolean isStart() {
        return mTimerState == TimerState.START;
    }

    public synchronized boolean isFinish() {
        return mTimerState == TimerState.FINISH;
    }

    /**
     * @param millisInFuture
     * @deprecated 使用构造方法
     */
    @Deprecated
    public synchronized void setMillisInFuture(long millisInFuture) {
        this.mMillisInFuture = millisInFuture;
        this.mMillisUntilFinished = mMillisInFuture;
    }

    /**
     * @param countDownInterval
     * @deprecated 使用构造方法
     */
    @Deprecated
    public synchronized void setCountDownInterval(long countDownInterval) {
        this.mCountDownInterval = countDownInterval;
    }

    public synchronized void setOnCountDownTimerListener(OnCountDownTimerListener listener) {
        this.mOnCountDownTimerListener = listener;
    }

    public synchronized long getMillisUntilFinished() {
        return mMillisUntilFinished;
    }

    public synchronized TimerState getTimerState() {
        return mTimerState;
    }

    private void postTick(final long millisUntilFinished, final long generation) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (CountDownTimerSupport.this) {
                    if (generation == mTimerGeneration && mOnCountDownTimerListener != null) {
                        mOnCountDownTimerListener.onTick(millisUntilFinished);
                    }
                }
            }
        });
    }

    protected synchronized TimerTask createTimerTask() {
        final long generation = mTimerGeneration;
        return new TimerTask() {
            private long startTime = -1;

            @Override
            public void run() {
                synchronized (CountDownTimerSupport.this) {
                    // cancel() 不会中断正在执行的任务，旧任务取得锁后必须再次检查。
                    if (generation != mTimerGeneration || mTimer == null || mTimerState != TimerState.START) {
                        return;
                    }
                    if (startTime < 0) {
                        //第一次回调 记录开始时间
                        startTime = scheduledExecutionTime() - (mMillisInFuture - mMillisUntilFinished);
                        postTick(mMillisUntilFinished, generation);
                    } else {
                        //剩余时间
                        mMillisUntilFinished = mMillisInFuture - (scheduledExecutionTime() - startTime);
                        postTick(mMillisUntilFinished, generation);

                        if (mMillisUntilFinished <= 0) {
                            //如果没有剩余时间 就停止
                            stopTimer(false);
                        }
                    }
                }
            }
        };
    }

}
