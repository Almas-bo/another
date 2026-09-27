package city.subroutine.levels.water.api;

/** Открытая задвижка водопровода. Каждую открытую задвижку обязательно нужно закрыть. */
public interface Valve extends AutoCloseable {

    /** Номер задвижки. */
    String id();

    /**
     * Текущий расход воды через задвижку, л/с.
     *
     * @throws ValveJammedException если задвижку заклинило
     */
    int flowRate() throws ValveJammedException;

    /**
     * Закрывает задвижку.
     *
     * @throws ValveJammedException если задвижку заклинило при закрытии
     */
    @Override
    void close() throws ValveJammedException;
}
