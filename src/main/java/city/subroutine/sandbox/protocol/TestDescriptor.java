package city.subroutine.sandbox.protocol;

import java.util.Objects;

/** Заголовок теста в кадре SUITE_STARTED. */
public record TestDescriptor(String id, String title) {

    public TestDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
    }
}
