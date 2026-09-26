package city.subroutine.sandbox.testing;

import java.io.Serial;

/**
 * Провал проверки уровня. {@code expected}/{@code actual} уходят в diff-панель IDE.
 * Причина (cause), если есть, — исключение игрока, которое привело к провалу.
 */
public final class AssertionFailure extends AssertionError {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String expected;
    private final String actual;

    public AssertionFailure(String message) {
        this(message, null, null, null);
    }

    public AssertionFailure(String message, String expected, String actual, Throwable cause) {
        super(message, cause);
        this.expected = expected;
        this.actual = actual;
    }

    /** Ожидаемое значение в текстовом виде или {@code null}. */
    public String expected() {
        return expected;
    }

    /** Фактическое значение в текстовом виде или {@code null}. */
    public String actual() {
        return actual;
    }
}
