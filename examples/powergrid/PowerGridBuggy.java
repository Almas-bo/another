package city.player;

import java.util.Arrays;

/**
 * Типичное «почти правильное» решение: сумма в int (переполнение), нет проверки на null,
 * сортировка портит входной массив, стрим с boxing в горячем цикле.
 * Файл нужно подать в CLI как есть — класс называется PowerGrid, как требует контракт.
 */
public final class PowerGrid {

    public static long totalLoad(int[] sectorLoads) {
        Arrays.sort(sectorLoads);
        if (sectorLoads.length > 0 && sectorLoads[0] < 0) {
            throw new IllegalArgumentException("Отрицательная нагрузка");
        }
        int total = Arrays.stream(sectorLoads).boxed().reduce(0, Integer::sum);
        return total;
    }
}
