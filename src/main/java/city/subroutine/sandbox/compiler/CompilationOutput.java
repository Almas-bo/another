package city.subroutine.sandbox.compiler;

import city.subroutine.sandbox.api.CompilationDiagnostic;

import java.util.List;
import java.util.Map;

/**
 * Результат компиляции.
 *
 * @param classes двоичное имя класса → байткод (пусто при неуспехе)
 */
public record CompilationOutput(
        boolean success,
        List<CompilationDiagnostic> diagnostics,
        Map<String, byte[]> classes,
        long compileNanos) {

    public CompilationOutput {
        diagnostics = List.copyOf(diagnostics);
        classes = Map.copyOf(classes);
    }
}
