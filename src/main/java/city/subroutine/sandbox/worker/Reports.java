package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.StackFrameInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Преобразование исключений и стеков в структуры протокола с фильтрацией кадров обвязки. */
final class Reports {

    static final int MAX_FRAMES = 48;
    private static final int MAX_MESSAGE_CHARS = 2_000;
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final int MAX_SUPPRESSED = 4;
    private static final String HARNESS_PREFIX = "city.subroutine.sandbox.";
    private static final List<String> HIDDEN_PREFIXES =
            List.of("java.lang.invoke.", "jdk.internal.reflect.", "jdk.internal.invoke.", "java.lang.reflect.Method");

    record FrameSlice(List<StackFrameInfo> frames, int omitted) {
    }

    private Reports() {
    }

    /** Исключение из кода игрока или уровня: кадры обвязки песочницы отрезаются. */
    static ErrorReport of(Throwable error, String playerPackage) {
        return of(error, playerPackage, true, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /** Внутренняя ошибка песочницы: стек сохраняется целиком (для разработчиков, не для игрока). */
    static ErrorReport internal(Throwable error) {
        return of(error, "", false, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static ErrorReport of(Throwable error, String playerPackage, boolean cutHarness, int depth,
                                  Set<Throwable> seen) {
        seen.add(error);
        FrameSlice slice = frames(error.getStackTrace(), playerPackage, MAX_FRAMES, cutHarness);
        Optional<ErrorReport> cause = Optional.empty();
        Throwable rawCause = error.getCause();
        if (rawCause != null && depth < MAX_CAUSE_DEPTH && !seen.contains(rawCause)) {
            cause = Optional.of(of(rawCause, playerPackage, cutHarness, depth + 1, seen));
        }
        List<ErrorReport> suppressed = new ArrayList<>();
        if (depth < MAX_CAUSE_DEPTH) {
            for (Throwable s : error.getSuppressed()) {
                if (suppressed.size() >= MAX_SUPPRESSED) {
                    break;
                }
                if (!seen.contains(s)) {
                    suppressed.add(of(s, playerPackage, cutHarness, depth + 1, seen));
                }
            }
        }
        return new ErrorReport(error.getClass().getName(), truncate(safeMessage(error)), slice.frames(),
                slice.omitted(), cause, suppressed);
    }

    /**
     * Кадры до первого кадра обвязки песочницы (всё ниже — наш код, игроку не нужен);
     * кадры связывания MethodHandle/рефлексии скрываются.
     */
    static FrameSlice frames(StackTraceElement[] trace, String playerPackage, int max) {
        return frames(trace, playerPackage, max, true);
    }

    private static FrameSlice frames(StackTraceElement[] trace, String playerPackage, int max, boolean cutHarness) {
        List<StackFrameInfo> frames = new ArrayList<>();
        int omitted = 0;
        String playerPrefix = playerPackage + ".";
        for (StackTraceElement element : trace) {
            String className = element.getClassName();
            if (cutHarness && className.startsWith(HARNESS_PREFIX)) {
                break;
            }
            if (HIDDEN_PREFIXES.stream().anyMatch(className::startsWith)) {
                continue;
            }
            if (frames.size() >= max) {
                omitted++;
                continue;
            }
            frames.add(new StackFrameInfo(className, element.getMethodName(), element.getFileName(),
                    element.getLineNumber(), !playerPackage.isEmpty() && className.startsWith(playerPrefix)));
        }
        return new FrameSlice(frames, omitted);
    }

    /** getMessage() переопределяется игроком и может сам бросить исключение или зависнуть на рекурсии. */
    static String safeMessage(Throwable error) {
        try {
            return error.getMessage();
        } catch (Throwable nested) {
            return "<getMessage() выбросил " + nested.getClass().getName() + ">";
        }
    }

    static String truncate(String text) {
        if (text == null || text.length() <= MAX_MESSAGE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_CHARS) + "… (обрезано)";
    }
}
