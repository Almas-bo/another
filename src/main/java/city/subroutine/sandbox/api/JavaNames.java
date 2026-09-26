package city.subroutine.sandbox.api;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Проверка синтаксиса имён Java, приходящих из внешних запросов.
 * Все имена валидируются на границе системы, чтобы в javac, ClassLoader и командную строку
 * воркера никогда не попадали произвольные строки.
 */
public final class JavaNames {

    private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    private static final Pattern QUALIFIED = Pattern.compile(IDENTIFIER + "(?:\\." + IDENTIFIER + ")*");
    private static final Pattern TYPE = Pattern.compile("(" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)((?:\\[\\])*)");
    private static final Set<String> PRIMITIVES =
            Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double", "void");
    private static final int MAX_NAME_LENGTH = 512;

    private JavaNames() {
    }

    /** Двоичное имя класса: {@code city.player.PowerGrid}, {@code city.player.Outer$Inner}. */
    public static String requireBinaryName(String name, String what) {
        if (name == null || name.length() > MAX_NAME_LENGTH || !QUALIFIED.matcher(name).matches()) {
            throw new IllegalArgumentException(what + ": некорректное имя класса '" + name + "'");
        }
        return name;
    }

    /** Имя пакета: {@code city.player}. */
    public static String requirePackageName(String name, String what) {
        if (name == null || name.length() > MAX_NAME_LENGTH || !QUALIFIED.matcher(name).matches()) {
            throw new IllegalArgumentException(what + ": некорректное имя пакета '" + name + "'");
        }
        return name;
    }

    /** Простое имя метода. */
    public static String requireIdentifier(String name, String what) {
        if (name == null || name.length() > MAX_NAME_LENGTH || !Pattern.matches(IDENTIFIER, name)) {
            throw new IllegalArgumentException(what + ": некорректный идентификатор '" + name + "'");
        }
        return name;
    }

    /** Имя типа в исходной нотации: {@code int}, {@code int[]}, {@code java.lang.String[][]}. */
    public static String requireTypeName(String name, String what) {
        if (name == null || name.length() > MAX_NAME_LENGTH || !TYPE.matcher(name).matches()) {
            throw new IllegalArgumentException(what + ": некорректное имя типа '" + name + "'");
        }
        String element = name.replace("[]", "");
        if (!PRIMITIVES.contains(element) && element.indexOf('.') < 0) {
            throw new IllegalArgumentException(
                    what + ": ссылочный тип должен быть полностью квалифицирован: '" + name + "'");
        }
        if (element.equals("void") && !element.equals(name)) {
            throw new IllegalArgumentException(what + ": массив void недопустим");
        }
        return name;
    }

    public static boolean isPrimitive(String name) {
        return PRIMITIVES.contains(name);
    }

    public static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }
}
