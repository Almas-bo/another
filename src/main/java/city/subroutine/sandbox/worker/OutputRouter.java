package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.util.BoundedBuffer;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Перехват System.out/System.err игрока. Вывод текущего теста пишется в его ограниченный буфер;
 * вывод вне тестов (фоновые потоки после завершения теста) отбрасывается.
 */
final class OutputRouter {

    private final RoutingStream routing = new RoutingStream();
    private final PrintStream printStream = new PrintStream(routing, true, StandardCharsets.UTF_8);

    private OutputRouter() {
    }

    static OutputRouter install() {
        OutputRouter router = new OutputRouter();
        System.setOut(router.printStream);
        System.setErr(router.printStream);
        System.setIn(InputStream.nullInputStream());
        return router;
    }

    /** @param target буфер или {@code null}, чтобы отбрасывать вывод */
    void redirectTo(BoundedBuffer target) {
        printStream.flush();
        routing.target = target;
    }

    private static final class RoutingStream extends OutputStream {

        private volatile BoundedBuffer target;

        @Override
        public void write(int b) {
            BoundedBuffer current = target;
            if (current != null) {
                current.write(b);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            BoundedBuffer current = target;
            if (current != null) {
                current.write(bytes, offset, length);
            }
        }
    }
}
