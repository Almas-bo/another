package city.subroutine.server.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Минимальный JSON без зависимостей. Модель: {@code Map<String,Object>} (порядок ключей сохраняется),
 * {@code List<Object>}, {@code String}, {@code Long}/{@code Double}, {@code Boolean}, {@code null}.
 * Парсер строгий (RFC 8259) и ограничен по глубине и размеру — на вход приходят данные из сети.
 */
public final class Json {

    public static final int MAX_DEPTH = 64;

    private Json() {
    }

    // ---------------------------------------------------------------- сериализация

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(value, out, 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Слишком глубокая структура JSON");
        }
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Double d -> out.append(Double.isFinite(d) ? d.toString() : "null");
            case Float f -> out.append(Float.isFinite(f) ? f.toString() : "null");
            case Enum<?> e -> quote(e.name(), out);
            case Optional<?> o -> write(o.orElse(null), out, depth);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out, depth + 1);
                }
                out.append('}');
            }
            case Iterable<?> list -> {
                out.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(item, out, depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Тип не поддерживается JSON: " + value.getClass().getName());
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Упорядоченный объект: {@code obj("a", 1, "b", "x")}. Значения {@code null} сохраняются. */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Нечётное число аргументов");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // ---------------------------------------------------------------- разбор

    public static Object parse(String text) {
        Parser parser = new Parser(Objects.requireNonNull(text, "text"));
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("лишние символы после значения");
        }
        return value;
    }

    /** Разбор объекта верхнего уровня. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) {
            throw new JsonException("Ожидался JSON-объект");
        }
        return (Map<String, Object>) value;
    }

    public static final class JsonException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("слишком глубокая вложенность");
            }
            if (pos >= text.length()) {
                throw error("неожиданный конец");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("неожиданный символ '" + c + "'");
                }
            };
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("ожидался ключ-строка");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (map.containsKey(key)) {
                    throw error("повторяющийся ключ '" + key + "'");
                }
                map.put(key, value(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("ожидалась ',' или '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return Collections.unmodifiableList(list);
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return Collections.unmodifiableList(list);
                }
                if (c != ',') {
                    throw error("ожидалась ',' или ']'");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw error("незакрытая строка");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("управляющий символ в строке");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("обрезанная \\u-последовательность");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("некорректная \\u-последовательность");
                        }
                        pos += 4;
                    }
                    default -> throw error("неизвестная escape-последовательность \\" + escape);
                }
            }
        }

        private Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            digits();
            boolean fractional = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                fractional = true;
                pos++;
                digits();
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                fractional = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                digits();
            }
            String literal = text.substring(start, pos);
            try {
                if (!fractional) {
                    return Long.parseLong(literal);
                }
                return Double.parseDouble(literal);
            } catch (NumberFormatException e) {
                return Double.parseDouble(literal);
            }
        }

        private void digits() {
            int start = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos)) && text.charAt(pos) < 128) {
                pos++;
            }
            if (pos == start) {
                throw error("ожидалась цифра");
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("ожидалось " + word);
            }
            pos += word.length();
            return value;
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("неожиданный конец");
            }
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("ожидался '" + c + "'");
            }
        }

        JsonException error(String message) {
            return new JsonException("Некорректный JSON (позиция " + pos + "): " + message);
        }
    }
}
