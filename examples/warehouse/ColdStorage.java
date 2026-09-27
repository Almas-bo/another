package city.player;

import city.subroutine.levels.warehouse.api.Container;
import city.subroutine.levels.warehouse.api.Storage;

import java.util.ArrayDeque;
import java.util.Objects;

/** Эталон warehouse-01: ограничения склада выражены через false, как обещает контракт Storage. */
public final class ColdStorage implements Storage {

    private static final int CAPACITY = 8;
    private static final double MAX_TEMPERATURE = -18.0;

    private final ArrayDeque<Container> containers = new ArrayDeque<>(CAPACITY);

    @Override
    public int capacity() {
        return CAPACITY;
    }

    @Override
    public boolean offer(Container container) {
        Objects.requireNonNull(container, "container");
        if (container.temperature() > MAX_TEMPERATURE || containers.size() >= CAPACITY) {
            return false;
        }
        containers.addLast(container);
        return true;
    }

    @Override
    public Container poll() {
        return containers.pollFirst();
    }

    @Override
    public int size() {
        return containers.size();
    }
}
