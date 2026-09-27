package city.subroutine.levels.energy.api;

/**
 * Аккумулятор энергосети. <b>Не потокобезопасен</b>: синхронизацию обеспечивает вызывающий код.
 * Монитор аккумулятора ({@code synchronized (battery)}) можно использовать как его замок.
 */
public final class Battery {

    private final int id;
    private long charge;

    public Battery(int id, long charge) {
        if (charge < 0) {
            throw new IllegalArgumentException("Отрицательный заряд");
        }
        this.id = id;
        this.charge = charge;
    }

    /** Уникальный номер аккумулятора. */
    public int id() {
        return id;
    }

    public long charge() {
        return charge;
    }

    /** @throws IllegalStateException если заряда недостаточно */
    public void withdraw(long amount) {
        if (amount > charge) {
            throw new IllegalStateException("Недостаточно заряда в аккумуляторе " + id);
        }
        long current = charge;
        Thread.onSpinWait(); // «физическая» задержка между чтением и записью — гонка проявляется надёжно
        charge = current - amount;
    }

    public void deposit(long amount) {
        long current = charge;
        Thread.onSpinWait();
        charge = current + amount;
    }

    @Override
    public String toString() {
        return "Аккумулятор #" + id;
    }
}
