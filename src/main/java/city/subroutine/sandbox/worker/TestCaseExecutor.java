package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.TestMetrics;
import city.subroutine.sandbox.api.TestOutcome;
import city.subroutine.sandbox.api.TestStatus;
import city.subroutine.sandbox.api.ThreadSnapshot;
import city.subroutine.sandbox.testing.AssertionFailure;
import city.subroutine.sandbox.testing.TestBody;
import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.util.BoundedBuffer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Исполнение одного теста в отдельном потоке с лимитом времени и сбором метрик.
 * Если поток не завершился за отведённое время, остановить его невозможно — исполнитель возвращает
 * TIMEOUT/DEADLOCK со снимками потоков, а {@link WorkerSession} завершает процесс.
 */
final class TestCaseExecutor {

    /** Стек потока теста: глубокая, но конечная рекурсия успевает упасть с StackOverflowError. */
    private static final long TEST_THREAD_STACK_BYTES = 1024L * 1024L;
    private static final long LEAK_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final int MAX_SNAPSHOT_THREADS = 16;
    private static final int SNAPSHOT_FRAMES = 24;

    private final TargetBinding binding;
    private final OutputRouter router;
    private final JvmProbe probe;
    private final long timeoutNanos;
    private final int maxOutputBytes;
    private final String playerPackage;

    TestCaseExecutor(TargetBinding binding, OutputRouter router, JvmProbe probe, long timeoutNanos,
                     int maxOutputBytes, String playerPackage) {
        this.binding = binding;
        this.router = router;
        this.probe = probe;
        this.timeoutNanos = timeoutNanos;
        this.maxOutputBytes = maxOutputBytes;
        this.playerPackage = playerPackage;
    }

    TestOutcome execute(TestCase testCase, int index) {
        BoundedBuffer output = new BoundedBuffer(maxOutputBytes);
        Set<Long> threadsBefore = probe.liveThreadIds();
        probe.resetHeapPeaks();
        JvmProbe.GcTotals gcBefore = probe.gcTotals();

        CaseRun run = new CaseRun(testCase.body(), new DefaultTestContext(binding, probe), probe);
        Thread thread = new Thread(null, run, "player-test-" + (index + 1), TEST_THREAD_STACK_BYTES);
        thread.setDaemon(true);

        router.redirectTo(output);
        long started = System.nanoTime();
        thread.start();
        boolean finished = joinUninterruptibly(thread, started + timeoutNanos);
        long elapsed = System.nanoTime() - started;

        if (!finished) {
            return timedOut(testCase, thread, run, threadsBefore, output, elapsed, gcBefore);
        }

        List<String> leaked = leakedThreads(threadsBefore, thread.threadId());
        router.redirectTo(null);
        JvmProbe.GcTotals gcAfter = probe.gcTotals();
        TestMetrics metrics = new TestMetrics(
                run.wallEnd - run.wallStart,
                run.cpuEnd - run.cpuStart,
                run.allocEnd - run.allocStart,
                probe.heapPeakBytes(),
                gcAfter.count() - gcBefore.count(),
                gcAfter.timeMillis() - gcBefore.timeMillis());
        return classify(testCase, run.failure, metrics, output, leaked);
    }

    private TestOutcome classify(TestCase testCase, Throwable failure, TestMetrics metrics, BoundedBuffer output,
                                 List<String> leaked) {
        String text = output.contentAsString();
        if (failure == null) {
            return new TestOutcome(testCase.id(), testCase.title(), TestStatus.PASSED, null, null, null,
                    Optional.empty(), metrics, text, output.truncated(), List.of(), leaked);
        }
        if (failure instanceof AssertionFailure assertion) {
            Optional<ErrorReport> cause = Optional.ofNullable(assertion.getCause())
                    .map(c -> Reports.of(c, playerPackage));
            return new TestOutcome(testCase.id(), testCase.title(), TestStatus.FAILED,
                    Reports.truncate(Reports.safeMessage(assertion)), assertion.expected(), assertion.actual(),
                    cause, metrics, text, output.truncated(), List.of(), leaked);
        }
        String message = "Необработанное исключение " + failure.getClass().getName();
        String detail = Reports.safeMessage(failure);
        if (detail != null) {
            message += ": " + detail;
        }
        return new TestOutcome(testCase.id(), testCase.title(), TestStatus.ERROR, Reports.truncate(message), null,
                null, Optional.of(Reports.of(failure, playerPackage)), metrics, text, output.truncated(), List.of(),
                leaked);
    }

    private TestOutcome timedOut(TestCase testCase, Thread thread, CaseRun run, Set<Long> threadsBefore,
                                 BoundedBuffer output, long elapsed, JvmProbe.GcTotals gcBefore) {
        Set<Long> deadlocked = probe.deadlockedThreadIds();
        Set<Long> relevant = new LinkedHashSet<>();
        relevant.add(thread.threadId());
        for (long id : probe.liveThreadIds()) {
            if (!threadsBefore.contains(id) && relevant.size() < MAX_SNAPSHOT_THREADS) {
                relevant.add(id);
            }
        }
        boolean deadlock = relevant.stream().anyMatch(deadlocked::contains);
        List<ThreadSnapshot> snapshots = probe.snapshot(relevant, deadlocked, playerPackage, SNAPSHOT_FRAMES);
        long timeoutMillis = TimeUnit.NANOSECONDS.toMillis(timeoutNanos);
        String message;
        if (deadlock) {
            List<String> names = new ArrayList<>();
            for (ThreadSnapshot s : snapshots) {
                if (s.deadlocked()) {
                    names.add(s.name() + " ждёт " + s.lockName() + " (занят потоком " + s.lockOwnerName() + ")");
                }
            }
            message = "Взаимная блокировка потоков: " + String.join("; ", names);
        } else {
            message = "Тест не завершился за " + timeoutMillis + " мс (бесконечный цикл, ожидание или слишком медленный алгоритм)";
        }
        JvmProbe.GcTotals gcAfter = probe.gcTotals();
        TestMetrics metrics = new TestMetrics(
                run.started ? System.nanoTime() - run.wallStart : elapsed,
                probe.threadCpuNanos(thread.threadId()) - run.cpuStart,
                probe.threadAllocatedBytes(thread.threadId()) - run.allocStart,
                probe.heapPeakBytes(),
                gcAfter.count() - gcBefore.count(),
                gcAfter.timeMillis() - gcBefore.timeMillis());
        router.redirectTo(null);
        return new TestOutcome(testCase.id(), testCase.title(), deadlock ? TestStatus.DEADLOCK : TestStatus.TIMEOUT,
                message, null, null, Optional.empty(), metrics, output.contentAsString(), output.truncated(),
                snapshots, List.of());
    }

    private List<String> leakedThreads(Set<Long> before, long testThreadId) {
        long deadline = System.nanoTime() + LEAK_GRACE_NANOS;
        Set<Long> leaked;
        do {
            leaked = probe.liveThreadIds();
            leaked.removeAll(before);
            leaked.remove(testThreadId);
            if (leaked.isEmpty()) {
                return List.of();
            }
            LockSupport.parkNanos(1_000_000L);
        } while (System.nanoTime() < deadline);
        return probe.threadNames(leaked);
    }

    private static boolean joinUninterruptibly(Thread thread, long deadlineNanos) {
        boolean interrupted = false;
        try {
            while (true) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return !thread.isAlive();
                }
                try {
                    return thread.join(java.time.Duration.ofNanos(remaining));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Тело потока теста. Замеры CPU/аллокаций снимаются изнутри потока — это самые точные значения. */
    private static final class CaseRun implements Runnable {

        private final TestBody body;
        private final DefaultTestContext context;
        private final JvmProbe probe;

        volatile long wallStart;
        volatile long wallEnd;
        volatile long cpuStart;
        volatile long cpuEnd;
        volatile long allocStart;
        volatile long allocEnd;
        volatile Throwable failure;
        volatile boolean started;

        CaseRun(TestBody body, DefaultTestContext context, JvmProbe probe) {
            this.body = body;
            this.context = context;
            this.probe = probe;
        }

        @Override
        public void run() {
            cpuStart = probe.currentThreadCpuNanos();
            allocStart = probe.currentThreadAllocatedBytes();
            wallStart = System.nanoTime();
            started = true;
            Throwable caught = null;
            try {
                body.run(context);
            } catch (Throwable t) {
                caught = t;
            }
            wallEnd = System.nanoTime();
            allocEnd = probe.currentThreadAllocatedBytes();
            cpuEnd = probe.currentThreadCpuNanos();
            failure = caught;
        }
    }
}
