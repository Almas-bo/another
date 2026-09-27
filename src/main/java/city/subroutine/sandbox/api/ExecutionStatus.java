package city.subroutine.sandbox.api;

/** Итог запуска целиком. */
public enum ExecutionStatus {
    /** Все тесты пройдены. */
    SUCCESS,
    /** Режим CHECK: код скомпилирован, безопасен и соответствует контракту (тесты не запускались). */
    COMPILED,
    /** Код исполнился, но часть тестов не пройдена. */
    TESTS_FAILED,
    COMPILATION_ERROR,
    /** Код обращается к запрещённым API (файлы, сеть, рефлексия, процессы ...). */
    POLICY_VIOLATION,
    /** Класс/метод не соответствует контракту уровня. */
    CONTRACT_VIOLATION,
    TIMEOUT,
    DEADLOCK,
    MEMORY_LIMIT_EXCEEDED,
    THREAD_LIMIT_EXCEEDED,
    /** Запрос отклонён до запуска (слишком большой исходник, неверный пакет ...). */
    REJECTED,
    /** Внутренний сбой песочницы или ошибка в наборе тестов уровня — не вина игрока. */
    SANDBOX_FAILURE
}
