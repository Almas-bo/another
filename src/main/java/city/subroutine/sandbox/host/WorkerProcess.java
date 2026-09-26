package city.subroutine.sandbox.host;

import city.subroutine.sandbox.protocol.Frame;
import city.subroutine.sandbox.protocol.FrameIO;
import city.subroutine.sandbox.protocol.FrameType;
import city.subroutine.sandbox.util.BoundedBuffer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Хостовая сторона одного процесса-песочницы. stdout читается отдельным потоком в очередь кадров,
 * stderr непрерывно вычитывается в ограниченный буфер (иначе заполненный pipe заблокировал бы воркер).
 */
final class WorkerProcess implements AutoCloseable {

    /** Событие из канала воркера. */
    sealed interface Inbound permits Received, Closed {
    }

    record Received(Frame frame) implements Inbound {
    }

    /** Канал закрыт: чисто ({@code error == null}) или из-за повреждённых данных. */
    record Closed(IOException error) implements Inbound {
    }

    private final Process process;
    private final Path workDirectory;
    private final int heapMegabytes;
    private final DataOutputStream stdin;
    private final BlockingQueue<Inbound> inbound = new LinkedBlockingQueue<>();
    private final BoundedBuffer stderr;
    private final CountDownLatch stderrDrained = new CountDownLatch(1);

    WorkerProcess(Process process, Path workDirectory, int heapMegabytes, int maxLogBytes) {
        this.process = process;
        this.workDirectory = workDirectory;
        this.heapMegabytes = heapMegabytes;
        this.stdin = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
        this.stderr = new BoundedBuffer(maxLogBytes);
        Thread.ofVirtual().name("worker-stdout-" + process.pid()).start(this::pumpFrames);
        Thread.ofVirtual().name("worker-stderr-" + process.pid()).start(this::drainStderr);
    }

    long pid() {
        return process.pid();
    }

    int heapMegabytes() {
        return heapMegabytes;
    }

    boolean isAlive() {
        return process.isAlive();
    }

    void send(Frame frame) throws IOException {
        FrameIO.write(stdin, frame);
    }

    /** @return событие или {@code null}, если за {@code timeoutNanos} ничего не пришло */
    Inbound poll(long timeoutNanos) throws InterruptedException {
        return inbound.poll(Math.max(0, timeoutNanos), TimeUnit.NANOSECONDS);
    }

    /** Ждёт кадр HELLO: процесс стартовал, javac прогрет. */
    void awaitHello(Duration timeout) throws IOException, TimeoutException, InterruptedException {
        Inbound event = poll(timeout.toNanos());
        if (event == null) {
            throw new TimeoutException("Воркер не стартовал за " + timeout.toMillis() + " мс");
        }
        if (event instanceof Received(Frame frame) && frame.type() == FrameType.HELLO) {
            return;
        }
        String reason = event instanceof Closed(IOException error) && error != null ? error.getMessage() : "канал закрыт";
        int exitCode = awaitExit(Duration.ofSeconds(2));
        throw new IOException("Воркер не прислал HELLO (" + reason + ", код завершения " + exitCode + "). stderr: "
                + sandboxLog());
    }

    /** @return код завершения или -1, если процесс не завершился за {@code timeout} */
    int awaitExit(Duration timeout) throws InterruptedException {
        if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return process.exitValue();
        }
        return -1;
    }

    /**
     * Хвост stderr. Если процесс уже завершился, сначала дожидается, пока поток-читатель заберёт
     * остаток pipe — иначе причина падения (например, сообщение JVM об OOM) терялась бы.
     */
    String sandboxLog() {
        if (!process.isAlive()) {
            try {
                stderrDrained.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return stderr.contentAsString() + (stderr.truncated() ? "\n… (лог обрезан)" : "");
    }

    /** Уничтожает процесс (и потомков, если префикс команды запустил обёртку) и удаляет рабочий каталог. */
    @Override
    public void close() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteRecursively(workDirectory);
    }

    private void pumpFrames() {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(process.getInputStream(), 64 * 1024))) {
            while (true) {
                Frame frame = FrameIO.read(in);
                if (frame == null) {
                    inbound.add(new Closed(null));
                    return;
                }
                inbound.add(new Received(frame));
            }
        } catch (IOException e) {
            inbound.add(new Closed(e));
        }
    }

    private void drainStderr() {
        byte[] chunk = new byte[4096];
        try (InputStream err = process.getErrorStream()) {
            int read;
            while ((read = err.read(chunk)) != -1) {
                stderr.write(chunk, 0, read);
            }
        } catch (IOException ignored) {
            // процесс уничтожен
        } finally {
            stderrDrained.countDown();
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // рабочий каталог во временной папке; остатки уберёт ОС
        }
    }
}
