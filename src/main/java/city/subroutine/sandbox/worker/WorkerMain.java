package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.compiler.InMemoryCompiler;
import city.subroutine.sandbox.protocol.FinishedReport;
import city.subroutine.sandbox.protocol.Frame;
import city.subroutine.sandbox.protocol.FrameIO;
import city.subroutine.sandbox.protocol.FrameType;
import city.subroutine.sandbox.protocol.ProtocolException;
import city.subroutine.sandbox.protocol.WireCodec;
import city.subroutine.sandbox.protocol.WorkerVerdict;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Точка входа процесса-песочницы. Процесс одноразовый: один запрос — один процесс.
 *
 * <p>Жизненный цикл: захват stdin/stdout под протокол → подмена System.out/err/in → (опционально) прогрев javac →
 * HELLO → ожидание REQUEST → {@link WorkerSession} → FINISHED → {@link Runtime#halt(int)}.
 * {@code halt} вместо {@code exit}: не ждём не-daemon потоков игрока и не запускаем shutdown hooks.
 */
public final class WorkerMain {

    public static final String PREWARM_FLAG = "--prewarm";

    public static final int EXIT_OK = 0;
    /** JVM с -XX:+ExitOnOutOfMemoryError завершается с кодом 3. */
    public static final int EXIT_OUT_OF_MEMORY = 3;
    public static final int EXIT_PROTOCOL_ERROR = 10;
    public static final int EXIT_HALTED_AFTER_TIMEOUT = 11;
    public static final int EXIT_THREAD_LIMIT = 12;
    public static final int EXIT_INTERNAL_ERROR = 13;

    private WorkerMain() {
    }

    public static void main(String[] args) {
        FrameWriter writer = new FrameWriter(new FileOutputStream(FileDescriptor.out));
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(FileDescriptor.in)));
        OutputRouter router = OutputRouter.install();
        List<String> classpath = Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));

        int exitCode;
        try {
            if (Arrays.asList(args).contains(PREWARM_FLAG)) {
                InMemoryCompiler.warmUp(classpath);
                System.gc();
            }
            writer.send(FrameType.HELLO, o -> o.writeLong(ProcessHandle.current().pid()));
            Frame frame = FrameIO.read(in);
            if (frame == null) {
                exitCode = EXIT_OK; // хост закрыл канал, пока воркер ждал в пуле
            } else if (frame.type() != FrameType.REQUEST) {
                throw new ProtocolException("Ожидался кадр REQUEST, получен " + frame.type());
            } else {
                ExecutionRequest request = FrameIO.decode(frame, WireCodec::readRequest);
                exitCode = new WorkerSession(request, writer, router, classpath).run();
            }
        } catch (ProtocolException e) {
            writer.finish(new FinishedReport(WorkerVerdict.SANDBOX_FAILURE, "Ошибка протокола: " + e.getMessage(),
                    Optional.empty()));
            exitCode = EXIT_PROTOCOL_ERROR;
        } catch (Throwable t) {
            writer.finish(new FinishedReport(WorkerVerdict.SANDBOX_FAILURE, "Внутренняя ошибка песочницы: " + t,
                    Optional.of(Reports.internal(t))));
            exitCode = EXIT_INTERNAL_ERROR;
        }
        writer.flushQuietly();
        Runtime.getRuntime().halt(exitCode);
    }
}
