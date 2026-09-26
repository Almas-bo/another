package city.subroutine.sandbox.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Чтение формата {@link WireOutput} со строгими лимитами: повреждённый поток не может вызвать огромную аллокацию. */
public final class WireInput {

    @FunctionalInterface
    public interface Decoder<T> {
        T decode(WireInput in) throws IOException;
    }

    public static final int MAX_STRING_BYTES = 8 * 1024 * 1024;
    public static final int MAX_LIST_SIZE = 100_000;

    private final DataInputStream in;

    public WireInput(InputStream stream) {
        this.in = new DataInputStream(stream);
    }

    public int readInt() throws IOException {
        return in.readInt();
    }

    public long readLong() throws IOException {
        return in.readLong();
    }

    public boolean readBoolean() throws IOException {
        return in.readBoolean();
    }

    public String readString() throws IOException {
        int length = in.readInt();
        if (length == -1) {
            return null;
        }
        if (length < -1 || length > MAX_STRING_BYTES) {
            throw new ProtocolException("Недопустимая длина строки: " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Строка обрезана");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String readRequiredString() throws IOException {
        String value = readString();
        if (value == null) {
            throw new ProtocolException("Ожидалась непустая строка");
        }
        return value;
    }

    public <E extends Enum<E>> E readEnum(Class<E> type) throws IOException {
        String name = readRequiredString();
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Неизвестное значение " + type.getSimpleName() + ": " + name, e);
        }
    }

    public List<String> readStringList() throws IOException {
        return readList(WireInput::readRequiredString);
    }

    public <T> List<T> readList(Decoder<T> decoder) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_LIST_SIZE) {
            throw new ProtocolException("Недопустимый размер списка: " + size);
        }
        List<T> result = new ArrayList<>(Math.min(size, 1024));
        for (int i = 0; i < size; i++) {
            result.add(decoder.decode(this));
        }
        return result;
    }

    public <T> Optional<T> readOptional(Decoder<T> decoder) throws IOException {
        return in.readBoolean() ? Optional.of(decoder.decode(this)) : Optional.empty();
    }

    /** true, если во входе не осталось данных (проверка, что кадр разобран целиком). */
    public boolean exhausted() throws IOException {
        return in.read() == -1;
    }
}
