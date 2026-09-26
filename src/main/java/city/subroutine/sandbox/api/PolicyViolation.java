package city.subroutine.sandbox.api;

import java.util.Objects;

/**
 * Нарушение политики песочницы, найденное статическим анализом байткода до загрузки классов.
 *
 * @param playerClass класс игрока (внутреннее имя JVM приведено к двоичному), где найдено нарушение
 * @param reference   на что ссылается код: {@code java.io.File} или {@code java.lang.System#exit}
 * @param detail      пояснение для игрока
 */
public record PolicyViolation(Rule rule, String playerClass, String reference, String detail) {

    public enum Rule {
        /** Упоминание запрещённого типа (java.io.File, java.net.*, java.lang.reflect.* ...). */
        FORBIDDEN_TYPE,
        /** Обращение к запрещённому члену разрешённого типа (System.exit, Class.forName ...). */
        FORBIDDEN_MEMBER,
        /** Объявление native-метода. */
        NATIVE_METHOD,
        /** Класс вне пакета игрока. */
        WRONG_PACKAGE,
        /** Class-файл не удалось разобрать. */
        MALFORMED_CLASS
    }

    public PolicyViolation {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(playerClass, "playerClass");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(detail, "detail");
    }
}
