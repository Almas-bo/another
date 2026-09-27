package city.player;

import city.subroutine.levels.energy.api.Battery;
import city.subroutine.levels.energy.api.EnergyGrid;

/** Эталон energy-01: замки захватываются в едином глобальном порядке (по id) — цикл ожидания невозможен. */
public final class EnergyBank implements EnergyGrid {

    @Override
    public boolean transfer(Battery from, Battery to, long amount) {
        if (from == null || to == null || from == to || amount <= 0) {
            throw new IllegalArgumentException("Некорректный перевод");
        }
        Battery first = from.id() < to.id() ? from : to;
        Battery second = first == from ? to : from;
        synchronized (first) {
            synchronized (second) {
                if (from.charge() < amount) {
                    return false;
                }
                from.withdraw(amount);
                to.deposit(amount);
                return true;
            }
        }
    }
}
