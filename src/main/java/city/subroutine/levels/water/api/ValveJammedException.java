package city.subroutine.levels.water.api;

import java.io.Serial;

/** Задвижку заклинило: при открытии, при замере расхода или при закрытии. */
public class ValveJammedException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public ValveJammedException(String message) {
        super(message);
    }
}
