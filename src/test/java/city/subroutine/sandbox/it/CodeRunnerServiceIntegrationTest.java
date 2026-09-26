package city.subroutine.sandbox.it;

import city.subroutine.levels.LevelCatalog;
import city.subroutine.levels.LevelDefinition;
import city.subroutine.levels.it.TaskSuite;
import city.subroutine.sandbox.api.CompilationDiagnostic;
import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.ExecutionResult;
import city.subroutine.sandbox.api.ExecutionStatus;
import city.subroutine.sandbox.api.SandboxLimits;
import city.subroutine.sandbox.api.StackFrameInfo;
import city.subroutine.sandbox.api.TestOutcome;
import city.subroutine.sandbox.api.TestStatus;
import city.subroutine.sandbox.api.ThreadSnapshot;
import city.subroutine.sandbox.host.DefaultCodeRunnerService;
import city.subroutine.sandbox.host.RunnerConfig;
import city.subroutine.sandbox.report.RussianReportFormatter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сквозные тесты: реальные процессы-песочницы, реальные javac и JVM-лимиты. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CodeRunnerServiceIntegrationTest {

    private static final SandboxLimits TASK_LIMITS = SandboxLimits.defaults()
            .withPerTestTimeout(Duration.ofSeconds(1))
            .withTotalTimeout(Duration.ofSeconds(15))
            .withMaxThreads(16);

    private static DefaultCodeRunnerService service;

    @BeforeAll
    static void startService() {
        service = new DefaultCodeRunnerService(RunnerConfig.defaults()
                .withWorkerClasspath(List.of(Path.of("target", "classes"), Path.of("target", "test-classes")))
                .withPrewarmedWorkers(2)
                .withMaxConcurrentExecutions(4));
    }

    @AfterAll
    static void stopService() {
        service.close();
    }

    // ------------------------------------------------------------------ уровень powergrid-01

    @Test
    void referenceSolutionPassesAllTests() throws Exception {
        ExecutionResult result = runLevel("powergrid-01", example("powergrid/PowerGrid.java"));
        assertEquals(ExecutionStatus.SUCCESS, result.status(), () -> RussianReportFormatter.format(result));
        assertEquals(7, result.tests().size());
        assertTrue(result.metrics().compileNanos() > 0);
        assertTrue(result.metrics().totalCpuNanos() > 0);
        assertEquals(0, result.metrics().workerExitCode());
    }

    @Test
    void buggySolutionFailsExactlyTheExpectedTests() throws Exception {
        ExecutionResult result = runLevel("powergrid-01",
                example("powergrid/PowerGridBuggy.java"));
        assertEquals(ExecutionStatus.TESTS_FAILED, result.status(), () -> RussianReportFormatter.format(result));
        assertStatus(result, "basic-sum", TestStatus.PASSED);
        assertStatus(result, "int-overflow", TestStatus.FAILED);
        assertStatus(result, "input-immutable", TestStatus.FAILED);
        assertStatus(result, "hot-loop-allocations", TestStatus.FAILED);

        TestOutcome overflow = result.test("int-overflow").orElseThrow();
        assertEquals("4294967299", overflow.expected());
        assertEquals("3", overflow.actual());

        TestOutcome nullGrid = result.test("null-grid").orElseThrow();
        assertEquals(TestStatus.FAILED, nullGrid.status());
        ErrorReport npe = nullGrid.error().orElseThrow();
        assertEquals("java.lang.NullPointerException", npe.exceptionClass());
        StackFrameInfo crashSite = npe.firstPlayerFrame().orElseThrow();
        assertEquals("city.player.PowerGrid", crashSite.className());
        assertEquals(13, crashSite.lineNumber());
    }

    @Test
    void compilationErrorIsReportedWithPosition() throws Exception {
        ExecutionResult result = runLevel("powergrid-01", """
                package city.player;
                public final class PowerGrid {
                    public static long totalLoad(int[] loads) {
                        long total = 0
                        return total;
                    }
                }
                """);
        assertEquals(ExecutionStatus.COMPILATION_ERROR, result.status());
        CompilationDiagnostic error = result.diagnostics().stream()
                .filter(d -> d.kind() == CompilationDiagnostic.Kind.ERROR).findFirst().orElseThrow();
        assertEquals(4, error.line());
        assertEquals("compiler.err.expected", error.code());
        assertEquals("city.player.PowerGrid", error.sourceClass());
        assertTrue(result.tests().isEmpty());
    }

    @Test
    void wrongSignatureIsContractViolation() throws Exception {
        ExecutionResult result = runLevel("powergrid-01", """
                package city.player;
                public final class PowerGrid {
                    public static int totalLoad(int[] loads) { return 0; }
                }
                """);
        assertEquals(ExecutionStatus.CONTRACT_VIOLATION, result.status());
        assertTrue(result.statusDetail().contains("должен возвращать long"), result.statusDetail());
    }

    @Test
    void escapeAttemptIsBlockedBeforeExecution() throws Exception {
        ExecutionResult result = runLevel("powergrid-01", example("powergrid/PowerGridEvil.java"));
        assertEquals(ExecutionStatus.POLICY_VIOLATION, result.status());
        assertTrue(result.policyViolations().size() >= 5);
        assertTrue(result.tests().isEmpty(), "Ни один тест не должен запускаться");
    }

    @Test
    void oversizedSourceIsRejectedWithoutWorker() throws Exception {
        LevelDefinition level = LevelCatalog.find("powergrid-01").orElseThrow();
        String code = "package city.player; public final class PowerGrid {}" + " ".repeat(70_000);
        ExecutionResult result = service.execute(level.request("big", code));
        assertEquals(ExecutionStatus.REJECTED, result.status());
    }

    // ------------------------------------------------------------------ уровень traffic-01

    @Test
    void threadSafeCounterPasses() throws Exception {
        ExecutionResult result = runLevel("traffic-01", example("traffic/SectorTrafficCounter.java"));
        assertEquals(ExecutionStatus.SUCCESS, result.status(), () -> RussianReportFormatter.format(result));
    }

    @Test
    void parallelExecutionsAreIsolated() throws Exception {
        String good = example("powergrid/PowerGrid.java");
        String bad = example("powergrid/PowerGridBuggy.java");
        LevelDefinition level = LevelCatalog.find("powergrid-01").orElseThrow();
        List<CompletableFuture<ExecutionResult>> futures = IntStream.range(0, 6)
                .mapToObj(i -> service.executeAsync(level.request("par-" + i, i % 2 == 0 ? good : bad)))
                .toList();
        for (int i = 0; i < futures.size(); i++) {
            ExecutionResult result = futures.get(i).get(60, TimeUnit.SECONDS);
            assertEquals("par-" + i, result.requestId());
            assertEquals(i % 2 == 0 ? ExecutionStatus.SUCCESS : ExecutionStatus.TESTS_FAILED, result.status());
        }
    }

    // ------------------------------------------------------------------ режимы отказа

    @Test
    void infiniteLoopTimesOutAndRemainingTestsAreSkipped() throws Exception {
        long started = System.nanoTime();
        ExecutionResult result = runTask("""
                package city.player;
                public final class Task implements Runnable {
                    public void run() {
                        long x = 0;
                        while (x >= 0) { x = (x + 1) % 1_000; }
                    }
                }
                """);
        assertEquals(ExecutionStatus.TIMEOUT, result.status(), () -> RussianReportFormatter.format(result));
        assertStatus(result, "first-run", TestStatus.TIMEOUT);
        assertStatus(result, "second-run", TestStatus.SKIPPED);
        ThreadSnapshot testThread = result.tests().get(0).threads().get(0);
        assertTrue(testThread.frames().stream().anyMatch(f -> f.playerCode() && f.lineNumber() == 5),
                () -> "Снимок должен указывать на строку цикла: " + testThread);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10);
    }

    @Test
    void deadlockIsDetectedWithLockGraph() throws Exception {
        ExecutionResult result = runTask("""
                package city.player;
                import java.util.concurrent.CountDownLatch;
                public final class Task implements Runnable {
                    private final Object north = new Object();
                    private final Object south = new Object();
                    public void run() {
                        CountDownLatch bothLocked = new CountDownLatch(2);
                        Thread a = new Thread(() -> transfer(north, south, bothLocked), "north-gate");
                        Thread b = new Thread(() -> transfer(south, north, bothLocked), "south-gate");
                        a.start();
                        b.start();
                        try { a.join(); b.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    }
                    private static void transfer(Object first, Object second, CountDownLatch latch) {
                        synchronized (first) {
                            latch.countDown();
                            try { latch.await(); } catch (InterruptedException e) { return; }
                            synchronized (second) {
                                System.out.println("не случится");
                            }
                        }
                    }
                }
                """);
        assertEquals(ExecutionStatus.DEADLOCK, result.status(), () -> RussianReportFormatter.format(result));
        TestOutcome first = result.tests().get(0);
        assertEquals(TestStatus.DEADLOCK, first.status());
        List<ThreadSnapshot> deadlocked = first.threads().stream().filter(ThreadSnapshot::deadlocked).toList();
        assertEquals(2, deadlocked.size(), first.threads()::toString);
        for (ThreadSnapshot t : deadlocked) {
            assertEquals("BLOCKED", t.state());
            assertTrue(t.lockOwnerName().endsWith("-gate"));
        }
        assertStatus(result, "second-run", TestStatus.SKIPPED);
    }

    @Test
    void memoryBombHitsHeapLimitEvenIfOomIsCaught() throws Exception {
        ExecutionResult result = runTask("""
                package city.player;
                import java.util.ArrayList;
                import java.util.List;
                public final class Task implements Runnable {
                    public void run() {
                        List<long[]> hoard = new ArrayList<>();
                        try {
                            while (true) { hoard.add(new long[1 << 20]); }
                        } catch (OutOfMemoryError e) {
                            System.out.println("поймал OOM, продолжаю: " + hoard.size());
                        }
                    }
                }
                """);
        assertEquals(ExecutionStatus.MEMORY_LIMIT_EXCEEDED, result.status(), () -> RussianReportFormatter.format(result));
        assertStatus(result, "first-run", TestStatus.MEMORY_LIMIT_EXCEEDED);
        assertStatus(result, "second-run", TestStatus.SKIPPED);
        assertEquals(3, result.metrics().workerExitCode());
    }

    @Test
    void threadBombHitsThreadLimit() throws Exception {
        ExecutionResult result = runTask("""
                package city.player;
                public final class Task implements Runnable {
                    public void run() {
                        for (int i = 0; i < 500; i++) {
                            Thread t = new Thread(() -> {
                                try { Thread.sleep(60_000); } catch (InterruptedException e) { }
                            });
                            t.setDaemon(true);
                            t.start();
                        }
                        try { Thread.sleep(60_000); } catch (InterruptedException e) { }
                    }
                }
                """);
        assertEquals(ExecutionStatus.THREAD_LIMIT_EXCEEDED, result.status(), () -> RussianReportFormatter.format(result));
        assertStatus(result, "first-run", TestStatus.THREAD_LIMIT_EXCEEDED);
        assertStatus(result, "second-run", TestStatus.SKIPPED);
    }

    @Test
    void stackOverflowIsErrorWithTrimmedTrace() throws Exception {
        ExecutionResult result = runTask("""
                package city.player;
                public final class Task implements Runnable {
                    public void run() { System.out.println(depth(0)); }
                    private int depth(int n) { return depth(n + 1) + 1; }
                }
                """);
        assertEquals(ExecutionStatus.TESTS_FAILED, result.status(), () -> RussianReportFormatter.format(result));
        TestOutcome first = result.tests().get(0);
        assertEquals(TestStatus.ERROR, first.status());
        ErrorReport error = first.error().orElseThrow();
        assertEquals("java.lang.StackOverflowError", error.exceptionClass());
        assertTrue(error.omittedFrames() > 0);
        assertTrue(error.frames().stream().allMatch(StackFrameInfo::playerCode));
        assertStatus(result, "second-run", TestStatus.ERROR); // процесс жив, второй тест тоже исполнен
    }

    @Test
    void outputIsCapturedAndLeakedExecutorIsReported() throws Exception {
        ExecutionResult result = runTask("""
                package city.player;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                public final class Task implements Runnable {
                    public void run() {
                        System.out.println("Светофор: зелёный");
                        ExecutorService pool = Executors.newSingleThreadExecutor();
                        pool.submit(() -> System.out.println("фон"));
                        // pool.shutdown() забыт
                    }
                }
                """);
        assertEquals(ExecutionStatus.SUCCESS, result.status(), () -> RussianReportFormatter.format(result));
        TestOutcome first = result.tests().get(0);
        assertTrue(first.output().contains("Светофор: зелёный"), first.output());
        assertEquals(1, first.leakedThreads().size(), first.leakedThreads()::toString);
    }

    @Test
    void infiniteStaticInitializerIsCaughtByTestTimeout() throws Exception {
        ExecutionResult result = runTask("""
                package city.player;
                public final class Task implements Runnable {
                    static {
                        long spins = 0;
                        while (spins >= 0) { spins = (spins + 1) % 1_000; }
                    }
                    public void run() { }
                }
                """);
        assertEquals(ExecutionStatus.TIMEOUT, result.status(), () -> RussianReportFormatter.format(result));
        assertStatus(result, "first-run", TestStatus.TIMEOUT);
    }

    @Test
    void missingInterfaceIsContractViolation() throws Exception {
        ExecutionResult result = runTask("""
                package city.player;
                public final class Task { public void run() { } }
                """);
        assertEquals(ExecutionStatus.CONTRACT_VIOLATION, result.status());
        assertTrue(result.statusDetail().contains("java.lang.Runnable"), result.statusDetail());
    }

    // ------------------------------------------------------------------ helpers

    private static ExecutionResult runLevel(String levelId, String code) throws InterruptedException {
        LevelDefinition level = LevelCatalog.find(levelId).orElseThrow();
        return service.execute(level.request(levelId + "-" + System.nanoTime(), code));
    }

    private static ExecutionResult runTask(String code) throws InterruptedException {
        ExecutionRequest request = ExecutionRequest.singleSource("task-" + System.nanoTime(), code,
                new EntryPoint.ContractEntry("city.player.Task", "java.lang.Runnable"),
                TaskSuite.class.getName(), "city.player", List.of(), TASK_LIMITS);
        return service.execute(request);
    }

    /** Примеры лежат в examples/ под «учебными» именами файлов, но объявляют класс по контракту. */
    private static String example(String relative) throws IOException {
        return Files.readString(Path.of("examples", relative), StandardCharsets.UTF_8);
    }

    private static void assertStatus(ExecutionResult result, String testId, TestStatus expected) {
        TestOutcome outcome = result.test(testId).orElseThrow(() -> new AssertionError("Нет теста " + testId));
        assertEquals(expected, outcome.status(), () -> testId + "\n" + RussianReportFormatter.format(result));
    }
}
