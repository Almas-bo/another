package city.subroutine.sandbox.host;

import city.subroutine.sandbox.worker.WorkerMain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Сборка командной строки и запуск процесса-песочницы. */
final class WorkerLauncher {

    private final RunnerConfig config;

    WorkerLauncher(RunnerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    WorkerProcess launch(int heapMegabytes) throws IOException {
        return launch(heapMegabytes, List.of(), true);
    }

    /**
     * @param extraOptions дополнительные флаги JVM этого запуска (например, JDWP-агент отладки)
     * @param prewarm      прогревать javac до HELLO (для пула — да, для отладки — нет)
     */
    WorkerProcess launch(int heapMegabytes, List<String> extraOptions, boolean prewarm) throws IOException {
        Files.createDirectories(config.workDirectoryRoot());
        Path workDirectory = Files.createTempDirectory(config.workDirectoryRoot(), "worker-");
        List<String> command = new ArrayList<>(config.commandPrefix());
        command.add(config.javaExecutable().toString());
        command.addAll(jvmOptions(heapMegabytes, workDirectory));
        command.addAll(config.extraJvmOptions());
        command.addAll(extraOptions);
        command.add("-cp");
        command.add(String.join(File.pathSeparator,
                config.workerClasspath().stream().map(p -> p.toAbsolutePath().toString()).toList()));
        command.add(WorkerMain.class.getName());
        if (prewarm) {
            command.add(WorkerMain.PREWARM_FLAG);
        }

        ProcessBuilder builder = new ProcessBuilder(command).directory(workDirectory.toFile());
        // Чистое окружение: никаких секретов хоста, никаких JAVA_TOOL_OPTIONS/агентов.
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("LANG", "C.UTF-8");
        environment.put("TMPDIR", workDirectory.toString());
        try {
            return new WorkerProcess(builder.start(), workDirectory, heapMegabytes, config.maxSandboxLogBytes());
        } catch (IOException e) {
            Files.deleteIfExists(workDirectory);
            throw e;
        }
    }

    static List<String> jvmOptions(int heapMegabytes, Path workDirectory) {
        return List.of(
                "-Xmx" + heapMegabytes + "m",
                "-Xms" + Math.min(heapMegabytes, 64) + "m",
                "-Xss512k",
                "-XX:MaxMetaspaceSize=128m",
                // Детерминизм замеров: один поток GC, только C1 (без escape analysis, который «прячет» аллокации).
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-XX:ActiveProcessorCount=2",
                // OOM в любом потоке — немедленное завершение с кодом 3, код игрока не может «проглотить» OOM.
                "-XX:+ExitOnOutOfMemoryError",
                // Сообщения VM — в stderr, stdout принадлежит протоколу.
                "-XX:+DisplayVMOutputToStderr",
                "-XX:-UsePerfData",
                "-XX:+DisableAttachMechanism",
                "-Xshare:auto",
                "-Djava.io.tmpdir=" + workDirectory,
                "-Dfile.encoding=UTF-8",
                "-Djava.awt.headless=true");
    }
}
