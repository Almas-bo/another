using System.Collections.Generic;

namespace SubroutineCity.Core.Protocol
{
    // Модели JSON API v1 сервера песочницы. Имена и смысл полей совпадают с Java-записями
    // city.subroutine.sandbox.api.* (см. ApiMapper на сервере). Неизвестные значения перечислений → Unknown.

    public enum ExecutionStatus
    {
        Unknown,
        SUCCESS,
        COMPILED,
        TESTS_FAILED,
        COMPILATION_ERROR,
        POLICY_VIOLATION,
        CONTRACT_VIOLATION,
        TIMEOUT,
        DEADLOCK,
        MEMORY_LIMIT_EXCEEDED,
        THREAD_LIMIT_EXCEEDED,
        REJECTED,
        SANDBOX_FAILURE
    }

    public enum TestStatus
    {
        Unknown,
        PASSED,
        FAILED,
        ERROR,
        TIMEOUT,
        DEADLOCK,
        MEMORY_LIMIT_EXCEEDED,
        THREAD_LIMIT_EXCEEDED,
        SKIPPED,
        SANDBOX_CRASH
    }

    public enum DiagnosticKind { Unknown, ERROR, WARNING, NOTE }

    public enum PolicyRule { Unknown, FORBIDDEN_TYPE, FORBIDDEN_MEMBER, NATIVE_METHOD, WRONG_PACKAGE, MALFORMED_CLASS }

    public sealed class TestInfo
    {
        public string Id;
        public string Title;
    }

    public sealed class EntryPointInfo
    {
        /// <summary>"method" или "contract".</summary>
        public string Kind;
        public string ClassName;
        public string MethodName;
        public List<string> ParameterTypes = new List<string>();
        public string ReturnType;
        public string Signature;
        public string ContractInterface;
    }

    public sealed class LimitsInfo
    {
        public long CompileTimeoutMillis;
        public long PerTestTimeoutMillis;
        public long TotalTimeoutMillis;
        public int HeapMegabytes;
        public int MaxThreads;
        public int MaxSourceChars;
    }

    public class LevelSummary
    {
        public string Id;
        public string Title;
        public string Chapter;
        public int Order;
        public int Difficulty;
        /// <summary>Визуальная тема района: power, water, warehouse, telemetry, traffic, energy.</summary>
        public string District;
        public string Summary;
        public List<TestInfo> Tests = new List<TestInfo>();
    }

    public sealed class LevelDetail : LevelSummary
    {
        public string Brief;
        public List<string> Requirements = new List<string>();
        public List<string> Goals = new List<string>();
        public string ContractText;
        public string StarterCode;
        public string PlayerPackage;
        public EntryPointInfo EntryPoint;
        public LimitsInfo Limits;
    }

    public sealed class CompilationDiagnostic
    {
        public DiagnosticKind Kind;
        public string Code;
        public string Message;
        public string SourceClass;
        public long Line;
        public long Column;
        public long StartPosition;
        public long EndPosition;
    }

    public sealed class PolicyViolation
    {
        public PolicyRule Rule;
        public string PlayerClass;
        public string Reference;
        public string Detail;
    }

    public sealed class StackFrameInfo
    {
        public string ClassName;
        public string MethodName;
        public string FileName;
        public int LineNumber;
        public bool PlayerCode;

        public override string ToString()
        {
            return ClassName + "." + MethodName + "(" + (FileName ?? "?") + (LineNumber > 0 ? ":" + LineNumber : "") + ")";
        }
    }

    public sealed class ErrorReport
    {
        public string ExceptionClass;
        public string Message;
        public List<StackFrameInfo> Frames = new List<StackFrameInfo>();
        public int OmittedFrames;
        public ErrorReport Cause;
        public List<ErrorReport> Suppressed = new List<ErrorReport>();

        /// <summary>Первый кадр из кода игрока — «место аварии».</summary>
        public StackFrameInfo FirstPlayerFrame()
        {
            foreach (var frame in Frames)
                if (frame.PlayerCode) return frame;
            return Cause?.FirstPlayerFrame();
        }

        public string ShortClassName
        {
            get
            {
                int dot = ExceptionClass?.LastIndexOf('.') ?? -1;
                return dot < 0 ? ExceptionClass : ExceptionClass.Substring(dot + 1);
            }
        }
    }

    public sealed class ThreadSnapshot
    {
        public string Name;
        public string State;
        public bool Deadlocked;
        public string LockName;
        public string LockOwnerName;
        public List<StackFrameInfo> Frames = new List<StackFrameInfo>();
    }

    public sealed class TestMetrics
    {
        public long WallNanos;
        public long CpuNanos;
        public long AllocatedBytes;
        public long PeakHeapBytes;
        public long GcCount;
        public long GcTimeMillis;
    }

    public sealed class TestOutcome
    {
        public string Id;
        public string Title;
        public TestStatus Status;
        public string Message;
        public string Expected;
        public string Actual;
        public ErrorReport Error;
        public TestMetrics Metrics = new TestMetrics();
        public string Output = "";
        public bool OutputTruncated;
        public List<ThreadSnapshot> Threads = new List<ThreadSnapshot>();
        public List<string> LeakedThreads = new List<string>();

        public bool Passed => Status == TestStatus.PASSED;
    }

    public sealed class ExecutionMetrics
    {
        public long CompileNanos;
        public long ExecutionWallNanos;
        public long TotalCpuNanos;
        public long TotalAllocatedBytes;
        public long PeakHeapBytes;
        public long GcCount;
        public long GcTimeMillis;
        public int WorkerExitCode;
    }

    public sealed class ExecutionResult
    {
        public string RequestId;
        public ExecutionStatus Status;
        public string StatusDetail;
        public List<CompilationDiagnostic> Diagnostics = new List<CompilationDiagnostic>();
        public List<PolicyViolation> PolicyViolations = new List<PolicyViolation>();
        public List<TestOutcome> Tests = new List<TestOutcome>();
        public ExecutionMetrics Metrics = new ExecutionMetrics();
        public ErrorReport FatalError;

        public int PassedCount
        {
            get
            {
                int passed = 0;
                foreach (var test in Tests)
                    if (test.Passed) passed++;
                return passed;
            }
        }

        public TestOutcome Test(string id)
        {
            foreach (var test in Tests)
                if (test.Id == id) return test;
            return null;
        }
    }

    public sealed class VariableValue
    {
        public string Name;
        public string Type;
        public string Value;
    }

    public sealed class TraceStep
    {
        public int Index;
        public string Thread;
        public int Depth;
        public string ClassName;
        public string Method;
        public int Line;
        public List<VariableValue> Locals = new List<VariableValue>();
        public List<StackFrameInfo> Stack = new List<StackFrameInfo>();
    }

    public sealed class DebugTrace
    {
        public string TestId;
        public bool Truncated;
        public int MaxSteps;
        public string Note;
        public List<TraceStep> Steps = new List<TraceStep>();
    }

    public sealed class DebugResult
    {
        public ExecutionResult Result;
        public DebugTrace Trace;
    }

    public sealed class ApiError
    {
        public ApiError(int httpStatus, string code, string message)
        {
            HttpStatus = httpStatus;
            Code = code;
            Message = message;
        }

        /// <summary>HTTP-статус; 0 — сервер недоступен (сетевой сбой).</summary>
        public int HttpStatus { get; }
        public string Code { get; }
        public string Message { get; }

        public override string ToString() => Message + " (" + Code + ", HTTP " + HttpStatus + ")";
    }
}
