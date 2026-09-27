package city.subroutine.levels.energy;

import city.subroutine.levels.energy.api.Battery;
import city.subroutine.levels.energy.api.EnergyGrid;
import city.subroutine.levels.support.Concurrency;
import city.subroutine.sandbox.testing.Check;
import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.testing.TestSuite;

import java.util.List;
import java.util.SplittableRandom;

/** Уровень «Энергобанк: переводы без deadlock» — упорядоченный захват замков, атомарность, гонки. */
public final class EnergySuite implements TestSuite {

    static final int THREADS = 8;
    static final int TRANSFERS_PER_THREAD = 20_000;

    @Override
    public List<TestCase> cases() {
        return List.of(
                TestCase.of("basic-transfer", "Перевод 30 единиц", ctx -> {
                    EnergyGrid grid = ctx.newInstance(EnergyGrid.class);
                    Battery a = new Battery(1, 100);
                    Battery b = new Battery(2, 0);
                    Check.isTrue(grid.transfer(a, b, 30), "transfer(A, B, 30) должен вернуть true");
                    Check.equal(70L, a.charge(), "Заряд A");
                    Check.equal(30L, b.charge(), "Заряд B");
                }),

                TestCase.of("insufficient", "Недостаточно заряда — false без изменений", ctx -> {
                    EnergyGrid grid = ctx.newInstance(EnergyGrid.class);
                    Battery a = new Battery(1, 10);
                    Battery b = new Battery(2, 5);
                    Check.isTrue(!grid.transfer(a, b, 50), "transfer(A, B, 50) при заряде 10 должен вернуть false");
                    Check.equal(10L, a.charge(), "Заряд A не должен измениться");
                    Check.equal(5L, b.charge(), "Заряд B не должен измениться");
                }),

                TestCase.of("invalid-arguments", "Некорректные переводы — IllegalArgumentException", ctx -> {
                    EnergyGrid grid = ctx.newInstance(EnergyGrid.class);
                    Battery a = new Battery(1, 100);
                    Battery b = new Battery(2, 100);
                    Check.throwsType(IllegalArgumentException.class, () -> grid.transfer(a, b, 0), "amount = 0");
                    Check.throwsType(IllegalArgumentException.class, () -> grid.transfer(a, b, -5), "amount < 0");
                    Check.throwsType(IllegalArgumentException.class, () -> grid.transfer(a, a, 5), "from == to");
                    Check.throwsType(IllegalArgumentException.class, () -> grid.transfer(null, b, 5), "from == null");
                    Check.throwsType(IllegalArgumentException.class, () -> grid.transfer(a, null, 5), "to == null");
                }),

                TestCase.of("opposite-directions", "Встречные переводы A→B и B→A одновременно", ctx -> {
                    EnergyGrid grid = ctx.newInstance(EnergyGrid.class);
                    Battery a = new Battery(1, 1_000_000);
                    Battery b = new Battery(2, 1_000_000);
                    Concurrency.runConcurrently(THREADS, worker -> {
                        Battery from = worker % 2 == 0 ? a : b;
                        Battery to = worker % 2 == 0 ? b : a;
                        for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                            grid.transfer(from, to, 1);
                        }
                    });
                    Check.equal(2_000_000L, a.charge() + b.charge(), "Суммарная энергия A + B — энергия не должна теряться");
                }),

                TestCase.of("grid-storm", "Шторм случайных переводов между 6 аккумуляторами", ctx -> {
                    EnergyGrid grid = ctx.newInstance(EnergyGrid.class);
                    Battery[] batteries = new Battery[6];
                    for (int i = 0; i < batteries.length; i++) {
                        batteries[i] = new Battery(100 - i * 7, 10_000);
                    }
                    Concurrency.runConcurrently(THREADS, worker -> {
                        SplittableRandom random = new SplittableRandom(31L * worker + 7);
                        for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                            int from = random.nextInt(batteries.length);
                            int to = (from + 1 + random.nextInt(batteries.length - 1)) % batteries.length;
                            grid.transfer(batteries[from], batteries[to], 1 + random.nextInt(50));
                        }
                    });
                    long total = 0;
                    for (Battery battery : batteries) {
                        Check.isTrue(battery.charge() >= 0, battery + ": отрицательный заряд " + battery.charge());
                        total += battery.charge();
                    }
                    Check.equal(60_000L, total, "Суммарная энергия сети");
                }));
    }
}
