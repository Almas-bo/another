package city.subroutine.levels.telemetry.api;

import java.util.OptionalDouble;

/**
 * Кэш последних показаний датчиков города. Контракт уровня telemetry-01.
 *
 * <ul>
 *   <li>Хранит показания не более чем {@link #CAPACITY} датчиков.</li>
 *   <li>При переполнении вытесняется датчик, к которому дольше всего не обращались (LRU).
 *       Обращением считаются и {@link #record}, и {@link #latest}.</li>
 *   <li>{@code sensorId == null} → {@link IllegalArgumentException}.</li>
 * </ul>
 * В город поступают показания миллионов датчиков — кэш без вытеснения исчерпает память.
 */
public interface TelemetryCache {

    int CAPACITY = 1_000;

    void record(String sensorId, double value);

    /** Последнее показание датчика или пусто, если датчика нет в кэше. */
    OptionalDouble latest(String sensorId);

    int size();
}
