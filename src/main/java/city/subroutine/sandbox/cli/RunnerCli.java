package city.subroutine.sandbox.cli;

import city.subroutine.levels.LevelCatalog;
import city.subroutine.levels.LevelDefinition;
import city.subroutine.sandbox.api.ExecutionResult;
import city.subroutine.sandbox.api.ExecutionStatus;
import city.subroutine.sandbox.host.DefaultCodeRunnerService;
import city.subroutine.sandbox.host.RunnerConfig;
import city.subroutine.sandbox.report.RussianReportFormatter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Консольный прогон решения: {@code java -cp target/classes city.subroutine.sandbox.cli.RunnerCli <уровень> <файл.java>}.
 * Код завершения: 0 — все тесты пройдены, 1 — нет, 2 — ошибка использования.
 */
public final class RunnerCli {

    private RunnerCli() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length != 2) {
            out.println("Использование: RunnerCli <уровень> <путь к .java>");
            out.println("Уровни: " + String.join(", ", LevelCatalog.ids()));
            System.exit(2);
        }
        Optional<LevelDefinition> level = LevelCatalog.find(args[0]);
        if (level.isEmpty()) {
            out.println("Неизвестный уровень: " + args[0] + ". Доступны: " + String.join(", ", LevelCatalog.ids()));
            System.exit(2);
        }
        String code = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        try (DefaultCodeRunnerService service =
                     new DefaultCodeRunnerService(RunnerConfig.defaults().withPrewarmedWorkers(0))) {
            ExecutionResult result = service.execute(level.get().request("cli-" + UUID.randomUUID(), code));
            out.print(RussianReportFormatter.format(result));
            System.exit(result.status() == ExecutionStatus.SUCCESS ? 0 : 1);
        }
    }
}
