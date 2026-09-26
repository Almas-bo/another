package city.subroutine.sandbox.host;

import city.subroutine.sandbox.api.CodeRunnerService;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.ExecutionResult;

import java.io.IOException;
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

    private final WorkerPool pool;
    private final Semaphore permits;
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultCodeRunnerService(RunnerConfig config) {
        Objects.requireNonNull(config, "config");
        this.pool = new WorkerPool(new WorkerLauncher(config), config.prewarmedWorkers(), config.poolHeapMegabytes(),
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
