package city.subroutine.sandbox.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Примитивы бинарного формата: big-endian числа, строки UTF-8 с длиной (-1 = null), списки с длиной. */
public final class WireOutput {

    @FunctionalInterface
    public interface Encoder<T> {
        void encode(WireOutput out, T value) throws IOException;
    }

    private final DataOutputStream out;

    public WireOutput(OutputStream stream) {
        this.out = new DataOutputStream(stream);
    }

    public void writeInt(int value) throws IOException {
        out.writeInt(value);
    }

    public void writeLong(long value) throws IOException {
        out.writeLong(value);
    }

    public void writeBoolean(boolean value) throws IOException {
        out.writeBoolean(value);
    }

    public void writeString(String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public void writeEnum(Enum<?> value) throws IOException {
        writeString(value.name());
    }

    public void writeStringList(List<String> values) throws IOException {
        writeList(values, WireOutput::writeString);
    }

    public <T> void writeList(List<T> values, Encoder<T> encoder) throws IOException {
        out.writeInt(values.size());
        for (T value : values) {
            encoder.encode(this, value);
        }
    }

    public <T> void writeOptional(Optional<T> value, Encoder<T> encoder) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            encoder.encode(this, value.get());
        }
    }

    public void flush() throws IOException {
        out.flush();
    }
}
