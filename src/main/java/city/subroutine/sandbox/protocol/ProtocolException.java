package city.subroutine.sandbox.protocol;

import java.io.IOException;

/** Нарушение формата кадров между хостом и воркером. */
public final class ProtocolException extends IOException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
