package city.subroutine.sandbox.worker;

import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * Сторож числа потоков. Поток игрока нельзя остановить (Thread.stop в Java 21 не работает), поэтому
 * при превышении лимита сторож сообщает хосту и немедленно завершает процесс-песочницу.
 * Прерывания игнорируются: код игрока не имеет ссылки на сторожа, но защита не должна от этого зависеть.
 */
final class ThreadWatchdog implements Runnable {

    private static final long POLL_NANOS = 2_000_000L;

    private final JvmProbe probe;
    private final int maxPlayerThreads;
    private final IntConsumer onBreach;
    private volatile boolean stopped;
    private int baseline;

    private ThreadWatchdog(JvmProbe probe, int maxPlayerThreads, IntConsumer onBreach) {
        this.probe = probe;
        this.maxPlayerThreads = maxPlayerThreads;
        this.onBreach = onBreach;
    }

    /**
     * @param onBreach получает фактическое число потоков игрока; должен завершить процесс
     */
    static ThreadWatchdog start(JvmProbe probe, int maxPlayerThreads, IntConsumer onBreach) {
        ThreadWatchdog watchdog = new ThreadWatchdog(probe, maxPlayerThreads, onBreach);
        watchdog.baseline = probe.liveThreadCount() + 1; // + сам сторож
        Thread thread = new Thread(watchdog, "sandbox-watchdog");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        return watchdog;
    }

    @Override
    public void run() {
        while (!stopped) {
            // -1: поток теста принадлежит обвязке, а не игроку
            int playerThreads = probe.liveThreadCount() - baseline - 1;
            if (playerThreads > maxPlayerThreads) {
                onBreach.accept(playerThreads);
                return;
            }
            LockSupport.parkNanos(POLL_NANOS);
            Thread.interrupted();
        }
    }

    void stop() {
        stopped = true;
    }
}
