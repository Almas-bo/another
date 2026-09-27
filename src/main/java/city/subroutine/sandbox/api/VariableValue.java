package city.subroutine.sandbox.api;

import java.util.Objects;

/**
 * Значение локальной переменной в шаге трассы. Строится только чтением полей через JDI —
 * методы объектов игрока (toString и т.п.) при записи трассы никогда не вызываются.
 *
 * @param type  имя типа ({@code int}, {@code java.lang.String}, {@code city.player.Node})
 * @param value отображение значения ({@code 42}, {@code "abc"}, {@code int[3] {1, 2, 3}})
 */
public record VariableValue(String name, String type, String value) {

    public VariableValue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }
}
