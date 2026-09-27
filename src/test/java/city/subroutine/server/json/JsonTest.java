package city.subroutine.server.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void roundTripPreservesStructureAndUnicode() {
        Map<String, Object> value = Json.obj(
                "текст", "строка \"в кавычках\"\nс переносом\tи \\ слэшем \u0001",
                "числа", List.of(0L, -42L, 3.5, 1e-3),
                "вложено", Json.obj("пусто", null, "да", true, "нет", false),
                "список", List.of());
        String text = Json.write(value);
        assertEquals(value, Json.parse(text));
    }

    @Test
    void parsesEscapes() {
        assertEquals("A\u0416/\n", Json.parse("\"\\u0041\\u0416\\/\\n\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"a\":}", "[1,]", "{\"a\":1,\"a\":2}", "tru", "\"\\x\"", "01x", "{} {}",
            "\"\u0001\""})
    void rejectsMalformedInput(String text) {
        assertThrows(Json.JsonException.class, () -> Json.parse(text));
    }

    @Test
    void rejectsExcessiveNesting() {
        assertThrows(Json.JsonException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
    }
}
