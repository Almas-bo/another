package city.subroutine.levels.support;

import city.subroutine.sandbox.testing.ThrowingRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Инструменты наборов тестов для уровней о конкурентности. */
public final class Concurrency {

    @FunctionalInterface
    public interface Worker {
        void run(int workerIndex) throws Throwable;
    }

    private Concurrency() {
    }

    /**
     * Запускает {@code threads} потоков одновременно (через стартовую защёлку — максимизирует шанс гонки)
     * и ждёт их завершения. Первое исключение любого потока пробрасывается как есть.
     * Если код игрока зависнет (deadlock), зависнет и этот метод — песочница поймает это по таймауту теста.
     */
    public static void runConcurrently(int threads, Worker worker) throws Throwable {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int index = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        worker.run(index);
                    } catch (Exception | Error e) {
                        throw e;
                    } catch (Throwable t2) {
                        throw new ExecutionException(t2);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    /** Удобная обёртка для одного действия в каждом потоке. */
    public static void runConcurrently(int threads, ThrowingRunnable action) throws Throwable {
        runConcurrently(threads, index -> action.run());
    }
}
