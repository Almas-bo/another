package city.subroutine.sandbox.api;

import java.util.List;
import java.util.Objects;

/**
 * Один шаг трассы: поток остановился на строке кода игрока.
 *
 * @param index  порядковый номер шага
 * @param thread имя потока
 * @param depth  глубина стека потока (для Step Over / Step Out сравниваются глубины одного потока)
 * @param locals локальные переменные верхнего кадра
 * @param stack  верхние кадры стека (кадр 0 — текущий)
 */
public record TraceStep(
        int index,
        String thread,
        int depth,
        String className,
        String method,
        int line,
        List<VariableValue> locals,
        List<StackFrameInfo> stack) {

    public TraceStep {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(method, "method");
        locals = List.copyOf(Objects.requireNonNull(locals, "locals"));
        stack = List.copyOf(Objects.requireNonNull(stack, "stack"));
    }
}
