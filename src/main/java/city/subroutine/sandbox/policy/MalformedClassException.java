package city.subroutine.sandbox.policy;

/** Class-файл не соответствует спецификации JVMS §4. */
public final class MalformedClassException extends Exception {

    public MalformedClassException(String message) {
        super(message);
    }

    public MalformedClassException(String message, Throwable cause) {
        super(message, cause);
    }
}
