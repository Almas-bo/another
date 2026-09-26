package city.subroutine.sandbox.host;

import city.subroutine.sandbox.api.SandboxLimits;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Конфигурация хоста песочницы.
 *
 * @param javaExecutable         JDK (не JRE — воркеру нужен javac)
 * @param workerClasspath        classpath воркера: этот модуль + наборы тестов и API уровней
 * @param commandPrefix          префикс команды для ОС-изоляции: {@code ["nsjail", "--config", "sandbox.cfg", "--"]},
 *                               {@code ["prlimit", "--nproc=256", "--"]} и т.п.; пусто — без префикса
 * @param extraJvmOptions        дополнительные флаги JVM воркера
 * @param prewarmedWorkers       сколько прогретых воркеров держать в пуле (0 — запускать по требованию)
 * @param poolHeapMegabytes      -Xmx воркеров в пуле; запросы с другим лимитом кучи запускают свежий процесс
 * @param maxConcurrentExecutions сколько песочниц может работать одновременно
 * @param workerStartupTimeout   время на старт JVM и прогрев javac (не входит в лимиты игрока)
 * @param workDirectoryRoot      каталог для рабочих папок воркеров
 * @param maxSandboxLogBytes     сколько байт stderr воркера сохранять
 */
public record RunnerConfig(
        Path javaExecutable,
        List<Path> workerClasspath,
        List<String> commandPrefix,
        List<String> extraJvmOptions,
        int prewarmedWorkers,
        int poolHeapMegabytes,
        int maxConcurrentExecutions,
        Duration workerStartupTimeout,
        Path workDirectoryRoot,
        int maxSandboxLogBytes) {

    public RunnerConfig {
        Objects.requireNonNull(javaExecutable, "javaExecutable");
        workerClasspath = List.copyOf(Objects.requireNonNull(workerClasspath, "workerClasspath"));
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("workerClasspath пуст");
        }
        commandPrefix = List.copyOf(Objects.requireNonNull(commandPrefix, "commandPrefix"));
        extraJvmOptions = List.copyOf(Objects.requireNonNull(extraJvmOptions, "extraJvmOptions"));
        if (prewarmedWorkers < 0 || prewarmedWorkers > 64) {
            throw new IllegalArgumentException("prewarmedWorkers вне диапазона [0, 64]");
        }
        if (poolHeapMegabytes < SandboxLimits.MIN_HEAP_MB || poolHeapMegabytes > SandboxLimits.MAX_HEAP_MB) {
            throw new IllegalArgumentException("poolHeapMegabytes вне допустимого диапазона");
        }
        if (maxConcurrentExecutions < 1) {
            throw new IllegalArgumentException("maxConcurrentExecutions < 1");
        }
        Objects.requireNonNull(workerStartupTimeout, "workerStartupTimeout");
        Objects.requireNonNull(workDirectoryRoot, "workDirectoryRoot");
        if (maxSandboxLogBytes < 0) {
            throw new IllegalArgumentException("maxSandboxLogBytes < 0");
        }
    }

    /** Конфигурация по умолчанию: текущий JDK и текущий classpath. */
    public static RunnerConfig defaults() {
        List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> !s.isBlank())
                .map(Path::of)
                .toList();
        return new RunnerConfig(
                Path.of(System.getProperty("java.home"), "bin", "java"),
                classpath,
                List.of(),
                List.of(),
                1,
                SandboxLimits.defaults().heapMegabytes(),
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                Duration.ofSeconds(20),
                Path.of(System.getProperty("java.io.tmpdir"), "subroutine-sandbox"),
                64 * 1024);
    }

    public RunnerConfig withWorkerClasspath(List<Path> value) {
        return new RunnerConfig(javaExecutable, value, commandPrefix, extraJvmOptions, prewarmedWorkers,
                poolHeapMegabytes, maxConcurrentExecutions, workerStartupTimeout, workDirectoryRoot, maxSandboxLogBytes);
    }

    public RunnerConfig withCommandPrefix(List<String> value) {
        return new RunnerConfig(javaExecutable, workerClasspath, value, extraJvmOptions, prewarmedWorkers,
                poolHeapMegabytes, maxConcurrentExecutions, workerStartupTimeout, workDirectoryRoot, maxSandboxLogBytes);
    }

    public RunnerConfig withPrewarmedWorkers(int value) {
        return new RunnerConfig(javaExecutable, workerClasspath, commandPrefix, extraJvmOptions, value,
                poolHeapMegabytes, maxConcurrentExecutions, workerStartupTimeout, workDirectoryRoot, maxSandboxLogBytes);
    }

    public RunnerConfig withMaxConcurrentExecutions(int value) {
        return new RunnerConfig(javaExecutable, workerClasspath, commandPrefix, extraJvmOptions, prewarmedWorkers,
                poolHeapMegabytes, value, workerStartupTimeout, workDirectoryRoot, maxSandboxLogBytes);
    }
}
