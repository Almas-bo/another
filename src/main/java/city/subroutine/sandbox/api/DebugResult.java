package city.subroutine.sandbox.api;

import java.util.Objects;

/** Результат отладочного запуска: итог одного теста и трасса его выполнения. */
public record DebugResult(ExecutionResult result, DebugTrace trace) {

    public DebugResult {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(trace, "trace");
    }
}
