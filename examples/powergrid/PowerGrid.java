package city.player;

/** Эталонное решение уровня powergrid-01. */
public final class PowerGrid {

    private PowerGrid() {
    }

    public static long totalLoad(int[] sectorLoads) {
        if (sectorLoads == null) {
            throw new IllegalArgumentException("Сеть не подключена: массив нагрузок равен null");
        }
        long total = 0;
        for (int i = 0; i < sectorLoads.length; i++) {
            int load = sectorLoads[i];
            if (load < 0) {
                throw new IllegalArgumentException("Отрицательная нагрузка в секторе " + i + ": " + load);
            }
            total += load;
        }
        return total;
    }
}
