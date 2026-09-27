package city.subroutine.sandbox.host;

import city.subroutine.sandbox.api.CodeRunnerService;
import city.subroutine.sandbox.api.DebugResult;
import city.subroutine.sandbox.api.DebugTrace;
import city.subroutine.sandbox.api.ExecutionMode;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.ExecutionResult;
import city.subroutine.sandbox.api.SandboxLimits;
import city.subroutine.sandbox.host.debug.DebugAttach;
import city.subroutine.sandbox.host.debug.TraceRecorder;
import com.sun.jdi.VirtualMachine;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Реализация {@link CodeRunnerService}: каждый запрос исполняется в одноразовом JVM-процессе.
 *
 * <p>Почему процесс, а не поток: в Java 21 SecurityManager отключён, а Thread.stop() не работает,
 * поэтому внутри одного JVM нельзя ни прервать зависший код, ни ограничить ему кучу. Процесс даёт
 * жёсткие гарантии: {@code -Xmx}, уничтожение по таймауту, изолированное окружение, и может быть
 * дополнительно обёрнут в ОС-изоляцию через {@link RunnerConfig#commandPrefix()}.
 */
public final class DefaultCodeRunnerService implements CodeRunnerService {

    /** Лимит шагов трассы: больше игроку не пролистать, а запись каждого шага стоит ~0.1–1 мс. */
    public static final int MAX_TRACE_STEPS = 5_000;

    private final RunnerConfig config;
    private final WorkerLauncher launcher;
    private final WorkerPool pool;
    private final Semaphore permits;
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultCodeRunnerService(RunnerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.launcher = new WorkerLauncher(config);
        this.pool = new WorkerPool(launcher, config.prewarmedWorkers(), config.poolHeapMegabytes(),
                config.workerStartupTimeout());
        this.permits = new Semaphore(config.maxConcurrentExecutions(), true);
        pool.start();
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        ensureOpen();
        Optional<String> rejection = RequestValidator.validate(request);
        if (rejection.isPresent()) {
            return ExecutionResult.rejected(request.requestId(), rejection.get());
        }
        permits.acquire();
        try {
            WorkerProcess worker;
            try {
                worker = pool.acquire(request.limits().heapMegabytes());
            } catch (IOException | TimeoutException e) {
                return ExecutionResult.sandboxFailure(request.requestId(),
                        "Не удалось запустить песочницу: " + e.getMessage(), "");
            }
            try (worker) {
                return new ExecutionSession(worker, request).run();
            }
        } finally {
            permits.release();
        }
    }

    @Override
    public DebugResult debug(ExecutionRequest request, String testId) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(testId, "testId");
        ensureOpen();
        ExecutionRequest debugRequest = request
                .withMode(ExecutionMode.FULL)
                .withOnlyTestId(testId)
                .withLimits(debugLimits(request.limits()));
        Optional<String> rejection = RequestValidator.validate(debugRequest);
        if (rejection.isPresent()) {
            return new DebugResult(ExecutionResult.rejected(request.requestId(), rejection.get()), emptyTrace(testId));
        }
        permits.acquire();
        try (DebugAttach attach = DebugAttach.listen(config.workerStartupTimeout())) {
            WorkerProcess worker = launcher.launch(debugRequest.limits().heapMegabytes(),
                    List.of(attach.jvmAgentOption()), false);
            try (worker) {
                VirtualMachine vm = attach.accept();
                TraceRecorder recorder = new TraceRecorder(vm, request.playerPackage(), MAX_TRACE_STEPS);
                recorder.install();
                Thread recording = Thread.ofPlatform().daemon().name("jdi-trace-" + worker.pid()).start(recorder);
                vm.resume();
                worker.awaitHello(config.workerStartupTimeout());
                ExecutionResult result = new ExecutionSession(worker, debugRequest).run();
                worker.close(); // гарантирует VMDisconnect, даже если процесс ещё не вышел
                recording.join(Duration.ofSeconds(5));
                return new DebugResult(result, recorder.result(testId));
            }
        } catch (IOException | TimeoutException e) {
            return new DebugResult(ExecutionResult.sandboxFailure(request.requestId(),
                    "Не удалось запустить отладку: " + e.getMessage(), ""), emptyTrace(testId));
        } finally {
            permits.release();
        }
    }

    /** Запись трассы замедляет исполнение на порядки — лимиты времени расширяются. */
    private static SandboxLimits debugLimits(SandboxLimits limits) {
        Duration perTest = max(limits.perTestTimeout(), Duration.ofSeconds(30));
        Duration total = max(limits.totalTimeout(), perTest.plusSeconds(15));
        return limits.withTotalTimeout(total).withPerTestTimeout(perTest);
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static DebugTrace emptyTrace(String testId) {
        return new DebugTrace(testId, List.of(), false, MAX_TRACE_STEPS, "Трасса не записана");
    }

    @Override
    public CompletableFuture<ExecutionResult> executeAsync(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        ensureOpen();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execute(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            pool.close();
            asyncExecutor.shutdownNow();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("CodeRunnerService закрыт");
        }
    }
}
