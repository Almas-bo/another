package city.player;

import city.subroutine.levels.water.api.FlowMeter;
import city.subroutine.levels.water.api.Valve;
import city.subroutine.levels.water.api.ValveBoard;
import city.subroutine.levels.water.api.ValveJammedException;

import java.util.List;

/** Эталон water-01: try-with-resources закрывает задвижку и сохраняет suppressed-исключения. */
public final class WaterStation implements FlowMeter {

    @Override
    public long measureTotal(ValveBoard board, List<String> valveIds) throws ValveJammedException {
        if (board == null || valveIds == null) {
            throw new IllegalArgumentException("Пульт и список задвижек обязательны");
        }
        long total = 0;
        for (String id : valveIds) {
            try (Valve valve = board.open(id)) {
                total += valve.flowRate();
            }
        }
        return total;
    }
}
