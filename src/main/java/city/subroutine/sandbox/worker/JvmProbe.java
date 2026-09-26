package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.api.ThreadSnapshot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Телеметрия JVM: CPU и аллокации потоков, пики кучи, GC, дампы потоков и поиск deadlock. */
final class JvmProbe {

    record GcTotals(long count, long timeMillis) {
    }

    private final com.sun.management.ThreadMXBean threads;
    private final List<MemoryPoolMXBean> heapPools;
    private final List<GarbageCollectorMXBean> collectors;

    JvmProbe() {
        ThreadMXBean base = ManagementFactory.getThreadMXBean();
        if (!(base instanceof com.sun.management.ThreadMXBean extended)) {
            throw new IllegalStateException("Нужен HotSpot ThreadMXBean (com.sun.management)");
        }
        this.threads = extended;
        if (threads.isThreadCpuTimeSupported()) {
            threads.setThreadCpuTimeEnabled(true);
        }
        if (threads.isThreadAllocatedMemorySupported()) {
            threads.setThreadAllocatedMemoryEnabled(true);
        }
        this.heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP && p.isValid())
                .toList();
        this.collectors = ManagementFactory.getGarbageCollectorMXBeans();
    }

    long currentThreadCpuNanos() {
        return Math.max(0, threads.getCurrentThreadCpuTime());
    }

    long currentThreadAllocatedBytes() {
        return Math.max(0, threads.getCurrentThreadAllocatedBytes());
    }

    long threadCpuNanos(long threadId) {
        return Math.max(0, threads.getThreadCpuTime(threadId));
    }

    long threadAllocatedBytes(long threadId) {
        return Math.max(0, threads.getThreadAllocatedBytes(threadId));
    }

    int liveThreadCount() {
        return threads.getThreadCount();
    }

    Set<Long> liveThreadIds() {
        Set<Long> ids = new HashSet<>();
        for (long id : threads.getAllThreadIds()) {
            ids.add(id);
        }
        return ids;
    }

    List<String> threadNames(Collection<Long> ids) {
        List<String> names = new ArrayList<>();
        for (ThreadInfo info : threads.getThreadInfo(toArray(ids), 0)) {
            if (info != null) {
                names.add(info.getThreadName());
            }
        }
        return names;
    }

    Set<Long> deadlockedThreadIds() {
        long[] ids = threads.findDeadlockedThreads();
        Set<Long> result = new HashSet<>();
        if (ids != null) {
            for (long id : ids) {
                result.add(id);
            }
        }
        return result;
    }

    List<ThreadSnapshot> snapshot(Collection<Long> ids, Set<Long> deadlocked, String playerPackage, int maxFrames) {
        List<ThreadSnapshot> result = new ArrayList<>();
        for (ThreadInfo info : threads.getThreadInfo(toArray(ids), true, true)) {
            if (info == null) {
                continue; // поток завершился
            }
            Reports.FrameSlice slice = Reports.frames(info.getStackTrace(), playerPackage, maxFrames);
            result.add(new ThreadSnapshot(
                    info.getThreadName(),
                    info.getThreadState().name(),
                    deadlocked.contains(info.getThreadId()),
                    info.getLockName(),
                    info.getLockOwnerName(),
                    slice.frames()));
        }
        return result;
    }

    void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : heapPools) {
            pool.resetPeakUsage();
        }
    }

    /** Сумма пиков пулов кучи — оценка сверху (пики пулов могли прийтись на разные моменты). */
    long heapPeakBytes() {
        long total = 0;
        for (MemoryPoolMXBean pool : heapPools) {
            total += pool.getPeakUsage().getUsed();
        }
        return total;
    }

    GcTotals gcTotals() {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean gc : collectors) {
            count += Math.max(0, gc.getCollectionCount());
            time += Math.max(0, gc.getCollectionTime());
        }
        return new GcTotals(count, time);
    }

    private static long[] toArray(Collection<Long> ids) {
        return ids.stream().mapToLong(Long::longValue).toArray();
    }
}
