package city.player;

import city.subroutine.levels.telemetry.api.TelemetryCache;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/** Утечка памяти: кэш помнит все датчики навсегда. */
public final class SensorCache implements TelemetryCache {

    private final Map<String, Double> readings = new HashMap<>();

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
        return Math.min(readings.size(), CAPACITY);
    }
}
