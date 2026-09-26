package city.subroutine.levels.traffic.api;

/**
 * Счётчик транспортного потока по секторам города. Вызывается одновременно из многих потоков датчиков.
 * Реализация обязана быть потокобезопасной и не терять ни одного события.
 */
public interface TrafficCounter {

    /** Количество секторов: допустимые номера — {@code [0, SECTORS)}. */
    int SECTORS = 64;

    /**
     * Зарегистрировать проезд через сектор.
     *
     * @throws IllegalArgumentException если сектор вне диапазона
     */
    void register(int sector);

    /** Сколько проездов зарегистрировано по сектору. */
    long ofSector(int sector);

    /** Сколько проездов зарегистрировано всего. */
    long total();
}
