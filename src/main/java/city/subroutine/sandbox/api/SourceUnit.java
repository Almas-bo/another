package city.subroutine.sandbox.api;

import java.util.Objects;

/**
 * Одна единица компиляции игрока. Имя файла для javac выводится из {@code className}, поэтому
 * public-класс в {@code code} обязан называться так же — иначе игрок получит стандартную ошибку javac.
 *
 * @param className двоичное имя верхнеуровневого класса, например {@code city.player.PowerGrid}
 * @param code      исходный текст
 */
public record SourceUnit(String className, String code) {

    public SourceUnit {
        JavaNames.requireBinaryName(className, "SourceUnit.className");
        if (className.indexOf('$') >= 0) {
            throw new IllegalArgumentException("SourceUnit.className должен быть верхнеуровневым классом: " + className);
        }
        Objects.requireNonNull(code, "code");
    }

    /** Путь внутри виртуальной файловой системы javac: {@code city/player/PowerGrid.java}. */
    public String relativePath() {
        return className.replace('.', '/') + ".java";
    }
}
