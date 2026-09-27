package city.player;

import city.subroutine.levels.telemetry.api.TelemetryCache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/** Эталон telemetry-01: LinkedHashMap в порядке доступа + removeEldestEntry = LRU фиксированного размера. */
public final class SensorCache implements TelemetryCache {

    private final LinkedHashMap<String, Double> readings = new LinkedHashMap<>(CAPACITY * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Double> eldest) {
            return size() > CAPACITY;
        }
    };

    @Override
    public void record(String sensorId, double value) {
        if (sensorId == null) {
            throw new IllegalArgumentException("sensorId == null");
        }
        readings.put(sensorId, value);
    }

    @Override
    public OptionalDouble latest(String sensorId) {
        if (sensorId == null) {
            throw new IllegalArgumentException("sensorId == null");
        }
        Double value = readings.get(sensorId);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    @Override
    public int size() {
        return readings.size();
    }
}
