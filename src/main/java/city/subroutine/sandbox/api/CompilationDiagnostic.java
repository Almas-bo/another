package city.subroutine.sandbox.api;

import java.util.Objects;

/**
 * Диагностика javac. {@code code} — стабильный ключ javac (например {@code compiler.err.cant.resolve.location}),
 * по нему клиент подбирает русский текст из таблицы локализации. {@code message} — исходный текст javac (Locale.ROOT).
 *
 * @param sourceClass класс-источник или {@code null}, если диагностика не привязана к файлу
 * @param line        строка (с 1) или -1
 * @param column      столбец (с 1) или -1
 */
public record CompilationDiagnostic(
        Kind kind,
        String code,
        String message,
        String sourceClass,
        long line,
        long column,
        long startPosition,
        long endPosition) {

    public enum Kind { ERROR, WARNING, NOTE }

    public CompilationDiagnostic {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
