package city.subroutine.sandbox.protocol;

import city.subroutine.sandbox.api.CompilationDiagnostic;

import java.util.List;

/** Кадр COMPILATION. */
public record CompilationReport(boolean success, List<CompilationDiagnostic> diagnostics, long compileNanos) {

    public CompilationReport {
        diagnostics = List.copyOf(diagnostics);
    }
}
