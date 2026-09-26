package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.PolicyViolation;
import city.subroutine.sandbox.api.TestOutcome;
import city.subroutine.sandbox.api.TestStatus;
import city.subroutine.sandbox.compiler.CompilationOutput;
import city.subroutine.sandbox.compiler.InMemoryCompiler;
import city.subroutine.sandbox.policy.BytecodeVerifier;
import city.subroutine.sandbox.policy.ReflectiveTypeHierarchy;
import city.subroutine.sandbox.policy.SandboxPolicy;
import city.subroutine.sandbox.protocol.CompilationReport;
import city.subroutine.sandbox.protocol.FinishedReport;
import city.subroutine.sandbox.protocol.FrameType;
import city.subroutine.sandbox.protocol.TestDescriptor;
import city.subroutine.sandbox.protocol.WireCodec;
import city.subroutine.sandbox.protocol.WorkerVerdict;
import city.subroutine.sandbox.testing.TestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Конвейер одного запроса внутри воркера:
 * компиляция → проверка байткода → загрузка в изолированный ClassLoader → проверка контракта → тесты.
 * Каждый этап сразу отправляет свой кадр хосту, поэтому при аварийном завершении процесса
 * (OOM, лимит потоков) частичные результаты не теряются.
 */
final class WorkerSession {

    private final ExecutionRequest request;
    private final FrameWriter writer;
    private final OutputRouter router;
    private final List<String> classpath;

    WorkerSession(ExecutionRequest request, FrameWriter writer, OutputRouter router, List<String> classpath) {
        this.request = request;
        this.writer = writer;
        this.router = router;
        this.classpath = classpath;
    }

    /** @return код завершения процесса */
    int run() throws IOException {
        ClassLoader trustedLoader = WorkerSession.class.getClassLoader();
        SandboxPolicy policy = SandboxPolicy.standard(request.playerPackage(), request.allowedApiPackages());

        // --- 1. Компиляция в памяти
        CompilationOutput compiled = new InMemoryCompiler(classpath).compile(request.sources());
        CompilationReport report =
                new CompilationReport(compiled.success(), compiled.diagnostics(), compiled.compileNanos());
        writer.send(FrameType.COMPILATION, o -> WireCodec.writeCompilation(o, report));
        if (!compiled.success()) {
            return finish(WorkerVerdict.COMPILATION_ERROR, "Код не скомпилирован", null, WorkerMain.EXIT_OK);
        }

        // --- 2. Статическая проверка байткода
        List<PolicyViolation> violations =
                new BytecodeVerifier(policy, new ReflectiveTypeHierarchy(trustedLoader)).verify(compiled.classes());
        writer.send(FrameType.POLICY, o -> WireCodec.writeViolations(o, violations));
        if (!violations.isEmpty()) {
            return finish(WorkerVerdict.POLICY_VIOLATION,
                    "Код обращается к API, недоступному в песочнице: " + violations.size() + " нарушений", null,
                    WorkerMain.EXIT_OK);
        }

        // --- 3. Изолированная загрузка и проверка контракта
        Map<String, byte[]> classes = compiled.classes();
        compiled = null;
        System.gc(); // освобождаем кучу от javac до замеров кода игрока
        SandboxClassLoader loader = new SandboxClassLoader(classes, policy, trustedLoader);
        TargetBinding binding;
        try {
            binding = TargetBinding.resolve(request.entryPoint(), loader);
        } catch (ContractException e) {
            return finish(WorkerVerdict.CONTRACT_VIOLATION, e.getMessage(), null, WorkerMain.EXIT_OK);
        }

        // --- 4. Набор тестов уровня
        List<TestCase> cases;
        try {
            cases = SuiteLoader.load(request.testSuiteClass(), trustedLoader);
        } catch (SuiteLoader.SuiteException e) {
            return finish(WorkerVerdict.SANDBOX_FAILURE, e.getMessage(), Reports.of(e, request.playerPackage()),
                    WorkerMain.EXIT_OK);
        }
        List<TestDescriptor> descriptors = cases.stream().map(c -> new TestDescriptor(c.id(), c.title())).toList();
        writer.send(FrameType.SUITE_STARTED, o -> WireCodec.writeDescriptors(o, descriptors));

        // --- 5. Тесты
        JvmProbe probe = new JvmProbe();
        ThreadWatchdog watchdog = ThreadWatchdog.start(probe, request.limits().maxThreads(), count -> {
            writer.finish(new FinishedReport(WorkerVerdict.THREAD_LIMIT_EXCEEDED,
                    "Код создал " + count + " потоков при лимите " + request.limits().maxThreads(), Optional.empty()));
            writer.flushQuietly();
            Runtime.getRuntime().halt(WorkerMain.EXIT_THREAD_LIMIT);
        });
        TestCaseExecutor executor = new TestCaseExecutor(binding, router, probe,
                request.limits().perTestTimeout().toNanos(), request.limits().maxOutputBytesPerTest(),
                request.playerPackage());
        for (int i = 0; i < cases.size(); i++) {
            TestOutcome outcome = executor.execute(cases.get(i), i);
            writer.send(FrameType.TEST_RESULT, o -> WireCodec.writeOutcome(o, outcome));
            if (outcome.status() == TestStatus.TIMEOUT || outcome.status() == TestStatus.DEADLOCK) {
                // Зависший поток игрока остановить нельзя — останавливаем процесс целиком.
                WorkerVerdict verdict = outcome.status() == TestStatus.DEADLOCK
                        ? WorkerVerdict.DEADLOCK : WorkerVerdict.TIMEOUT;
                return finish(verdict, outcome.message(), null, WorkerMain.EXIT_HALTED_AFTER_TIMEOUT);
            }
        }
        watchdog.stop();
        return finish(WorkerVerdict.COMPLETED, null, null, WorkerMain.EXIT_OK);
    }

    private int finish(WorkerVerdict verdict, String detail, ErrorReport fatal, int exitCode) {
        writer.finish(new FinishedReport(verdict, detail, Optional.ofNullable(fatal)));
        return exitCode;
    }
}
