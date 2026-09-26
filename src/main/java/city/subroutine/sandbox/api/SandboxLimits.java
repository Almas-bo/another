package city.subroutine.sandbox.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Лимиты ресурсов для одного запуска кода игрока.
 *
 * @param compileTimeout        время на компиляцию и статическую проверку байткода
 * @param perTestTimeout        лимит на один тест (обнаружение бесконечных циклов и deadlock)
 * @param totalTimeout          страховочный лимит на весь этап исполнения
 * @param heapMegabytes         размер кучи процесса-песочницы ({@code -Xmx})
 * @param maxThreads            сколько потоков может одновременно создать код игрока
 * @param maxOutputBytesPerTest сколько байт System.out/System.err сохраняется на тест
 * @param maxSourceChars        максимальный суммарный размер исходников
 */
public record SandboxLimits(
        Duration compileTimeout,
        Duration perTestTimeout,
        Duration totalTimeout,
        int heapMegabytes,
        int maxThreads,
        int maxOutputBytesPerTest,
        int maxSourceChars) {

    public static final int MIN_HEAP_MB = 64;
    public static final int MAX_HEAP_MB = 2048;
    public static final int MAX_THREADS_CEILING = 512;
    public static final int MAX_OUTPUT_CEILING = 1024 * 1024;
    public static final int MAX_SOURCE_CEILING = 1_000_000;

    public SandboxLimits {
        requirePositive(compileTimeout, "compileTimeout");
        requirePositive(perTestTimeout, "perTestTimeout");
        requirePositive(totalTimeout, "totalTimeout");
        if (perTestTimeout.compareTo(totalTimeout) > 0) {
            throw new IllegalArgumentException("perTestTimeout не может превышать totalTimeout");
        }
        if (heapMegabytes < MIN_HEAP_MB || heapMegabytes > MAX_HEAP_MB) {
            throw new IllegalArgumentException(
                    "heapMegabytes должен быть в диапазоне [" + MIN_HEAP_MB + ", " + MAX_HEAP_MB + "]");
        }
        if (maxThreads < 1 || maxThreads > MAX_THREADS_CEILING) {
            throw new IllegalArgumentException("maxThreads должен быть в диапазоне [1, " + MAX_THREADS_CEILING + "]");
        }
        if (maxOutputBytesPerTest < 0 || maxOutputBytesPerTest > MAX_OUTPUT_CEILING) {
            throw new IllegalArgumentException("maxOutputBytesPerTest вне диапазона [0, " + MAX_OUTPUT_CEILING + "]");
        }
        if (maxSourceChars < 1 || maxSourceChars > MAX_SOURCE_CEILING) {
            throw new IllegalArgumentException("maxSourceChars вне диапазона [1, " + MAX_SOURCE_CEILING + "]");
        }
    }

    public static SandboxLimits defaults() {
        return new SandboxLimits(
                Duration.ofSeconds(15),
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                128,
                16,
                16 * 1024,
                64 * 1024);
    }

    public SandboxLimits withPerTestTimeout(Duration value) {
        return new SandboxLimits(compileTimeout, value, totalTimeout, heapMegabytes, maxThreads,
                maxOutputBytesPerTest, maxSourceChars);
    }

    public SandboxLimits withTotalTimeout(Duration value) {
        return new SandboxLimits(compileTimeout, perTestTimeout, value, heapMegabytes, maxThreads,
                maxOutputBytesPerTest, maxSourceChars);
    }

    public SandboxLimits withHeapMegabytes(int value) {
        return new SandboxLimits(compileTimeout, perTestTimeout, totalTimeout, value, maxThreads,
                maxOutputBytesPerTest, maxSourceChars);
    }

    public SandboxLimits withMaxThreads(int value) {
        return new SandboxLimits(compileTimeout, perTestTimeout, totalTimeout, heapMegabytes, value,
                maxOutputBytesPerTest, maxSourceChars);
    }

    public SandboxLimits withMaxSourceChars(int value) {
        return new SandboxLimits(compileTimeout, perTestTimeout, totalTimeout, heapMegabytes, maxThreads,
                maxOutputBytesPerTest, value);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " должен быть положительным");
        }
    }
}
