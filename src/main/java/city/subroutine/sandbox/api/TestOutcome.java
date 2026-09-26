package city.subroutine.sandbox.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Результат одного теста.
 *
 * @param message        пояснение (для FAILED — текст проверки уровня), или {@code null}
 * @param expected       ожидаемое значение для diff-панели IDE, или {@code null}
 * @param actual         фактическое значение, или {@code null}
 * @param error          исключение игрока (для ERROR) или причина провала проверки
 * @param output         перехваченный System.out/System.err за время теста
 * @param threads        снимки потоков (только для TIMEOUT/DEADLOCK)
 * @param leakedThreads  потоки, созданные тестом и оставшиеся живыми после него (не закрытый ExecutorService и т.п.)
 */
public record TestOutcome(
        String id,
        String title,
        TestStatus status,
        String message,
        String expected,
        String actual,
        Optional<ErrorReport> error,
        TestMetrics metrics,
        String output,
        boolean outputTruncated,
        List<ThreadSnapshot> threads,
        List<String> leakedThreads) {

    public TestOutcome {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(output, "output");
        threads = List.copyOf(Objects.requireNonNull(threads, "threads"));
        leakedThreads = List.copyOf(Objects.requireNonNull(leakedThreads, "leakedThreads"));
    }

    /** Тест, результат которого не был получен от песочницы (она остановилась раньше). */
    public static TestOutcome notReported(String id, String title, TestStatus status, String message) {
        return new TestOutcome(id, title, status, message, null, null, Optional.empty(), TestMetrics.EMPTY, "",
                false, List.of(), List.of());
    }
}
