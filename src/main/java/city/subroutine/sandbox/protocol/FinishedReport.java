package city.subroutine.sandbox.protocol;

import city.subroutine.sandbox.api.ErrorReport;

import java.util.Objects;
import java.util.Optional;

/**
 * Кадр FINISHED.
 *
 * @param detail пояснение к вердикту или {@code null}
 */
public record FinishedReport(WorkerVerdict verdict, String detail, Optional<ErrorReport> fatalError) {

    public FinishedReport {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(fatalError, "fatalError");
    }
}
