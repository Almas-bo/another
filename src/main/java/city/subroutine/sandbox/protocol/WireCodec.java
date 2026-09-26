package city.subroutine.sandbox.protocol;

import city.subroutine.sandbox.api.CompilationDiagnostic;
import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.PolicyViolation;
import city.subroutine.sandbox.api.SandboxLimits;
import city.subroutine.sandbox.api.SourceUnit;
import city.subroutine.sandbox.api.StackFrameInfo;
import city.subroutine.sandbox.api.TestMetrics;
import city.subroutine.sandbox.api.TestOutcome;
import city.subroutine.sandbox.api.TestStatus;
import city.subroutine.sandbox.api.ThreadSnapshot;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Кодеки всех сообщений протокола. Порядок полей — часть протокола; хост и воркер всегда из одной сборки. */
public final class WireCodec {

    private static final int MAX_ERROR_DEPTH = 16;
    private static final int ENTRY_METHOD = 1;
    private static final int ENTRY_CONTRACT = 2;

    private WireCodec() {
    }

    // ---------------------------------------------------------------- ExecutionRequest

    public static void writeRequest(WireOutput out, ExecutionRequest request) throws IOException {
        out.writeString(request.requestId());
        out.writeList(request.sources(), (o, unit) -> {
            o.writeString(unit.className());
            o.writeString(unit.code());
        });
        writeEntryPoint(out, request.entryPoint());
        out.writeString(request.testSuiteClass());
        out.writeString(request.playerPackage());
        out.writeStringList(request.allowedApiPackages());
        writeLimits(out, request.limits());
    }

    public static ExecutionRequest readRequest(WireInput in) throws IOException {
        String requestId = in.readRequiredString();
        List<SourceUnit> sources = in.readList(i -> new SourceUnit(i.readRequiredString(), i.readRequiredString()));
        EntryPoint entryPoint = readEntryPoint(in);
        String suite = in.readRequiredString();
        String playerPackage = in.readRequiredString();
        List<String> apiPackages = in.readStringList();
        SandboxLimits limits = readLimits(in);
        return new ExecutionRequest(requestId, sources, entryPoint, suite, playerPackage, apiPackages, limits);
    }

    private static void writeEntryPoint(WireOutput out, EntryPoint entryPoint) throws IOException {
        switch (entryPoint) {
            case EntryPoint.MethodEntry method -> {
                out.writeInt(ENTRY_METHOD);
                out.writeString(method.className());
                out.writeString(method.methodName());
                out.writeStringList(method.parameterTypes());
                out.writeString(method.returnType());
            }
            case EntryPoint.ContractEntry contract -> {
                out.writeInt(ENTRY_CONTRACT);
                out.writeString(contract.className());
                out.writeString(contract.contractInterface());
            }
        }
    }

    private static EntryPoint readEntryPoint(WireInput in) throws IOException {
        int kind = in.readInt();
        return switch (kind) {
            case ENTRY_METHOD -> new EntryPoint.MethodEntry(
                    in.readRequiredString(), in.readRequiredString(), in.readStringList(), in.readRequiredString());
            case ENTRY_CONTRACT -> new EntryPoint.ContractEntry(in.readRequiredString(), in.readRequiredString());
            default -> throw new ProtocolException("Неизвестный тип точки входа: " + kind);
        };
    }

    private static void writeLimits(WireOutput out, SandboxLimits limits) throws IOException {
        out.writeLong(limits.compileTimeout().toMillis());
        out.writeLong(limits.perTestTimeout().toMillis());
        out.writeLong(limits.totalTimeout().toMillis());
        out.writeInt(limits.heapMegabytes());
        out.writeInt(limits.maxThreads());
        out.writeInt(limits.maxOutputBytesPerTest());
        out.writeInt(limits.maxSourceChars());
    }

    private static SandboxLimits readLimits(WireInput in) throws IOException {
        return new SandboxLimits(
                Duration.ofMillis(in.readLong()),
                Duration.ofMillis(in.readLong()),
                Duration.ofMillis(in.readLong()),
                in.readInt(),
                in.readInt(),
                in.readInt(),
                in.readInt());
    }

    // ---------------------------------------------------------------- COMPILATION

    public static void writeCompilation(WireOutput out, CompilationReport report) throws IOException {
        out.writeBoolean(report.success());
        out.writeList(report.diagnostics(), WireCodec::writeDiagnostic);
        out.writeLong(report.compileNanos());
    }

    public static CompilationReport readCompilation(WireInput in) throws IOException {
        boolean success = in.readBoolean();
        List<CompilationDiagnostic> diagnostics = in.readList(WireCodec::readDiagnostic);
        return new CompilationReport(success, diagnostics, in.readLong());
    }

    private static void writeDiagnostic(WireOutput out, CompilationDiagnostic d) throws IOException {
        out.writeEnum(d.kind());
        out.writeString(d.code());
        out.writeString(d.message());
        out.writeString(d.sourceClass());
        out.writeLong(d.line());
        out.writeLong(d.column());
        out.writeLong(d.startPosition());
        out.writeLong(d.endPosition());
    }

    private static CompilationDiagnostic readDiagnostic(WireInput in) throws IOException {
        return new CompilationDiagnostic(
                in.readEnum(CompilationDiagnostic.Kind.class),
                in.readRequiredString(),
                in.readRequiredString(),
                in.readString(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong());
    }

    // ---------------------------------------------------------------- POLICY

    public static void writeViolations(WireOutput out, List<PolicyViolation> violations) throws IOException {
        out.writeList(violations, (o, v) -> {
            o.writeEnum(v.rule());
            o.writeString(v.playerClass());
            o.writeString(v.reference());
            o.writeString(v.detail());
        });
    }

    public static List<PolicyViolation> readViolations(WireInput in) throws IOException {
        return in.readList(i -> new PolicyViolation(
                i.readEnum(PolicyViolation.Rule.class),
                i.readRequiredString(),
                i.readRequiredString(),
                i.readRequiredString()));
    }

    // ---------------------------------------------------------------- SUITE_STARTED

    public static void writeDescriptors(WireOutput out, List<TestDescriptor> descriptors) throws IOException {
        out.writeList(descriptors, (o, d) -> {
            o.writeString(d.id());
            o.writeString(d.title());
        });
    }

    public static List<TestDescriptor> readDescriptors(WireInput in) throws IOException {
        return in.readList(i -> new TestDescriptor(i.readRequiredString(), i.readRequiredString()));
    }

    // ---------------------------------------------------------------- TEST_RESULT

    public static void writeOutcome(WireOutput out, TestOutcome outcome) throws IOException {
        out.writeString(outcome.id());
        out.writeString(outcome.title());
        out.writeEnum(outcome.status());
        out.writeString(outcome.message());
        out.writeString(outcome.expected());
        out.writeString(outcome.actual());
        out.writeOptional(outcome.error(), WireCodec::writeError);
        TestMetrics m = outcome.metrics();
        out.writeLong(m.wallNanos());
        out.writeLong(m.cpuNanos());
        out.writeLong(m.allocatedBytes());
        out.writeLong(m.peakHeapBytes());
        out.writeLong(m.gcCount());
        out.writeLong(m.gcTimeMillis());
        out.writeString(outcome.output());
        out.writeBoolean(outcome.outputTruncated());
        out.writeList(outcome.threads(), WireCodec::writeThread);
        out.writeStringList(outcome.leakedThreads());
    }

    public static TestOutcome readOutcome(WireInput in) throws IOException {
        String id = in.readRequiredString();
        String title = in.readRequiredString();
        TestStatus status = in.readEnum(TestStatus.class);
        String message = in.readString();
        String expected = in.readString();
        String actual = in.readString();
        Optional<ErrorReport> error = in.readOptional(i -> readError(i, 0));
        TestMetrics metrics = new TestMetrics(
                in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong());
        String output = in.readRequiredString();
        boolean truncated = in.readBoolean();
        List<ThreadSnapshot> threads = in.readList(WireCodec::readThread);
        List<String> leaked = in.readStringList();
        return new TestOutcome(id, title, status, message, expected, actual, error, metrics, output, truncated,
                threads, leaked);
    }

    // ---------------------------------------------------------------- FINISHED

    public static void writeFinished(WireOutput out, FinishedReport report) throws IOException {
        out.writeEnum(report.verdict());
        out.writeString(report.detail());
        out.writeOptional(report.fatalError(), WireCodec::writeError);
    }

    public static FinishedReport readFinished(WireInput in) throws IOException {
        return new FinishedReport(
                in.readEnum(WorkerVerdict.class),
                in.readString(),
                in.readOptional(i -> readError(i, 0)));
    }

    // ---------------------------------------------------------------- общие структуры

    private static void writeError(WireOutput out, ErrorReport error) throws IOException {
        out.writeString(error.exceptionClass());
        out.writeString(error.message());
        out.writeList(error.frames(), WireCodec::writeFrame);
        out.writeInt(error.omittedFrames());
        out.writeOptional(error.cause(), WireCodec::writeError);
        out.writeList(error.suppressed(), WireCodec::writeError);
    }

    private static ErrorReport readError(WireInput in, int depth) throws IOException {
        if (depth > MAX_ERROR_DEPTH) {
            throw new ProtocolException("Слишком глубокая цепочка исключений");
        }
        String type = in.readRequiredString();
        String message = in.readString();
        List<StackFrameInfo> frames = in.readList(WireCodec::readFrame);
        int omitted = in.readInt();
        Optional<ErrorReport> cause = in.readOptional(i -> readError(i, depth + 1));
        List<ErrorReport> suppressed = in.readList(i -> readError(i, depth + 1));
        return new ErrorReport(type, message, frames, omitted, cause, suppressed);
    }

    private static void writeFrame(WireOutput out, StackFrameInfo frame) throws IOException {
        out.writeString(frame.className());
        out.writeString(frame.methodName());
        out.writeString(frame.fileName());
        out.writeInt(frame.lineNumber());
        out.writeBoolean(frame.playerCode());
    }

    private static StackFrameInfo readFrame(WireInput in) throws IOException {
        return new StackFrameInfo(in.readRequiredString(), in.readRequiredString(), in.readString(), in.readInt(),
                in.readBoolean());
    }

    private static void writeThread(WireOutput out, ThreadSnapshot thread) throws IOException {
        out.writeString(thread.name());
        out.writeString(thread.state());
        out.writeBoolean(thread.deadlocked());
        out.writeString(thread.lockName());
        out.writeString(thread.lockOwnerName());
        out.writeList(thread.frames(), WireCodec::writeFrame);
    }

    private static ThreadSnapshot readThread(WireInput in) throws IOException {
        return new ThreadSnapshot(in.readRequiredString(), in.readRequiredString(), in.readBoolean(), in.readString(),
                in.readString(), in.readList(WireCodec::readFrame));
    }
}
