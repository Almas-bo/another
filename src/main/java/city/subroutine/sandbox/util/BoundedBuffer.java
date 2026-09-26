package city.subroutine.sandbox.util;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Потокобезопасный буфер фиксированной ёмкости: лишние байты отбрасываются, факт обрезки запоминается. */
public final class BoundedBuffer {

    private final byte[] data;
    private int size;
    private boolean truncated;

    public BoundedBuffer(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0");
        }
        this.data = new byte[capacity];
    }

    public synchronized void write(int b) {
        if (size < data.length) {
            data[size++] = (byte) b;
        } else {
            truncated = true;
        }
    }

    public synchronized void write(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        int accepted = Math.min(length, data.length - size);
        System.arraycopy(bytes, offset, data, size, accepted);
        size += accepted;
        if (accepted < length) {
            truncated = true;
        }
    }

    public synchronized boolean truncated() {
        return truncated;
    }

    public synchronized String contentAsString() {
        return new String(data, 0, size, StandardCharsets.UTF_8);
    }
}
