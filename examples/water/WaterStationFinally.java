package city.player;

import city.subroutine.levels.water.api.FlowMeter;
import city.subroutine.levels.water.api.Valve;
import city.subroutine.levels.water.api.ValveBoard;
import city.subroutine.levels.water.api.ValveJammedException;

import java.util.List;

/** Классическая ошибка: close() в finally маскирует основное исключение, если сам падает. */
public final class WaterStation implements FlowMeter {

    @Override
    public long measureTotal(ValveBoard board, List<String> valveIds) throws ValveJammedException {
        if (board == null || valveIds == null) {
            throw new IllegalArgumentException("Пульт и список задвижек обязательны");
        }
        long total = 0;
        for (String id : valveIds) {
            Valve valve = board.open(id);
            try {
                total += valve.flowRate();
            } finally {
                valve.close();
            }
        }
        return total;
    }
}
