package city.player;

import city.subroutine.levels.energy.api.Battery;
import city.subroutine.levels.energy.api.EnergyGrid;

/** Deadlock: замки захватываются в порядке аргументов — встречные переводы ждут друг друга вечно. */
public final class EnergyBank implements EnergyGrid {

    @Override
    public boolean transfer(Battery from, Battery to, long amount) {
        if (from == null || to == null || from == to || amount <= 0) {
            throw new IllegalArgumentException("Некорректный перевод");
        }
        synchronized (from) {
            synchronized (to) {
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
