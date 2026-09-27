package city.subroutine.levels.telemetry;

import city.subroutine.levels.telemetry.api.TelemetryCache;
import city.subroutine.sandbox.testing.Check;
import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.testing.TestSuite;

import java.util.List;
import java.util.OptionalDouble;

/** Уровень «Телеметрия: кэш без утечек» — ограниченный LRU-кэш, утечка памяти под нагрузкой. */
public final class TelemetrySuite implements TestSuite {

    static final int STREAM_SENSORS = 2_000_000;
    private static final int CAPACITY = TelemetryCache.CAPACITY;

    @Override
    public List<TestCase> cases() {
        return List.of(
                TestCase.of("record-latest", "Последнее показание датчика", ctx -> {
                    TelemetryCache cache = ctx.newInstance(TelemetryCache.class);
                    cache.record("T-1", 1.5);
                    Check.equal(OptionalDouble.of(1.5), cache.latest("T-1"), "latest(T-1)");
                    cache.record("T-1", 2.5);
                    Check.equal(OptionalDouble.of(2.5), cache.latest("T-1"), "latest(T-1) после обновления");
                    Check.equal(OptionalDouble.empty(), cache.latest("T-404"), "latest() неизвестного датчика");
                    Check.equal(1, cache.size(), "size()");
                }),

                TestCase.of("null-sensor", "null вместо датчика — IllegalArgumentException", ctx -> {
                    TelemetryCache cache = ctx.newInstance(TelemetryCache.class);
                    Check.throwsType(IllegalArgumentException.class, () -> cache.record(null, 1.0), "record(null, …)");
                    Check.throwsType(IllegalArgumentException.class, () -> cache.latest(null), "latest(null)");
                }),

                TestCase.of("capacity-bound", "Не больше 1000 датчиков в кэше", ctx -> {
                    TelemetryCache cache = ctx.newInstance(TelemetryCache.class);
                    for (int i = 0; i < 5_000; i++) {
                        cache.record("S-" + i, i);
                    }
                    Check.equal(CAPACITY, cache.size(), "size() после 5000 разных датчиков");
                    Check.equal(OptionalDouble.of(4_999), cache.latest("S-4999"), "latest() самого свежего датчика");
                }),

                TestCase.of("lru-latest", "latest() продлевает жизнь датчика", ctx -> {
                    TelemetryCache cache = ctx.newInstance(TelemetryCache.class);
                    fill(cache);
                    cache.latest("S-0");
                    cache.record("S-new", 42);
                    Check.isTrue(cache.latest("S-0").isPresent(), "S-0 читали недавно — его нельзя вытеснять");
                    Check.isTrue(cache.latest("S-1").isEmpty(), "Вытеснен должен быть S-1: к нему дольше всех не обращались");
                }),

                TestCase.of("lru-record", "record() тоже считается обращением", ctx -> {
                    TelemetryCache cache = ctx.newInstance(TelemetryCache.class);
                    fill(cache);
                    cache.record("S-0", -1);
                    cache.record("S-new", 42);
                    Check.equal(OptionalDouble.of(-1), cache.latest("S-0"), "latest(S-0) после обновления");
                    Check.isTrue(cache.latest("S-1").isEmpty(), "Вытеснен должен быть S-1");
                }),

                TestCase.of("sensor-storm", "Поток показаний двух миллионов датчиков", ctx -> {
                    TelemetryCache cache = ctx.newInstance(TelemetryCache.class);
                    for (int i = 0; i < STREAM_SENSORS; i++) {
                        cache.record("storm-sensor-" + i, i * 0.5);
                    }
                    Check.equal(CAPACITY, cache.size(), "size() после шторма");
                    Check.equal(OptionalDouble.of((STREAM_SENSORS - 1) * 0.5),
                            cache.latest("storm-sensor-" + (STREAM_SENSORS - 1)), "Последний датчик шторма");
                }));
    }

    private static void fill(TelemetryCache cache) {
        for (int i = 0; i < CAPACITY; i++) {
            cache.record("S-" + i, i);
        }
    }
}
