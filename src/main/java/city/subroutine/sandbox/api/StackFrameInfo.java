package city.subroutine.sandbox.api;

import java.util.Objects;

/**
 * Кадр стека. {@code playerCode} = кадр из кода игрока: движок подсвечивает строку в IDE и узел в городе.
 *
 * @param fileName   имя файла или {@code null}
 * @param lineNumber строка или отрицательное значение, если неизвестна
 */
public record StackFrameInfo(String className, String methodName, String fileName, int lineNumber, boolean playerCode) {

    public StackFrameInfo {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
    }
}
