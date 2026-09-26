package city.subroutine.sandbox.testing;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Один тест.
 *
 * @param id    стабильный идентификатор ({@code int-overflow}); движок привязывает к нему визуальный сценарий сбоя
 * @param title название для игрока, на русском
 */
public record TestCase(String id, String title, TestBody body) {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public TestCase {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Некорректный id теста: " + id);
        }
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
    }

    public static TestCase of(String id, String title, TestBody body) {
        return new TestCase(id, title, body);
    }
}
