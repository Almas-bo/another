package city.subroutine.levels.water.api;

/** Пульт управления задвижками. */
public interface ValveBoard {

    /**
     * Открывает задвижку.
     *
     * @throws ValveJammedException если задвижку не удалось открыть (тогда закрывать её не нужно)
     */
    Valve open(String valveId) throws ValveJammedException;
}
