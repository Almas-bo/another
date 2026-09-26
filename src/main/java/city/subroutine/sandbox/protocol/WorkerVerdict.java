package city.subroutine.sandbox.protocol;

/** Финальный вердикт воркера (кадр FINISHED). */
public enum WorkerVerdict {
    COMPLETED,
    COMPILATION_ERROR,
    POLICY_VIOLATION,
    CONTRACT_VIOLATION,
    TIMEOUT,
    DEADLOCK,
    THREAD_LIMIT_EXCEEDED,
    SANDBOX_FAILURE
}
