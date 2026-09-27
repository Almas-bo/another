package city.subroutine.levels.warehouse;

import city.subroutine.levels.warehouse.api.Container;
import city.subroutine.levels.warehouse.api.Storage;
import city.subroutine.sandbox.testing.AssertionFailure;
import city.subroutine.sandbox.testing.Check;
import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.testing.TestSuite;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Уровень «Склад: принцип подстановки Лисков». Холодильный склад {@code city.player.ColdStorage}
 * на {@value #CAPACITY} мест принимает только контейнеры с температурой не выше {@value #MAX_TEMPERATURE} °C.
 * Главное: он обязан соблюдать общий контракт {@link Storage}, иначе диспетчер города сломается.
 */
public final class WarehouseSuite implements TestSuite {

    static final int CAPACITY = 8;
    static final double MAX_TEMPERATURE = -18.0;

    private static Container cold(String id) {
        return new Container(id, -24.0);
    }

    private static Container warm(String id) {
        return new Container(id, 4.0);
    }

    @Override
    public List<TestCase> cases() {
        return List.of(
                TestCase.of("capacity", "Вместимость холодильного склада — 8 мест", ctx -> {
                    Storage storage = ctx.newInstance(Storage.class);
                    Check.equal(CAPACITY, storage.capacity(), "capacity()");
                    Check.equal(0, storage.size(), "size() нового склада");
                }),

                TestCase.of("fifo", "Контейнеры выдаются в порядке поступления", ctx -> {
                    Storage storage = ctx.newInstance(Storage.class);
                    for (String id : List.of("K-1", "K-2", "K-3")) {
                        Check.isTrue(storage.offer(cold(id)), "Холодный контейнер " + id + " должен быть принят");
                    }
                    Check.equal("K-1", idOf(storage.poll()), "Первый poll()");
                    Check.equal("K-2", idOf(storage.poll()), "Второй poll()");
                    Check.equal("K-3", idOf(storage.poll()), "Третий poll()");
                    Check.equal(null, storage.poll(), "poll() опустевшего склада");
                }),

                TestCase.of("empty-poll", "Пустой склад: poll() возвращает null, а не бросает исключение", ctx -> {
                    Storage storage = ctx.newInstance(Storage.class);
                    Object result = contractCall("poll() на пустом складе", storage::poll);
                    Check.equal(null, result, "poll() на пустом складе");
                }),

                TestCase.of("null-offer", "offer(null) — NullPointerException", ctx -> {
                    Storage storage = ctx.newInstance(Storage.class);
                    Check.throwsType(NullPointerException.class, () -> storage.offer(null), "offer(null)");
                }),

                TestCase.of("full-storage", "Полный склад отказывает через false", ctx -> {
                    Storage storage = ctx.newInstance(Storage.class);
                    for (int i = 0; i < CAPACITY; i++) {
                        Check.isTrue(storage.offer(cold("K-" + i)), "Контейнер K-" + i + " должен быть принят");
                    }
                    Object accepted = contractCall("offer() в полный склад", () -> storage.offer(cold("K-extra")));
                    Check.equal(Boolean.FALSE, accepted, "offer() в полный склад");
                    Check.equal(CAPACITY, storage.size(), "size() полного склада");
                }),

                TestCase.of("warm-rejected", "Тёплый контейнер отклоняется через false, а не исключением", ctx -> {
                    Storage storage = ctx.newInstance(Storage.class);
                    Object accepted = contractCall("offer() тёплого контейнера", () -> storage.offer(warm("T-1")));
                    Check.equal(Boolean.FALSE, accepted, "offer() контейнера +4 °C");
                    Check.equal(0, storage.size(), "size() после отказа");
                    Check.isTrue(storage.offer(new Container("B-1", MAX_TEMPERATURE)),
                            "Контейнер ровно " + MAX_TEMPERATURE + " °C должен быть принят");
                }),

                TestCase.of("dispatcher", "Диспетчер города работает с ColdStorage как с любым складом", ctx -> {
                    Storage storage = ctx.newInstance(Storage.class);
                    List<Container> arrivals = List.of(cold("C-1"), warm("W-1"), cold("C-2"), warm("W-2"), cold("C-3"));
                    int accepted = 0;
                    for (Container container : arrivals) {
                        Object result = contractCall("Диспетчер: offer(" + container.id() + ")", () -> storage.offer(container));
                        if (Boolean.TRUE.equals(result)) {
                            accepted++;
                        }
                    }
                    Check.equal(3, accepted, "Принято контейнеров");
                    int drained = 0;
                    while (contractCall("Диспетчер: poll()", storage::poll) != null) {
                        drained++;
                        Check.isTrue(drained <= CAPACITY, "poll() выдаёт больше контейнеров, чем было принято");
                    }
                    Check.equal(3, drained, "Выдано контейнеров");
                }),

                TestCase.of("model-check", "5000 случайных операций против эталонной модели", ctx -> {
                    Storage storage = ctx.newInstance(Storage.class);
                    Deque<String> model = new ArrayDeque<>();
                    Random random = new Random(20_260_927L);
                    for (int step = 0; step < 5_000; step++) {
                        if (random.nextInt(3) < 2) {
                            boolean isCold = random.nextInt(4) != 0;
                            Container container = new Container("R-" + step, isCold ? -30.0 : 12.0);
                            boolean expected = isCold && model.size() < CAPACITY;
                            Object actual = contractCall("Шаг " + step + ": offer(" + container + ")", () -> storage.offer(container));
                            Check.equal(expected, actual, "Шаг " + step + ": offer(" + container + ")");
                            if (expected) {
                                model.addLast(container.id());
                            }
                        } else {
                            Object polled = contractCall("Шаг " + step + ": poll()", storage::poll);
                            Check.equal(model.pollFirst(), polled == null ? null : ((Container) polled).id(),
                                    "Шаг " + step + ": poll()");
                        }
                        Check.equal(model.size(), storage.size(), "Шаг " + step + ": size()");
                    }
                }));
    }

    private static String idOf(Container container) {
        return container == null ? null : container.id();
    }

    @FunctionalInterface
    private interface ContractCall {
        Object call();
    }

    /** Вызов, для которого контракт Storage запрещает исключения. */
    private static Object contractCall(String what, ContractCall call) {
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw new AssertionFailure(what + ": нарушен принцип подстановки Лисков — контракт Storage обещает "
                    + "результат без исключения, а ColdStorage бросил " + e.getClass().getName()
                    + ". Код, написанный для любого Storage, с этим складом ломается.", "результат по контракту",
                    e.getClass().getName(), e);
        }
    }
}
