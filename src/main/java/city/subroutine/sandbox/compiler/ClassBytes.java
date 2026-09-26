package city.subroutine.sandbox.compiler;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;

/** Приёмник байткода одного класса, который javac «записывает на диск». */
final class ClassBytes extends SimpleJavaFileObject {

    private final String binaryName;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);

    ClassBytes(String binaryName) {
        super(URI.create("bytes:///" + binaryName.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        this.binaryName = binaryName;
    }

    String binaryName() {
        return binaryName;
    }

    byte[] toByteArray() {
        return bytes.toByteArray();
    }

    @Override
    public OutputStream openOutputStream() {
        bytes.reset();
        return bytes;
    }
}
