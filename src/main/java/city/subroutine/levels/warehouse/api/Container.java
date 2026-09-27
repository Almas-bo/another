package city.subroutine.levels.warehouse.api;

import java.util.Objects;

/**
 * Контейнер с грузом.
 *
 * @param id          номер контейнера
 * @param temperature требуемая температура хранения, °C
 */
public record Container(String id, double temperature) {

    public Container {
        Objects.requireNonNull(id, "id");
    }
}
