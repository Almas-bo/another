package city.subroutine.sandbox.testing;

import java.util.Arrays;
import java.util.Objects;

/** Проверки для наборов тестов уровней. Сообщения — на русском, для игрока. */
public final class Check {

    private static final int MAX_RENDER_ELEMENTS = 32;

    private Check() {
    }

    public static void equal(long expected, long actual, String what) {
        if (expected != actual) {
            throw new AssertionFailure(what + ": ожидалось " + expected + ", получено " + actual,
                    Long.toString(expected), Long.toString(actual), null);
        }
    }

    public static void equal(Object expected, Object actual, String what) {
        if (!Objects.deepEquals(expected, actual)) {
            String e = render(expected);
            String a = render(actual);
            throw new AssertionFailure(what + ": ожидалось " + e + ", получено " + a, e, a, null);
        }
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionFailure(message);
        }
    }

    public static void atMost(long actual, long limit, String what) {
        if (actual > limit) {
            throw new AssertionFailure(what + ": допустимо не более " + limit + ", получено " + actual,
                    "≤ " + limit, Long.toString(actual), null);
        }
    }

    /** Ожидает исключение заданного типа (или подтипа) и возвращает его для дальнейших проверок. */
    public static <T extends Throwable> T throwsType(Class<T> type, ThrowingRunnable action, String what) {
        Objects.requireNonNull(type, "type");
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return type.cast(thrown);
            }
            throw new AssertionFailure(
                    what + ": ожидалось исключение " + type.getName() + ", но выброшено " + thrown.getClass().getName(),
                    type.getName(), thrown.getClass().getName(), thrown);
        }
        throw new AssertionFailure(
                what + ": ожидалось исключение " + type.getName() + ", но метод завершился без исключения",
                type.getName(), "нет исключения", null);
    }

    public static AssertionFailure fail(String message) {
        throw new AssertionFailure(message);
    }

    static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return '"' + s + '"';
        }
        if (value.getClass().isArray()) {
            String text = Arrays.deepToString(new Object[] {value});
            text = text.substring(1, text.length() - 1);
            int length = java.lang.reflect.Array.getLength(value);
            if (length > MAX_RENDER_ELEMENTS) {
                return value.getClass().getComponentType().getSimpleName() + "[" + length + "]";
            }
            return text;
        }
        return String.valueOf(value);
    }
}
