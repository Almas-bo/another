package city.subroutine.sandbox.api;

import java.util.List;
import java.util.Objects;

/**
 * Записанная трасса выполнения одного теста.
 *
 * @param truncated запись остановлена по лимиту шагов (программа при этом доработала до конца)
 * @param note      пояснение (например, почему трасса пуста), или {@code null}
 */
public record DebugTrace(String testId, List<TraceStep> steps, boolean truncated, int maxSteps, String note) {

    public DebugTrace {
        Objects.requireNonNull(testId, "testId");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }
}
