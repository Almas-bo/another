package city.subroutine.sandbox.compiler;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Перехватывает вывод javac: class-файлы остаются в памяти, файловая система не используется. */
final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Map<String, ClassBytes> outputs = new LinkedHashMap<>();

    InMemoryFileManager(StandardJavaFileManager delegate) {
        super(delegate);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        if (location == StandardLocation.CLASS_OUTPUT && kind == JavaFileObject.Kind.CLASS) {
            ClassBytes output = new ClassBytes(className);
            outputs.put(className, output);
            return output;
        }
        throw new IOException("Запись вне CLASS_OUTPUT запрещена: " + location + " / " + className);
    }

    @Override
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling)
            throws IOException {
        throw new IOException("Запись ресурсов запрещена: " + packageName + "/" + relativeName);
    }

    Map<String, byte[]> compiledClasses() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        outputs.forEach((name, output) -> result.put(name, output.toByteArray()));
        return result;
    }
}
