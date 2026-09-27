package city.subroutine.sandbox.host;

import city.subroutine.sandbox.api.CompilationDiagnostic;
import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.ExecutionMetrics;
import city.subroutine.sandbox.api.ExecutionMode;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.ExecutionResult;
import city.subroutine.sandbox.api.ExecutionStatus;
import city.subroutine.sandbox.api.PolicyViolation;
import city.subroutine.sandbox.api.TestMetrics;
import city.subroutine.sandbox.api.TestOutcome;
import city.subroutine.sandbox.api.TestStatus;
import city.subroutine.sandbox.protocol.CompilationReport;
import city.subroutine.sandbox.protocol.FinishedReport;
import city.subroutine.sandbox.protocol.Frame;
import city.subroutine.sandbox.protocol.FrameIO;
import city.subroutine.sandbox.protocol.FrameType;
import city.subroutine.sandbox.protocol.ProtocolException;
import city.subroutine.sandbox.protocol.TestDescriptor;
import city.subroutine.sandbox.protocol.WireCodec;
import city.subroutine.sandbox.worker.WorkerMain;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Проведение одного запроса через воркер: отправка REQUEST, приём потоковых кадров с дедлайнами
 * по фазам, классификация аварийного завершения процесса и сборка {@link ExecutionResult}.
 *
 * <p>Воркер сам следит за лимитом на тест; хостовые дедлайны — страховка на случай, если процесс
 * перестал отвечать целиком (например, завис в javac или GC-трэшинге у границы -Xmx).
 */
final class ExecutionSession {

    private enum Phase { COMPILING, EXECUTING }

    private static final Duration EXIT_WAIT = Duration.ofSeconds(3);

    private final WorkerProcess worker;
    private final ExecutionRequest request;

    private Phase phase = Phase.COMPILING;
    private CompilationReport compilation;
    private List<PolicyViolation> violations = List.of();
    private List<TestDescriptor> descriptors = List.of();
    private final List<TestOutcome> outcomes = new ArrayList<>();
    private FinishedReport finished;
    private boolean hostTimeout;
    private IOException channelError;
    private long executionStarted;
    private long executionEnded;

    ExecutionSession(WorkerProcess worker, ExecutionRequest request) {
        this.worker = worker;
        this.request = request;
    }

    ExecutionResult run() throws InterruptedException {
        try {
            worker.send(FrameIO.frame(FrameType.REQUEST, o -> WireCodec.writeRequest(o, request)));
        } catch (IOException e) {
            return ExecutionResult.sandboxFailure(request.requestId(),
                    "Не удалось передать запрос в песочницу: " + e.getMessage(), worker.sandboxLog());
        }
        long deadline = System.nanoTime() + request.limits().compileTimeout().toNanos();
        loop:
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                hostTimeout = true;
                break;
            }
            WorkerProcess.Inbound event = worker.poll(remaining);
            if (event == null) {
                continue;
            }
            switch (event) {
                case WorkerProcess.Closed closed -> {
                    channelError = closed.error();
                    break loop;
                }
                case WorkerProcess.Received received -> {
                    try {
                        if (handle(received.frame())) {
                            break loop;
                        }
                    } catch (ProtocolException e) {
                        channelError = e;
                        break loop;
                    }
                    if (phase == Phase.EXECUTING && executionStarted == 0) {
                        executionStarted = System.nanoTime();
                        deadline = executionStarted + request.limits().totalTimeout().toNanos();
                    }
                }
            }
        }
        executionEnded = System.nanoTime();
        int exitCode;
        if (hostTimeout || channelError instanceof ProtocolException) {
            worker.close();
            exitCode = worker.awaitExit(Duration.ZERO);
        } else {
            exitCode = worker.awaitExit(EXIT_WAIT);
        }
        return assemble(exitCode);
    }

    /** @return true, если получен финальный кадр */
    private boolean handle(Frame frame) throws ProtocolException {
        switch (frame.type()) {
            case COMPILATION -> {
                compilation = FrameIO.decode(frame, WireCodec::readCompilation);
                if (compilation.success()) {
                    phase = Phase.EXECUTING;
                }
            }
            case POLICY -> violations = FrameIO.decode(frame, WireCodec::readViolations);
            case SUITE_STARTED -> descriptors = FrameIO.decode(frame, WireCodec::readDescriptors);
            case TEST_RESULT -> {
                if (outcomes.size() >= descriptors.size()) {
                    throw new ProtocolException("Результат теста без объявления в SUITE_STARTED");
                }
                outcomes.add(FrameIO.decode(frame, WireCodec::readOutcome));
            }
            case FINISHED -> {
                finished = FrameIO.decode(frame, WireCodec::readFinished);
                return true;
            }
            case HELLO, REQUEST -> throw new ProtocolException("Неожиданный кадр от воркера: " + frame.type());
        }
        return false;
    }

    private ExecutionResult assemble(int exitCode) {
        ExecutionStatus status;
        String detail;
        TestStatus interruptedTestStatus = null;
        Optional<ErrorReport> fatal = Optional.empty();

        if (finished != null) {
            detail = finished.detail();
            fatal = finished.fatalError();
            status = switch (finished.verdict()) {
                case COMPLETED -> request.mode() == ExecutionMode.CHECK
                        ? ExecutionStatus.COMPILED
                        : allPassed() ? ExecutionStatus.SUCCESS : ExecutionStatus.TESTS_FAILED;
                case COMPILATION_ERROR -> ExecutionStatus.COMPILATION_ERROR;
                case POLICY_VIOLATION -> ExecutionStatus.POLICY_VIOLATION;
                case CONTRACT_VIOLATION -> ExecutionStatus.CONTRACT_VIOLATION;
                case TIMEOUT -> ExecutionStatus.TIMEOUT;
                case DEADLOCK -> ExecutionStatus.DEADLOCK;
                case THREAD_LIMIT_EXCEEDED -> {
                    interruptedTestStatus = TestStatus.THREAD_LIMIT_EXCEEDED;
                    yield ExecutionStatus.THREAD_LIMIT_EXCEEDED;
                }
                case SANDBOX_FAILURE -> {
                    interruptedTestStatus = TestStatus.SANDBOX_CRASH;
                    yield ExecutionStatus.SANDBOX_FAILURE;
                }
            };
        } else if (hostTimeout) {
            status = ExecutionStatus.TIMEOUT;
            interruptedTestStatus = TestStatus.TIMEOUT;
            detail = phase == Phase.COMPILING
                    ? "Компиляция не уложилась в " + request.limits().compileTimeout().toMillis() + " мс"
                    : "Песочница не уложилась в общий лимит " + request.limits().totalTimeout().toMillis() + " мс";
        } else if (exitCode == WorkerMain.EXIT_OUT_OF_MEMORY) {
            status = ExecutionStatus.MEMORY_LIMIT_EXCEEDED;
            interruptedTestStatus = TestStatus.MEMORY_LIMIT_EXCEEDED;
            detail = phase == Phase.COMPILING
                    ? "Не хватило памяти на компиляцию (" + request.limits().heapMegabytes() + " МБ)"
                    : "Исчерпана куча песочницы (" + request.limits().heapMegabytes() + " МБ)";
        } else {
            status = ExecutionStatus.SANDBOX_FAILURE;
            interruptedTestStatus = TestStatus.SANDBOX_CRASH;
            detail = "Процесс песочницы аварийно завершился (код " + exitCode + ")"
                    + (channelError != null ? ": " + channelError.getMessage() : "");
        }

        List<TestOutcome> tests = mergeTests(interruptedTestStatus, detail);
        List<CompilationDiagnostic> diagnostics = compilation == null ? List.of() : compilation.diagnostics();
        return new ExecutionResult(request.requestId(), status, detail, diagnostics, violations, tests,
                metrics(exitCode), fatal, worker.sandboxLog());
    }

    private boolean allPassed() {
        return !outcomes.isEmpty() && outcomes.size() == descriptors.size()
                && outcomes.stream().allMatch(o -> o.status().passed());
    }

    /** Полный список тестов в порядке набора: полученные результаты, прерванный тест и пропущенные. */
    private List<TestOutcome> mergeTests(TestStatus interruptedStatus, String detail) {
        Map<String, TestOutcome> byId = new HashMap<>();
        for (TestOutcome outcome : outcomes) {
            byId.put(outcome.id(), outcome);
        }
        List<TestOutcome> merged = new ArrayList<>(descriptors.size());
        boolean interruptedAssigned = false;
        for (TestDescriptor descriptor : descriptors) {
            TestOutcome outcome = byId.get(descriptor.id());
            if (outcome != null) {
                merged.add(outcome);
            } else if (interruptedStatus != null && !interruptedAssigned) {
                merged.add(TestOutcome.notReported(descriptor.id(), descriptor.title(), interruptedStatus, detail));
                interruptedAssigned = true;
            } else {
                merged.add(TestOutcome.notReported(descriptor.id(), descriptor.title(), TestStatus.SKIPPED,
                        "Не запускался: песочница остановлена на предыдущем тесте"));
            }
        }
        return merged;
    }

    private ExecutionMetrics metrics(int exitCode) {
        long cpu = 0;
        long allocated = 0;
        long peak = 0;
        long gcCount = 0;
        long gcTime = 0;
        for (TestOutcome outcome : outcomes) {
            TestMetrics m = outcome.metrics();
            cpu += m.cpuNanos();
            allocated += m.allocatedBytes();
            peak = Math.max(peak, m.peakHeapBytes());
            gcCount += m.gcCount();
            gcTime += m.gcTimeMillis();
        }
        long compileNanos = compilation == null ? 0 : compilation.compileNanos();
        long executionWall = executionStarted == 0 ? 0 : executionEnded - executionStarted;
        return new ExecutionMetrics(compileNanos, executionWall, cpu, allocated, peak, gcCount, gcTime, exitCode);
    }
}
