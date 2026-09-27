package city.subroutine.levels.warehouse.api;

/**
 * Склад контейнеров. Контракт обязателен для <b>любой</b> реализации — диспетчер города работает
 * со всеми складами через этот интерфейс и не знает, какой склад перед ним.
 *
 * <ul>
 *   <li>{@link #capacity()} &gt; 0 и не меняется.</li>
 *   <li>{@link #offer(Container)}: {@code null} → {@link NullPointerException}. Если контейнер нельзя принять
 *       (склад полон или контейнер не подходит этому складу) — вернуть {@code false}, ничего не меняя.
 *       Иначе принять, вернуть {@code true}; {@link #size()} увеличивается на 1.
 *       Никаких других исключений.</li>
 *   <li>{@link #poll()}: выдаёт контейнеры в порядке поступления (FIFO). Пустой склад → {@code null}, не исключение.</li>
 *   <li>{@link #size()} всегда в диапазоне {@code [0, capacity()]}.</li>
 * </ul>
 */
public interface Storage {

    int capacity();

    boolean offer(Container container);

    Container poll();

    int size();
}
