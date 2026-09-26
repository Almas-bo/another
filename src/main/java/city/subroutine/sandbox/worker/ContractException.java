package city.subroutine.sandbox.worker;

/** Код игрока скомпилирован и безопасен, но не соответствует контракту уровня. */
final class ContractException extends Exception {

    private static final long serialVersionUID = 1L;

    ContractException(String message) {
        super(message);
    }
}
