package city.subroutine.sandbox.it;

import city.subroutine.levels.LevelCatalog;
import city.subroutine.sandbox.api.ExecutionResult;
import city.subroutine.sandbox.api.ExecutionStatus;
import city.subroutine.sandbox.api.TestStatus;
import city.subroutine.sandbox.host.DefaultCodeRunnerService;
import city.subroutine.sandbox.host.RunnerConfig;
import city.subroutine.sandbox.report.RussianReportFormatter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Каждый уровень: эталонное решение проходит, типичная ошибка даёт ожидаемый сценарий провала. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LevelsIntegrationTest {

    private static DefaultCodeRunnerService service;

    @BeforeAll
    static void start() {
        service = new DefaultCodeRunnerService(RunnerConfig.defaults()
                .withWorkerClasspath(List.of(Path.of("target", "classes")))
                .withPrewarmedWorkers(2)
                .withMaxConcurrentExecutions(2));
    }

    @AfterAll
    static void stop() {
        service.close();
    }

    @ParameterizedTest(name = "{0}: {1} → {2}")
    @CsvSource({
            "powergrid-01, powergrid/PowerGrid.java,               SUCCESS,               ,",
            "powergrid-01, powergrid/PowerGridBuggy.java,          TESTS_FAILED,          int-overflow, FAILED",
            "water-01,     water/WaterStation.java,                SUCCESS,               ,",
            "water-01,     water/WaterStationFinally.java,         TESTS_FAILED,          suppressed-kept, FAILED",
            "warehouse-01, warehouse/ColdStorage.java,             SUCCESS,               ,",
            "warehouse-01, warehouse/ColdStorageStrict.java,       TESTS_FAILED,          warm-rejected, FAILED",
            "telemetry-01, telemetry/SensorCache.java,             SUCCESS,               ,",
            "telemetry-01, telemetry/SensorCacheLeaky.java,        MEMORY_LIMIT_EXCEEDED, sensor-storm, MEMORY_LIMIT_EXCEEDED",
            "traffic-01,   traffic/SectorTrafficCounter.java,      SUCCESS,               ,",
            "energy-01,    energy/EnergyBank.java,                 SUCCESS,               ,",
            "energy-01,    energy/EnergyBankDeadlock.java,         DEADLOCK,              opposite-directions, DEADLOCK",
    })
    void levelBehavesAsDesigned(String levelId, String example, ExecutionStatus expected, String testId,
                                TestStatus testStatus) throws Exception {
        String code = Files.readString(Path.of("examples", example), StandardCharsets.UTF_8);
        ExecutionResult result = service.execute(LevelCatalog.find(levelId).orElseThrow().request(example, code));
        assertEquals(expected, result.status(), () -> RussianReportFormatter.format(result));
        if (testId != null) {
            assertEquals(testStatus, result.test(testId).orElseThrow().status(),
                    () -> RussianReportFormatter.format(result));
        }
    }
}
