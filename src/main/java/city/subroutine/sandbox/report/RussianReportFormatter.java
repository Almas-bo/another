package city.subroutine.sandbox.report;

import city.subroutine.sandbox.api.CompilationDiagnostic;
import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.ExecutionMetrics;
import city.subroutine.sandbox.api.ExecutionResult;
import city.subroutine.sandbox.api.ExecutionStatus;
import city.subroutine.sandbox.api.PolicyViolation;
import city.subroutine.sandbox.api.StackFrameInfo;
import city.subroutine.sandbox.api.TestOutcome;
import city.subroutine.sandbox.api.TestStatus;
import city.subroutine.sandbox.api.ThreadSnapshot;

import java.util.Locale;

/**
 * Текстовый отчёт ru-RU для консоли, логов и CLI. Движок строит собственный UI из структурированного
 * {@link ExecutionResult}; этот класс — эталонная таблица локализации кодов статусов.
 */
public final class RussianReportFormatter {

    private static final int FRAMES_TO_PRINT = 8;

    private RussianReportFormatter() {
    }

    public static String title(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "Все тесты пройдены";
            case COMPILED -> "Код скомпилирован и соответствует контракту";
            case TESTS_FAILED -> "Часть тестов не пройдена";
            case COMPILATION_ERROR -> "Ошибка компиляции";
            case POLICY_VIOLATION -> "Нарушение правил песочницы";
            case CONTRACT_VIOLATION -> "Нарушение контракта уровня";
            case TIMEOUT -> "Превышен лимит времени";
            case DEADLOCK -> "Взаимная блокировка потоков (deadlock)";
            case MEMORY_LIMIT_EXCEEDED -> "Превышен лимит памяти";
            case THREAD_LIMIT_EXCEEDED -> "Превышен лимит потоков";
            case REJECTED -> "Запрос отклонён";
            case SANDBOX_FAILURE -> "Сбой песочницы";
        };
    }

    public static String label(TestStatus status) {
        return switch (status) {
            case PASSED -> "ПРОЙДЕН";
            case FAILED -> "ПРОВАЛЕН";
            case ERROR -> "ОШИБКА";
            case TIMEOUT -> "ТАЙМАУТ";
            case DEADLOCK -> "DEADLOCK";
            case MEMORY_LIMIT_EXCEEDED -> "ПАМЯТЬ";
            case THREAD_LIMIT_EXCEEDED -> "ПОТОКИ";
            case SKIPPED -> "ПРОПУЩЕН";
            case SANDBOX_CRASH -> "СБОЙ";
        };
    }

    public static String format(ExecutionResult result) {
        StringBuilder out = new StringBuilder();
        out.append("══ Запуск ").append(result.requestId()).append(" ══\n");
        out.append("Итог: ").append(title(result.status()));
        if (!result.tests().isEmpty()) {
            out.append(" (").append(result.passedCount()).append('/').append(result.tests().size()).append(')');
        }
        out.append('\n');
        if (result.statusDetail() != null) {
            out.append("Подробности: ").append(result.statusDetail()).append('\n');
        }

        for (CompilationDiagnostic d : result.diagnostics()) {
            out.append(switch (d.kind()) {
                case ERROR -> "  [ошибка] ";
                case WARNING -> "  [предупреждение] ";
                case NOTE -> "  [заметка] ";
            });
            if (d.line() > 0) {
                out.append("строка ").append(d.line()).append(':').append(d.column()).append(" — ");
            }
            out.append(d.message().replace('\n', ' ')).append("  {").append(d.code()).append("}\n");
        }

        for (PolicyViolation v : result.policyViolations()) {
            out.append("  [запрещено] ").append(v.detail()).append(" (в ").append(v.playerClass()).append(")\n");
        }

        for (TestOutcome test : result.tests()) {
            out.append(test.status().passed() ? "  ✔ " : "  ✘ ")
                    .append(String.format(Locale.ROOT, "%-9s", label(test.status())))
                    .append(' ').append(test.title())
                    .append(String.format(Locale.ROOT, "  [%.1f мс, %s]",
                            test.metrics().wallNanos() / 1e6, bytes(test.metrics().allocatedBytes())))
                    .append('\n');
            if (test.message() != null && !test.status().passed() && test.status() != TestStatus.SKIPPED) {
                out.append("      ").append(test.message()).append('\n');
            }
            test.error().ifPresent(e -> appendError(out, e, "      "));
            for (ThreadSnapshot thread : test.threads()) {
                out.append("      поток ").append(thread.name()).append(" [").append(thread.state()).append(']');
                if (thread.deadlocked()) {
                    out.append(" DEADLOCK");
                }
                if (thread.lockName() != null) {
                    out.append(" ждёт ").append(thread.lockName());
                    if (thread.lockOwnerName() != null) {
                        out.append(", владелец ").append(thread.lockOwnerName());
                    }
                }
                out.append('\n');
                thread.frames().stream().filter(StackFrameInfo::playerCode).limit(3)
                        .forEach(f -> out.append("        at ").append(frame(f)).append('\n'));
            }
            if (!test.leakedThreads().isEmpty()) {
                out.append("      ⚠ после теста остались потоки: ").append(String.join(", ", test.leakedThreads()))
                        .append('\n');
            }
            if (!test.output().isEmpty()) {
                out.append("      вывод: ").append(test.output().strip().replace("\n", "\n             "))
                        .append(test.outputTruncated() ? " …(обрезано)" : "").append('\n');
            }
        }

        result.fatalError().ifPresent(e -> appendError(out, e, "  "));

        ExecutionMetrics m = result.metrics();
        out.append(String.format(Locale.ROOT,
                "Метрики: компиляция %.0f мс · тесты %.0f мс · CPU %.0f мс · аллокации %s · пик кучи %s · GC %d (%d мс)%n",
                m.compileNanos() / 1e6, m.executionWallNanos() / 1e6, m.totalCpuNanos() / 1e6,
                bytes(m.totalAllocatedBytes()), bytes(m.peakHeapBytes()), m.gcCount(), m.gcTimeMillis()));
        return out.toString();
    }

    private static void appendError(StringBuilder out, ErrorReport error, String indent) {
        out.append(indent).append(error.exceptionClass());
        if (error.message() != null) {
            out.append(": ").append(error.message());
        }
        out.append('\n');
        error.frames().stream().limit(FRAMES_TO_PRINT)
                .forEach(f -> out.append(indent).append("  at ").append(frame(f))
                        .append(f.playerCode() ? "   ← ваш код" : "").append('\n'));
        int hidden = Math.max(0, error.frames().size() - FRAMES_TO_PRINT) + error.omittedFrames();
        if (hidden > 0) {
            out.append(indent).append("  … ещё ").append(hidden).append(" кадров\n");
        }
        error.cause().ifPresent(c -> {
            out.append(indent).append("Причина: ");
            appendError(out, c, indent);
        });
        for (ErrorReport s : error.suppressed()) {
            out.append(indent).append("Подавлено: ");
            appendError(out, s, indent);
        }
    }

    private static String frame(StackFrameInfo f) {
        return f.className() + "." + f.methodName() + "(" + (f.fileName() == null ? "?" : f.fileName())
                + (f.lineNumber() > 0 ? ":" + f.lineNumber() : "") + ")";
    }

    private static String bytes(long value) {
        if (value < 1024) {
            return value + " Б";
        }
        if (value < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f КБ", value / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f МБ", value / (1024.0 * 1024.0));
    }
}
