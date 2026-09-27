package city.subroutine.server;

import city.subroutine.levels.LevelDefinition;
import city.subroutine.levels.LevelInfo;
import city.subroutine.sandbox.api.CompilationDiagnostic;
import city.subroutine.sandbox.api.DebugResult;
import city.subroutine.sandbox.api.DebugTrace;
import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.ExecutionMetrics;
import city.subroutine.sandbox.api.ExecutionResult;
import city.subroutine.sandbox.api.PolicyViolation;
import city.subroutine.sandbox.api.SandboxLimits;
import city.subroutine.sandbox.api.StackFrameInfo;
import city.subroutine.sandbox.api.TestMetrics;
import city.subroutine.sandbox.api.TestOutcome;
import city.subroutine.sandbox.api.ThreadSnapshot;
import city.subroutine.sandbox.api.TraceStep;
import city.subroutine.sandbox.api.VariableValue;
import city.subroutine.sandbox.protocol.TestDescriptor;

import java.util.List;
import java.util.Map;

import static city.subroutine.server.json.Json.obj;

/**
 * Отображение доменных объектов в JSON API v1. Имена полей совпадают с компонентами Java-записей —
 * это контракт с клиентом (Unity: SubroutineCity.Core.Protocol). Любое изменение — только добавлением полей.
 */
public final class ApiMapper {

    private ApiMapper() {
    }

    public static Map<String, Object> levelSummary(LevelDefinition level, List<TestDescriptor> tests) {
        LevelInfo info = level.info();
        return obj(
                "id", level.id(),
                "title", level.title(),
                "chapter", info.chapter(),
                "order", info.order(),
                "difficulty", info.difficulty(),
                "district", info.district(),
                "summary", info.summary(),
                "tests", tests.stream().map(t -> obj("id", t.id(), "title", t.title())).toList());
    }

    public static Map<String, Object> levelDetail(LevelDefinition level, List<TestDescriptor> tests) {
        LevelInfo info = level.info();
        Map<String, Object> result = levelSummary(level, tests);
        result.put("brief", info.brief());
        result.put("requirements", info.requirements());
        result.put("goals", info.goals());
        result.put("contractText", info.contractText());
        result.put("starterCode", info.starterCode());
        result.put("playerPackage", level.playerPackage());
        result.put("entryPoint", entryPoint(level.entryPoint()));
        result.put("limits", limits(level.limits()));
        return result;
    }

    private static Map<String, Object> entryPoint(EntryPoint entryPoint) {
        return switch (entryPoint) {
            case EntryPoint.MethodEntry m -> obj("kind", "method", "className", m.className(),
                    "methodName", m.methodName(), "parameterTypes", m.parameterTypes(), "returnType", m.returnType(),
                    "signature", m.signature());
            case EntryPoint.ContractEntry c -> obj("kind", "contract", "className", c.className(),
                    "contractInterface", c.contractInterface());
        };
    }

    private static Map<String, Object> limits(SandboxLimits limits) {
        return obj(
                "compileTimeoutMillis", limits.compileTimeout().toMillis(),
                "perTestTimeoutMillis", limits.perTestTimeout().toMillis(),
                "totalTimeoutMillis", limits.totalTimeout().toMillis(),
                "heapMegabytes", limits.heapMegabytes(),
                "maxThreads", limits.maxThreads(),
                "maxSourceChars", limits.maxSourceChars());
    }

    public static Map<String, Object> result(ExecutionResult result) {
        return obj(
                "requestId", result.requestId(),
                "status", result.status(),
                "statusDetail", result.statusDetail(),
                "diagnostics", result.diagnostics().stream().map(ApiMapper::diagnostic).toList(),
                "policyViolations", result.policyViolations().stream().map(ApiMapper::violation).toList(),
                "tests", result.tests().stream().map(ApiMapper::outcome).toList(),
                "metrics", metrics(result.metrics()),
                "fatalError", result.fatalError().map(ApiMapper::error).orElse(null));
    }

    public static Map<String, Object> debug(DebugResult debug) {
        return obj("result", result(debug.result()), "trace", trace(debug.trace()));
    }

    private static Map<String, Object> diagnostic(CompilationDiagnostic d) {
        return obj("kind", d.kind(), "code", d.code(), "message", d.message(), "sourceClass", d.sourceClass(),
                "line", d.line(), "column", d.column(), "startPosition", d.startPosition(),
                "endPosition", d.endPosition());
    }

    private static Map<String, Object> violation(PolicyViolation v) {
        return obj("rule", v.rule(), "playerClass", v.playerClass(), "reference", v.reference(), "detail", v.detail());
    }

    private static Map<String, Object> outcome(TestOutcome t) {
        return obj(
                "id", t.id(),
                "title", t.title(),
                "status", t.status(),
                "message", t.message(),
                "expected", t.expected(),
                "actual", t.actual(),
                "error", t.error().map(ApiMapper::error).orElse(null),
                "metrics", testMetrics(t.metrics()),
                "output", t.output(),
                "outputTruncated", t.outputTruncated(),
                "threads", t.threads().stream().map(ApiMapper::thread).toList(),
                "leakedThreads", t.leakedThreads());
    }

    private static Map<String, Object> testMetrics(TestMetrics m) {
        return obj("wallNanos", m.wallNanos(), "cpuNanos", m.cpuNanos(), "allocatedBytes", m.allocatedBytes(),
                "peakHeapBytes", m.peakHeapBytes(), "gcCount", m.gcCount(), "gcTimeMillis", m.gcTimeMillis());
    }

    private static Map<String, Object> metrics(ExecutionMetrics m) {
        return obj("compileNanos", m.compileNanos(), "executionWallNanos", m.executionWallNanos(),
                "totalCpuNanos", m.totalCpuNanos(), "totalAllocatedBytes", m.totalAllocatedBytes(),
                "peakHeapBytes", m.peakHeapBytes(), "gcCount", m.gcCount(), "gcTimeMillis", m.gcTimeMillis(),
                "workerExitCode", m.workerExitCode());
    }

    private static Map<String, Object> error(ErrorReport e) {
        return obj(
                "exceptionClass", e.exceptionClass(),
                "message", e.message(),
                "frames", e.frames().stream().map(ApiMapper::frame).toList(),
                "omittedFrames", e.omittedFrames(),
                "cause", e.cause().map(ApiMapper::error).orElse(null),
                "suppressed", e.suppressed().stream().map(ApiMapper::error).toList());
    }

    private static Map<String, Object> frame(StackFrameInfo f) {
        return obj("className", f.className(), "methodName", f.methodName(), "fileName", f.fileName(),
                "lineNumber", f.lineNumber(), "playerCode", f.playerCode());
    }

    private static Map<String, Object> thread(ThreadSnapshot t) {
        return obj("name", t.name(), "state", t.state(), "deadlocked", t.deadlocked(), "lockName", t.lockName(),
                "lockOwnerName", t.lockOwnerName(), "frames", t.frames().stream().map(ApiMapper::frame).toList());
    }

    private static Map<String, Object> trace(DebugTrace trace) {
        return obj(
                "testId", trace.testId(),
                "truncated", trace.truncated(),
                "maxSteps", trace.maxSteps(),
                "note", trace.note(),
                "steps", trace.steps().stream().map(ApiMapper::step).toList());
    }

    private static Map<String, Object> step(TraceStep s) {
        return obj(
                "index", s.index(),
                "thread", s.thread(),
                "depth", s.depth(),
                "className", s.className(),
                "method", s.method(),
                "line", s.line(),
                "locals", s.locals().stream().map(ApiMapper::variable).toList(),
                "stack", s.stack().stream().map(ApiMapper::frame).toList());
    }

    private static Map<String, Object> variable(VariableValue v) {
        return obj("name", v.name(), "type", v.type(), "value", v.value());
    }
}
