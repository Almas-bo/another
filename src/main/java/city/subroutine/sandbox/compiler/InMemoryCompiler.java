package city.subroutine.sandbox.compiler;

import city.subroutine.sandbox.api.CompilationDiagnostic;
import city.subroutine.sandbox.api.SourceUnit;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Компиляция исходников игрока в памяти через {@link JavaCompiler}.
 *
 * <p>Обработка аннотаций отключена ({@code -proc:none}): иначе javac мог бы исполнить чужой код
 * процессоров аннотаций ещё до песочницы. Компиляция выполняется внутри процесса-воркера,
 * поэтому «компиляторные бомбы» ограничены тем же лимитом памяти и таймаутом, что и исполнение.
 */
public final class InMemoryCompiler {

    private static final int MAX_DIAGNOSTICS = 100;

    private final JavaCompiler javac;
    private final List<String> classpath;

    public InMemoryCompiler(List<String> classpath) {
        this.javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("javax.tools.JavaCompiler недоступен: песочнице нужен JDK, а не JRE");
        }
        this.classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
    }

    public CompilationOutput compile(List<SourceUnit> sources) {
        long started = System.nanoTime();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager standard = javac.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8);
        try (InMemoryFileManager fileManager = new InMemoryFileManager(standard)) {
            List<JavaFileObject> units = new ArrayList<>(sources.size());
            for (SourceUnit source : sources) {
                units.add(new StringSource(source));
            }
            List<String> options = List.of(
                    "--release", "21",
                    "-proc:none",
                    "-g",
                    "-parameters",
                    "-encoding", "UTF-8",
                    "-Xlint:all,-serial,-processing,-options",
                    "-Xmaxerrs", "50",
                    "-Xmaxwarns", "50",
                    "-classpath", String.join(File.pathSeparator, classpath));
            StringWriter javacLog = new StringWriter();
            boolean success = Boolean.TRUE.equals(
                    javac.getTask(javacLog, fileManager, collector, options, null, units).call());
            List<CompilationDiagnostic> diagnostics = convert(collector.getDiagnostics());
            if (!success && diagnostics.stream().noneMatch(d -> d.kind() == CompilationDiagnostic.Kind.ERROR)) {
                diagnostics = new ArrayList<>(diagnostics);
                diagnostics.add(new CompilationDiagnostic(CompilationDiagnostic.Kind.ERROR, "sandbox.javac.failed",
                        javacLog.toString().strip(), null, -1, -1, -1, -1));
            }
            Map<String, byte[]> classes = success ? fileManager.compiledClasses() : Map.of();
            return new CompilationOutput(success, diagnostics, classes, System.nanoTime() - started);
        } catch (IOException e) {
            throw new UncheckedIOException("Ошибка файлового менеджера javac", e);
        }
    }

    /** Прогрев javac в свежем процессе, пока воркер ждёт запрос в пуле: первая компиляция в JVM самая медленная. */
    public static void warmUp(List<String> classpath) {
        new InMemoryCompiler(classpath).compile(List.of(new SourceUnit("warmup.Warmup",
                "package warmup; public final class Warmup { int f(int x) { return x + 1; } }")));
    }

    private static List<CompilationDiagnostic> convert(List<Diagnostic<? extends JavaFileObject>> raw) {
        List<CompilationDiagnostic> result = new ArrayList<>(Math.min(raw.size(), MAX_DIAGNOSTICS));
        for (Diagnostic<? extends JavaFileObject> d : raw) {
            if (result.size() >= MAX_DIAGNOSTICS) {
                break;
            }
            String sourceClass = d.getSource() instanceof StringSource s ? s.className() : null;
            result.add(new CompilationDiagnostic(
                    kindOf(d.getKind()),
                    d.getCode() == null ? "compiler.unknown" : d.getCode(),
                    d.getMessage(Locale.ROOT),
                    sourceClass,
                    d.getLineNumber(),
                    d.getColumnNumber(),
                    d.getStartPosition(),
                    d.getEndPosition()));
        }
        return result;
    }

    private static CompilationDiagnostic.Kind kindOf(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> CompilationDiagnostic.Kind.ERROR;
            case WARNING, MANDATORY_WARNING -> CompilationDiagnostic.Kind.WARNING;
            case NOTE, OTHER -> CompilationDiagnostic.Kind.NOTE;
        };
    }
}
