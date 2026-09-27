package city.subroutine.sandbox.api;

import java.util.concurrent.CompletableFuture;

/**
 * Изолятор исполнения кода игрока: компиляция в памяти, статическая проверка байткода,
 * исполнение в отдельном ClassLoader внутри одноразового JVM-процесса с лимитами времени, памяти и потоков.
 *
 * <p>Реализация потокобезопасна. Ошибки кода игрока никогда не выбрасываются как исключения —
 * они всегда описаны в {@link ExecutionResult}.
 */
public interface CodeRunnerService extends AutoCloseable {

    /**
     * Синхронный запуск.
     *
     * @throws InterruptedException если вызывающий поток прерван во время ожидания слота или результата;
     *                              процесс-песочница при этом уничтожается
     * @throws IllegalStateException если сервис закрыт
     */
    ExecutionResult execute(ExecutionRequest request) throws InterruptedException;

    /**
     * Отладочный запуск одного теста с записью трассы выполнения кода игрока
     * (строки, глубина стека, локальные переменные). Лимиты времени увеличены: запись замедляет исполнение.
     */
    DebugResult debug(ExecutionRequest request, String testId) throws InterruptedException;

    /** Асинхронный запуск (для RPC-слоя). Отмена future не прерывает уже запущенную песочницу раньше её лимитов. */
    CompletableFuture<ExecutionResult> executeAsync(ExecutionRequest request);

    @Override
    void close();
}
