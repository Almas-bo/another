package city.subroutine.sandbox.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Структурированное исключение: класс, сообщение, отфильтрованный стек (без кадров обвязки песочницы),
 * цепочка причин и suppressed-исключения (важно для уровней про try-with-resources).
 *
 * @param message        сообщение исключения или {@code null}
 * @param omittedFrames  сколько кадров отброшено из-за лимита глубины (например, при StackOverflowError)
 */
public record ErrorReport(
        String exceptionClass,
        String message,
        List<StackFrameInfo> frames,
        int omittedFrames,
        Optional<ErrorReport> cause,
        List<ErrorReport> suppressed) {

    public ErrorReport {
        Objects.requireNonNull(exceptionClass, "exceptionClass");
        frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
        Objects.requireNonNull(cause, "cause");
        suppressed = List.copyOf(Objects.requireNonNull(suppressed, "suppressed"));
    }

    /** Первый кадр из кода игрока — «место аварии» для визуализатора. */
    public Optional<StackFrameInfo> firstPlayerFrame() {
        return frames.stream().filter(StackFrameInfo::playerCode).findFirst();
    }
}
