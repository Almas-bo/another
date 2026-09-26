package city.subroutine.levels.powergrid;

import city.subroutine.sandbox.testing.Check;
import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.testing.TestSuite;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.List;

/**
 * Уровень «Энергосеть: суммарная нагрузка».
 *
 * <p>Контракт: {@code public static long totalLoad(int[] sectorLoads)} в классе {@code city.player.PowerGrid}.
 * <ul>
 *   <li>{@code null} → {@link IllegalArgumentException};</li>
 *   <li>отрицательная нагрузка сектора → {@link IllegalArgumentException};</li>
 *   <li>сумма не должна переполняться (значения до {@code Integer.MAX_VALUE});</li>
 *   <li>входной массив не изменяется;</li>
 *   <li>метод вызывается диспетчером в горячем цикле — без лишних аллокаций.</li>
 * </ul>
 */
public final class PowerGridSuite implements TestSuite {

    /** Бюджет на 5000 вызовов: цикл без аллокаций укладывается с запасом, стрим/boxing — нет. */
    static final long HOT_LOOP_ALLOCATION_BUDGET = 16 * 1024;
    static final int HOT_LOOP_CALLS = 5_000;

    @Override
    public List<TestCase> cases() {
        return List.of(
                TestCase.of("empty-grid", "Пустая сеть потребляет 0", ctx ->
                        Check.equal(0L, (long) ctx.invoke((Object) new int[0]), "totalLoad([])")),

                TestCase.of("basic-sum", "Сумма нагрузки трёх секторов", ctx ->
                        Check.equal(60L, (long) ctx.invoke((Object) new int[] {10, 20, 30}), "totalLoad([10, 20, 30])")),

                TestCase.of("int-overflow", "Пиковая нагрузка не переполняет счётчик", ctx ->
                        Check.equal(2L * Integer.MAX_VALUE + 5,
                                (long) ctx.invoke((Object) new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, 5}),
                                "Сумма двух секторов по Integer.MAX_VALUE")),

                TestCase.of("null-grid", "Отключённая сеть (null) — IllegalArgumentException", ctx ->
                        Check.throwsType(IllegalArgumentException.class, () -> ctx.invoke((Object) null),
                                "totalLoad(null)")),

                TestCase.of("negative-load", "Отрицательная нагрузка — IllegalArgumentException", ctx ->
                        Check.throwsType(IllegalArgumentException.class,
                                () -> ctx.invoke((Object) new int[] {5, -1, 7}), "totalLoad([5, -1, 7])")),

                TestCase.of("input-immutable", "Показания датчиков не изменяются", ctx -> {
                    int[] loads = {3, 1, 4, 1, 5, 9, 2, 6};
                    int[] snapshot = loads.clone();
                    ctx.invoke((Object) loads);
                    Check.isTrue(Arrays.equals(loads, snapshot), "Метод изменил входной массив: "
                            + Arrays.toString(snapshot) + " → " + Arrays.toString(loads));
                }),

                TestCase.of("hot-loop-allocations", "Горячий цикл диспетчера без лишних объектов", ctx -> {
                    int[] loads = new int[1_000];
                    Arrays.fill(loads, 7);
                    MethodHandle totalLoad = ctx.handle();
                    for (int i = 0; i < 500; i++) {
                        long ignored = (long) totalLoad.invokeExact(loads); // прогрев JIT и связывания
                    }
                    long[] sink = new long[1];
                    long allocated = ctx.measureAllocatedBytes(() -> {
                        long acc = 0;
                        for (int i = 0; i < HOT_LOOP_CALLS; i++) {
                            acc += (long) totalLoad.invokeExact(loads);
                        }
                        sink[0] = acc;
                    });
                    Check.equal(7_000L * HOT_LOOP_CALLS, sink[0], "Сумма за " + HOT_LOOP_CALLS + " вызовов");
                    Check.atMost(allocated, HOT_LOOP_ALLOCATION_BUDGET,
                            "Байт выделено за " + HOT_LOOP_CALLS + " вызовов (boxing, стримы, копии массивов?)");
                }));
    }
}
