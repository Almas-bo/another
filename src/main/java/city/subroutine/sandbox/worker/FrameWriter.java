package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.protocol.FinishedReport;
import city.subroutine.sandbox.protocol.FrameIO;
import city.subroutine.sandbox.protocol.FrameType;
import city.subroutine.sandbox.protocol.WireCodec;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Единственный писатель в протокольный канал (исходный stdout процесса). Синхронизирован:
 * кадры пишут основной поток и сторожевой поток. Код игрока доступа к каналу не имеет —
 * System.out подменён, а FileDescriptor/FileOutputStream запрещены политикой.
 */
final class FrameWriter {

    private final DataOutputStream out;
    private boolean finished;

    FrameWriter(OutputStream raw) {
        this.out = new DataOutputStream(new BufferedOutputStream(raw, 64 * 1024));
    }

    synchronized void send(FrameType type, FrameIO.Body body) throws IOException {
        if (finished) {
            throw new IllegalStateException("Кадр FINISHED уже отправлен");
        }
        FrameIO.write(out, FrameIO.frame(type, body));
    }

    /** Отправляет финальный кадр ровно один раз; повторные вызовы игнорируются. */
    synchronized boolean finish(FinishedReport report) {
        if (finished) {
            return false;
        }
        try {
            FrameIO.write(out, FrameIO.frame(FrameType.FINISHED, o -> WireCodec.writeFinished(o, report)));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            finished = true;
        }
    }

    synchronized void flushQuietly() {
        try {
            out.flush();
        } catch (IOException ignored) {
            // хост ушёл — сообщать некому
        }
    }
}
