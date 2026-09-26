package city.subroutine.sandbox.compiler;

import city.subroutine.sandbox.api.SourceUnit;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/** Исходник игрока в памяти. URI вида {@code string:///city/player/PowerGrid.java}. */
final class StringSource extends SimpleJavaFileObject {

    private final SourceUnit unit;

    StringSource(SourceUnit unit) {
        super(URI.create("string:///" + unit.relativePath()), Kind.SOURCE);
        this.unit = unit;
    }

    String className() {
        return unit.className();
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return unit.code();
    }
}
