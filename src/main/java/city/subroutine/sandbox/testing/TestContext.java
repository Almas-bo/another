package city.subroutine.sandbox.testing;

import java.lang.invoke.MethodHandle;

/**
 * Доступ набора тестов к коду игрока. Один контекст — один тест; объект игрока (для методов экземпляра)
 * создаётся лениво и живёт в пределах теста.
 */
public interface TestContext {

    /**
     * Вызов метода контракта с упаковкой аргументов. Исключение игрока пробрасывается как есть,
     * без InvocationTargetException. Массив-аргумент передавайте как {@code (Object) array}.
     *
     * @throws IllegalStateException если контракт уровня — интерфейс, а не метод
     */
    Object invoke(Object... args) throws Throwable;

    /**
     * Точный {@link MethodHandle} метода контракта (для метода экземпляра — уже привязанный к объекту теста).
     * Для горячих циклов: {@code long r = (long) h.invokeExact(data);} не упаковывает аргументы
     * и не искажает замер аллокаций.
     */
    MethodHandle handle() throws Throwable;

    /**
     * Новый экземпляр класса игрока, приведённый к интерфейсу контракта. Каждый вызов — новый объект.
     *
     * @throws AssertionFailure если класс игрока не реализует {@code contract}
     */
    <T> T newInstance(Class<T> contract) throws Throwable;

    /**
     * Сколько байт выделено в текущем потоке за время {@code action}.
     * Позволяет ловить лишние объекты в горячих циклах (boxing, стримы, копии массивов).
     * Аллокации в других потоках не учитываются.
     */
    long measureAllocatedBytes(ThrowingRunnable action) throws Throwable;

    /** Класс игрока (например, для проверки модификаторов или реализуемых интерфейсов). */
    Class<?> playerClass();
}
