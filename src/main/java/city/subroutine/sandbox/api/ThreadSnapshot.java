package city.subroutine.sandbox.api;

import java.util.List;
import java.util.Objects;

/**
 * Снимок потока на момент таймаута. Данные для Deadlock Visualizer: кто какой монитор ждёт и кто им владеет.
 *
 * @param state         {@link Thread.State#name()}
 * @param lockName      монитор или синхронизатор, которого ждёт поток, либо {@code null}
 * @param lockOwnerName владелец этого монитора, либо {@code null}
 * @param deadlocked    поток входит в цикл взаимной блокировки
 */
public record ThreadSnapshot(
        String name,
        String state,
        boolean deadlocked,
        String lockName,
        String lockOwnerName,
        List<StackFrameInfo> frames) {

    public ThreadSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
    }
}
