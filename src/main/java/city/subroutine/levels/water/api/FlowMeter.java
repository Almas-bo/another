package city.subroutine.levels.water.api;

import java.util.List;

/**
 * Замер суммарного расхода водозабора. Контракт уровня water-01.
 *
 * <ol>
 *   <li>Задвижки открываются по очереди в порядке списка; суммируется {@link Valve#flowRate()}.</li>
 *   <li>Каждая успешно открытая задвижка закрывается ровно один раз — даже если произошла ошибка.</li>
 *   <li>Первая ошибка останавливает замер и пробрасывается наружу как есть: тот же объект исключения,
 *       без обёрток и без «проглатывания».</li>
 *   <li>Ошибка закрытия не теряется: если уже есть основная ошибка — она добавляется к ней
 *       как suppressed; если основной ошибки нет — пробрасывается сама.</li>
 *   <li>{@code board == null} или {@code valveIds == null} → {@link IllegalArgumentException}.</li>
 * </ol>
 */
public interface FlowMeter {

    long measureTotal(ValveBoard board, List<String> valveIds) throws ValveJammedException;
}
