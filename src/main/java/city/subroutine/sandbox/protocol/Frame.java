package city.subroutine.sandbox.protocol;

import java.util.Objects;

/** Кадр протокола: тип + полезная нагрузка. */
public record Frame(FrameType type, byte[] payload) {

    public Frame {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }
}
