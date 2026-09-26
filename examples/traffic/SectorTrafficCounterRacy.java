package city.player;

import city.subroutine.levels.traffic.api.TrafficCounter;

/** Гонка данных: long++ не атомарен, обновления теряются под нагрузкой. */
public final class SectorTrafficCounter implements TrafficCounter {

    private final long[] perSector = new long[SECTORS];
    private long total;

    @Override
    public void register(int sector) {
        if (sector < 0 || sector >= SECTORS) {
            throw new IllegalArgumentException("Сектор вне диапазона: " + sector);
        }
        perSector[sector]++;
        total++;
    }

    @Override
    public long ofSector(int sector) {
        return perSector[sector];
    }

    @Override
    public long total() {
        return total;
    }
}
