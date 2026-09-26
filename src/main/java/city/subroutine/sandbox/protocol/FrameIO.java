package city.subroutine.sandbox.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Кадрирование: {@code [u1 type][s4 length][payload]}.
 * Сделано вручную, без Java-сериализации: хост читает поток процесса, в котором исполнялся недоверенный код.
 */
public final class FrameIO {

    public static final int MAX_PAYLOAD_BYTES = 32 * 1024 * 1024;

    @FunctionalInterface
    public interface Body {
        void writeTo(WireOutput out) throws IOException;
    }

    private FrameIO() {
    }

    public static Frame frame(FrameType type, Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        WireOutput out = new WireOutput(bytes);
        body.writeTo(out);
        out.flush();
        if (bytes.size() > MAX_PAYLOAD_BYTES) {
            throw new ProtocolException("Кадр слишком большой: " + bytes.size());
        }
        return new Frame(type, bytes.toByteArray());
    }

    public static void write(DataOutputStream out, Frame frame) throws IOException {
        out.writeByte(frame.type().code());
        out.writeInt(frame.payload().length);
        out.write(frame.payload());
        out.flush();
    }

    /** @return кадр или {@code null} при чистом конце потока */
    public static Frame read(DataInputStream in) throws IOException {
        int code = in.read();
        if (code == -1) {
            return null;
        }
        FrameType type = FrameType.fromCode(code);
        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw new ProtocolException("Недопустимая длина кадра: " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Кадр " + type + " обрезан");
        }
        return new Frame(type, payload);
    }

    /** Разбор полезной нагрузки целиком; ошибки валидации записей превращаются в ProtocolException. */
    public static <T> T decode(Frame frame, WireInput.Decoder<T> decoder) throws ProtocolException {
        WireInput in = new WireInput(new ByteArrayInputStream(frame.payload()));
        try {
            T value = decoder.decode(in);
            if (!in.exhausted()) {
                throw new ProtocolException("Лишние байты в кадре " + frame.type());
            }
            return value;
        } catch (ProtocolException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ProtocolException("Повреждённый кадр " + frame.type() + ": " + e.getMessage(), e);
        }
    }
}
