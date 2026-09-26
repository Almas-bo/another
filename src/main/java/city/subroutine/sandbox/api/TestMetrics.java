package city.subroutine.sandbox.api;

/**
 * Метрики одного теста.
 *
 * @param wallNanos      реальное время
 * @param cpuNanos       процессорное время потока теста
 * @param allocatedBytes байты, выделенные в потоке теста (ThreadMXBean#getThreadAllocatedBytes)
 * @param peakHeapBytes  пик занятости кучи (сумма пиков пулов — оценка сверху)
 * @param gcCount        число сборок мусора во время теста
 * @param gcTimeMillis   время в GC во время теста
 */
public record TestMetrics(
        long wallNanos,
        long cpuNanos,
        long allocatedBytes,
        long peakHeapBytes,
        long gcCount,
        long gcTimeMillis) {

    public static final TestMetrics EMPTY = new TestMetrics(0, 0, 0, 0, 0, 0);
}
