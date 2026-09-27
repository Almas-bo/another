package city.player;

import city.subroutine.levels.warehouse.api.Container;
import city.subroutine.levels.warehouse.api.Storage;

import java.util.ArrayDeque;

/** Нарушение LSP: усиленное предусловие (исключение вместо false) и remove() вместо poll(). */
public final class ColdStorage implements Storage {

    private final ArrayDeque<Container> containers = new ArrayDeque<>();

    @Override
    public int capacity() {
        return 8;
    }

    @Override
    public boolean offer(Container container) {
        if (container.temperature() > -18.0) {
            throw new IllegalArgumentException("Тёплые контейнеры запрещены: " + container.id());
        }
        if (containers.size() == 8) {
            return false;
        }
        containers.addLast(container);
        return true;
    }

    @Override
    public Container poll() {
        return containers.removeFirst();
    }

    @Override
    public int size() {
        return containers.size();
    }
}
