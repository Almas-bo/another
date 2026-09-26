package city.subroutine.sandbox.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Структурированный результат, который уходит в движок (через Protobuf-шлюз) и управляет визуализацией.
 *
 * @param statusDetail пояснение к статусу (для CONTRACT_VIOLATION, TIMEOUT, SANDBOX_FAILURE ...), или {@code null}
 * @param fatalError   исключение, остановившее песочницу вне тестов (например, ошибка в наборе тестов уровня)
 * @param sandboxLog   хвост stderr процесса-песочницы (сообщения JVM) — для диагностики, не для игрока
 */
public record ExecutionResult(
        String requestId,
        ExecutionStatus status,
        String statusDetail,
        List<CompilationDiagnostic> diagnostics,
        List<PolicyViolation> policyViolations,
        List<TestOutcome> tests,
        ExecutionMetrics metrics,
        Optional<ErrorReport> fatalError,
        String sandboxLog) {

    public ExecutionResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        policyViolations = List.copyOf(Objects.requireNonNull(policyViolations, "policyViolations"));
        tests = List.copyOf(Objects.requireNonNull(tests, "tests"));
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(fatalError, "fatalError");
        Objects.requireNonNull(sandboxLog, "sandboxLog");
    }

    public static ExecutionResult rejected(String requestId, String reason) {
        return new ExecutionResult(requestId, ExecutionStatus.REJECTED, reason, List.of(), List.of(), List.of(),
                ExecutionMetrics.EMPTY, Optional.empty(), "");
    }

    public static ExecutionResult sandboxFailure(String requestId, String reason, String sandboxLog) {
        return new ExecutionResult(requestId, ExecutionStatus.SANDBOX_FAILURE, reason, List.of(), List.of(),
                List.of(), ExecutionMetrics.EMPTY, Optional.empty(), sandboxLog);
    }

    public long passedCount() {
        return tests.stream().filter(t -> t.status().passed()).count();
    }

    public Optional<TestOutcome> test(String id) {
        return tests.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
