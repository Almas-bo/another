package city.subroutine.sandbox.api;

/**
 * Сводные метрики запуска.
 *
 * @param compileNanos        время javac + проверки байткода
 * @param executionWallNanos  реальное время этапа тестов
 * @param totalCpuNanos       сумма CPU потоков тестов
 * @param totalAllocatedBytes сумма аллокаций потоков тестов
 * @param peakHeapBytes       максимум пика кучи по тестам
 * @param gcCount             сборок мусора за все тесты
 * @param gcTimeMillis        время GC за все тесты
 * @param workerExitCode      код завершения процесса-песочницы (-1, если неизвестен)
 */
public record ExecutionMetrics(
        long compileNanos,
        long executionWallNanos,
        long totalCpuNanos,
        long totalAllocatedBytes,
        long peakHeapBytes,
        long gcCount,
        long gcTimeMillis,
        int workerExitCode) {

    public static final ExecutionMetrics EMPTY = new ExecutionMetrics(0, 0, 0, 0, 0, 0, 0, -1);
}
