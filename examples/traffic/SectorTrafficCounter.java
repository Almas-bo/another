package city.player;

import city.subroutine.levels.traffic.api.TrafficCounter;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Эталонное решение traffic-01: без блокировок, без потерянных обновлений. */
public final class SectorTrafficCounter implements TrafficCounter {

    private final AtomicLongArray perSector = new AtomicLongArray(SECTORS);
    private final LongAdder total = new LongAdder();

    @Override
    public void register(int sector) {
        if (sector < 0 || sector >= SECTORS) {
            throw new IllegalArgumentException("Сектор вне диапазона: " + sector);
        }
        perSector.incrementAndGet(sector);
        total.increment();
    }

    @Override
    public long ofSector(int sector) {
        if (sector < 0 || sector >= SECTORS) {
            throw new IllegalArgumentException("Сектор вне диапазона: " + sector);
        }
        return perSector.get(sector);
    }

    @Override
    public long total() {
        return total.sum();
    }
}
