package city.subroutine.levels.traffic;

import city.subroutine.levels.traffic.api.TrafficCounter;
import city.subroutine.sandbox.testing.Check;
import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.testing.TestSuite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Уровень «Транспорт: потокобезопасный счётчик» — гонки данных и потерянные обновления. */
public final class TrafficCounterSuite implements TestSuite {

    static final int SENSOR_THREADS = 8;
    static final int EVENTS_PER_SENSOR = 50_000;

    @Override
    public List<TestCase> cases() {
        return List.of(
                TestCase.of("single-sensor", "Один датчик, три проезда", ctx -> {
                    TrafficCounter counter = ctx.newInstance(TrafficCounter.class);
                    counter.register(3);
                    counter.register(3);
                    counter.register(10);
                    Check.equal(2L, counter.ofSector(3), "ofSector(3)");
                    Check.equal(1L, counter.ofSector(10), "ofSector(10)");
                    Check.equal(0L, counter.ofSector(0), "ofSector(0)");
                    Check.equal(3L, counter.total(), "total()");
                }),

                TestCase.of("sector-bounds", "Границы номеров секторов", ctx -> {
                    TrafficCounter counter = ctx.newInstance(TrafficCounter.class);
                    counter.register(0);
                    counter.register(TrafficCounter.SECTORS - 1);
                    Check.throwsType(IllegalArgumentException.class, () -> counter.register(-1), "register(-1)");
                    Check.throwsType(IllegalArgumentException.class,
                            () -> counter.register(TrafficCounter.SECTORS), "register(SECTORS)");
                    Check.equal(2L, counter.total(), "total() после отклонённых событий");
                }),

                TestCase.of("concurrent-sectors", "8 датчиков одновременно, разные сектора", ctx -> {
                    TrafficCounter counter = ctx.newInstance(TrafficCounter.class);
                    hammer(counter, false);
                    long expected = (long) SENSOR_THREADS * EVENTS_PER_SENSOR;
                    Check.equal(expected, counter.total(), "total() — потерянные обновления = гонка данных");
                    long perSector = 0;
                    for (int s = 0; s < TrafficCounter.SECTORS; s++) {
                        perSector += counter.ofSector(s);
                    }
                    Check.equal(expected, perSector, "Сумма ofSector() по всем секторам");
                }),

                TestCase.of("concurrent-hot-sector", "8 датчиков на одном перекрёстке", ctx -> {
                    TrafficCounter counter = ctx.newInstance(TrafficCounter.class);
                    hammer(counter, true);
                    long expected = (long) SENSOR_THREADS * EVENTS_PER_SENSOR;
                    Check.equal(expected, counter.ofSector(7), "ofSector(7) — потерянные обновления");
                    Check.equal(expected, counter.total(), "total()");
                }));
    }

    /** Одновременный старт всех датчиков через защёлку — максимизирует вероятность проявления гонки. */
    private static void hammer(TrafficCounter counter, boolean sameSector) throws Exception {
        ExecutorService sensors = Executors.newFixedThreadPool(SENSOR_THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < SENSOR_THREADS; t++) {
                int sensor = t;
                futures.add(sensors.submit(() -> {
                    start.await();
                    for (int i = 0; i < EVENTS_PER_SENSOR; i++) {
                        counter.register(sameSector ? 7 : (sensor * 7 + i) % TrafficCounter.SECTORS);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            sensors.shutdownNow();
            sensors.awaitTermination(1, TimeUnit.SECONDS);
        }
    }
}
