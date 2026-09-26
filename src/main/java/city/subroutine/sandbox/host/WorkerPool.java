package city.subroutine.sandbox.host;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Пул прогретых одноразовых воркеров. Старт JVM и первая компиляция javac занимают ~1 с —
 * пул прячет эту задержку от игрока. Воркер никогда не используется повторно: после запроса
 * процесс уничтожается, а пул в фоне запускает замену.
 */
final class WorkerPool implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WorkerPool.class.getName());

    private final WorkerLauncher launcher;
    private final int targetSize;
    private final int heapMegabytes;
    private final Duration startupTimeout;
    private final BlockingDeque<WorkerProcess> idle = new LinkedBlockingDeque<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final ExecutorService spawner = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    WorkerPool(WorkerLauncher launcher, int targetSize, int heapMegabytes, Duration startupTimeout) {
        this.launcher = launcher;
        this.targetSize = targetSize;
        this.heapMegabytes = heapMegabytes;
        this.startupTimeout = startupTimeout;
    }

    void start() {
        for (int i = 0; i < targetSize; i++) {
            refillAsync();
        }
    }

    /** Готовый воркер (прислал HELLO) с нужным размером кучи. Владение переходит вызывающему. */
    WorkerProcess acquire(int requestedHeapMegabytes) throws IOException, TimeoutException, InterruptedException {
        if (closed) {
            throw new IllegalStateException("Пул закрыт");
        }
        if (requestedHeapMegabytes == heapMegabytes) {
            WorkerProcess candidate;
            while ((candidate = idle.pollFirst()) != null) {
                refillAsync();
                if (candidate.isAlive()) {
                    return candidate;
                }
                candidate.close();
            }
            refillAsync();
        }
        return launchReady(requestedHeapMegabytes);
    }

    private WorkerProcess launchReady(int heap) throws IOException, TimeoutException, InterruptedException {
        WorkerProcess worker = launcher.launch(heap);
        try {
            worker.awaitHello(startupTimeout);
            return worker;
        } catch (IOException | TimeoutException | InterruptedException | RuntimeException e) {
            worker.close();
            throw e;
        }
    }

    private void refillAsync() {
        if (closed || targetSize == 0) {
            return;
        }
        if (idle.size() + pending.incrementAndGet() > targetSize) {
            pending.decrementAndGet();
            return;
        }
        try {
            spawner.execute(() -> {
                try {
                    WorkerProcess worker = launchReady(heapMegabytes);
                    idle.addLast(worker);
                    if (closed && idle.remove(worker)) {
                        worker.close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Не удалось подготовить воркер песочницы", e);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            pending.decrementAndGet(); // исполнитель уже закрыт
        }
    }

    @Override
    public void close() {
        closed = true;
        spawner.shutdownNow();
        WorkerProcess worker;
        while ((worker = idle.pollFirst()) != null) {
            worker.close();
        }
    }
}
