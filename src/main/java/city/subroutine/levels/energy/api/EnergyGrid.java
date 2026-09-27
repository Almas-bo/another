package city.subroutine.levels.energy.api;

/**
 * Перераспределение энергии между аккумуляторами. Контракт уровня energy-01.
 *
 * <ul>
 *   <li>{@code amount <= 0}, {@code from == to} или {@code null} → {@link IllegalArgumentException}.</li>
 *   <li>Перевод атомарен: либо целиком, либо никак. Недостаточно заряда → {@code false} без изменений.</li>
 *   <li>Метод вызывают одновременно десятки потоков, в том числе встречные переводы A→B и B→A.
 *       Энергия не должна теряться или появляться из ниоткуда, а город — зависать.</li>
 * </ul>
 */
public interface EnergyGrid {

    boolean transfer(Battery from, Battery to, long amount);
}
