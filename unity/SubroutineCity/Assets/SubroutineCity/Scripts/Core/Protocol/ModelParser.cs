using System.Collections.Generic;
using SubroutineCity.Core.Json;

namespace SubroutineCity.Core.Protocol
{
    /// <summary>JSON API v1 → модели. Отсутствующие поля получают безопасные значения по умолчанию.</summary>
    public static class ModelParser
    {
        public static List<LevelSummary> ParseLevels(string json)
        {
            var result = new List<LevelSummary>();
            foreach (var level in JsonValue.ParseObject(json).Objects("levels"))
            {
                var summary = new LevelSummary();
                FillSummary(summary, level);
                result.Add(summary);
            }
            result.Sort((a, b) => a.Order.CompareTo(b.Order));
            return result;
        }

        public static LevelDetail ParseLevel(string json)
        {
            var o = JsonValue.ParseObject(json);
            var level = new LevelDetail();
            FillSummary(level, o);
            level.Brief = o.String("brief", "");
            level.Requirements = o.Strings("requirements");
            level.Goals = o.Strings("goals");
            level.ContractText = o.String("contractText", "");
            level.StarterCode = o.String("starterCode", "");
            level.PlayerPackage = o.String("playerPackage", "city.player");
            var entry = o.Object("entryPoint");
            if (entry != null)
            {
                level.EntryPoint = new EntryPointInfo
                {
                    Kind = entry.String("kind"),
                    ClassName = entry.String("className"),
                    MethodName = entry.String("methodName"),
                    ParameterTypes = entry.Strings("parameterTypes"),
                    ReturnType = entry.String("returnType"),
                    Signature = entry.String("signature"),
                    ContractInterface = entry.String("contractInterface")
                };
            }
            var limits = o.Object("limits");
            if (limits != null)
            {
                level.Limits = new LimitsInfo
                {
                    CompileTimeoutMillis = limits.Long("compileTimeoutMillis"),
                    PerTestTimeoutMillis = limits.Long("perTestTimeoutMillis"),
                    TotalTimeoutMillis = limits.Long("totalTimeoutMillis"),
                    HeapMegabytes = limits.Int("heapMegabytes"),
                    MaxThreads = limits.Int("maxThreads"),
                    MaxSourceChars = limits.Int("maxSourceChars")
                };
            }
            return level;
        }

        /// <summary>Ответ run/check: {"result": ExecutionResult}.</summary>
        public static ExecutionResult ParseRunResponse(string json)
        {
            var result = JsonValue.ParseObject(json).Object("result");
            if (result == null) throw new JsonException("В ответе нет поля result");
            return ParseResult(result);
        }

        /// <summary>Ответ debug: {"result": ExecutionResult, "trace": DebugTrace}.</summary>
        public static DebugResult ParseDebugResponse(string json)
        {
            var o = JsonValue.ParseObject(json);
            var result = o.Object("result");
            if (result == null) throw new JsonException("В ответе нет поля result");
            var trace = o.Object("trace");
            return new DebugResult
            {
                Result = ParseResult(result),
                Trace = trace == null ? new DebugTrace() : ParseTrace(trace)
            };
        }

        /// <summary>Тело ошибки {"error": {"code", "message"}} или null, если формат другой.</summary>
        public static ApiError ParseError(int httpStatus, string json)
        {
            try
            {
                var error = JsonValue.ParseObject(json).Object("error");
                if (error != null)
                    return new ApiError(httpStatus, error.String("code", "unknown"), error.String("message", "Ошибка сервера"));
            }
            catch (JsonException)
            {
                // тело не JSON — ниже общий текст
            }
            return new ApiError(httpStatus, "http_" + httpStatus, "Сервер ответил HTTP " + httpStatus);
        }

        public static ExecutionResult ParseResult(JsonObject o)
        {
            var result = new ExecutionResult
            {
                RequestId = o.String("requestId", ""),
                Status = o.Enum("status", ExecutionStatus.Unknown),
                StatusDetail = o.String("statusDetail"),
                FatalError = ParseError(o.Object("fatalError"), 0)
            };
            foreach (var d in o.Objects("diagnostics"))
            {
                result.Diagnostics.Add(new CompilationDiagnostic
                {
                    Kind = d.Enum("kind", DiagnosticKind.Unknown),
                    Code = d.String("code", ""),
                    Message = d.String("message", ""),
                    SourceClass = d.String("sourceClass"),
                    Line = d.Long("line", -1),
                    Column = d.Long("column", -1),
                    StartPosition = d.Long("startPosition", -1),
                    EndPosition = d.Long("endPosition", -1)
                });
            }
            foreach (var v in o.Objects("policyViolations"))
            {
                result.PolicyViolations.Add(new PolicyViolation
                {
                    Rule = v.Enum("rule", PolicyRule.Unknown),
                    PlayerClass = v.String("playerClass", ""),
                    Reference = v.String("reference", ""),
                    Detail = v.String("detail", "")
                });
            }
            foreach (var t in o.Objects("tests")) result.Tests.Add(ParseOutcome(t));
            var m = o.Object("metrics");
            if (m != null)
            {
                result.Metrics = new ExecutionMetrics
                {
                    CompileNanos = m.Long("compileNanos"),
                    ExecutionWallNanos = m.Long("executionWallNanos"),
                    TotalCpuNanos = m.Long("totalCpuNanos"),
                    TotalAllocatedBytes = m.Long("totalAllocatedBytes"),
                    PeakHeapBytes = m.Long("peakHeapBytes"),
                    GcCount = m.Long("gcCount"),
                    GcTimeMillis = m.Long("gcTimeMillis"),
                    WorkerExitCode = m.Int("workerExitCode", -1)
                };
            }
            return result;
        }

        private static TestOutcome ParseOutcome(JsonObject t)
        {
            var outcome = new TestOutcome
            {
                Id = t.String("id", ""),
                Title = t.String("title", ""),
                Status = t.Enum("status", TestStatus.Unknown),
                Message = t.String("message"),
                Expected = t.String("expected"),
                Actual = t.String("actual"),
                Error = ParseError(t.Object("error"), 0),
                Output = t.String("output", ""),
                OutputTruncated = t.Bool("outputTruncated"),
                LeakedThreads = t.Strings("leakedThreads")
            };
            var m = t.Object("metrics");
            if (m != null)
            {
                outcome.Metrics = new TestMetrics
                {
                    WallNanos = m.Long("wallNanos"),
                    CpuNanos = m.Long("cpuNanos"),
                    AllocatedBytes = m.Long("allocatedBytes"),
                    PeakHeapBytes = m.Long("peakHeapBytes"),
                    GcCount = m.Long("gcCount"),
                    GcTimeMillis = m.Long("gcTimeMillis")
                };
            }
            foreach (var thread in t.Objects("threads"))
            {
                outcome.Threads.Add(new ThreadSnapshot
                {
                    Name = thread.String("name", "?"),
                    State = thread.String("state", ""),
                    Deadlocked = thread.Bool("deadlocked"),
                    LockName = thread.String("lockName"),
                    LockOwnerName = thread.String("lockOwnerName"),
                    Frames = ParseFrames(thread)
                });
            }
            return outcome;
        }

        private static ErrorReport ParseError(JsonObject e, int depth)
        {
            if (e == null || depth > 16) return null;
            var report = new ErrorReport
            {
                ExceptionClass = e.String("exceptionClass", "?"),
                Message = e.String("message"),
                Frames = ParseFrames(e),
                OmittedFrames = e.Int("omittedFrames"),
                Cause = ParseError(e.Object("cause"), depth + 1)
            };
            foreach (var s in e.Objects("suppressed"))
            {
                var suppressed = ParseError(s, depth + 1);
                if (suppressed != null) report.Suppressed.Add(suppressed);
            }
            return report;
        }

        private static List<StackFrameInfo> ParseFrames(JsonObject owner, string key = "frames")
        {
            var frames = new List<StackFrameInfo>();
            foreach (var f in owner.Objects(key))
            {
                frames.Add(new StackFrameInfo
                {
                    ClassName = f.String("className", "?"),
                    MethodName = f.String("methodName", "?"),
                    FileName = f.String("fileName"),
                    LineNumber = f.Int("lineNumber", -1),
                    PlayerCode = f.Bool("playerCode")
                });
            }
            return frames;
        }

        private static DebugTrace ParseTrace(JsonObject t)
        {
            var trace = new DebugTrace
            {
                TestId = t.String("testId", ""),
                Truncated = t.Bool("truncated"),
                MaxSteps = t.Int("maxSteps"),
                Note = t.String("note")
            };
            foreach (var s in t.Objects("steps"))
            {
                var step = new TraceStep
                {
                    Index = s.Int("index"),
                    Thread = s.String("thread", "?"),
                    Depth = s.Int("depth"),
                    ClassName = s.String("className", "?"),
                    Method = s.String("method", "?"),
                    Line = s.Int("line", -1),
                    Stack = ParseFrames(s, "stack")
                };
                foreach (var v in s.Objects("locals"))
                    step.Locals.Add(new VariableValue { Name = v.String("name", "?"), Type = v.String("type", ""), Value = v.String("value", "") });
                trace.Steps.Add(step);
            }
            return trace;
        }

        private static void FillSummary(LevelSummary level, JsonObject o)
        {
            level.Id = o.String("id", "");
            level.Title = o.String("title", "");
            level.Chapter = o.String("chapter", "");
            level.Order = o.Int("order");
            level.Difficulty = o.Int("difficulty", 1);
            level.District = o.String("district", "power");
            level.Summary = o.String("summary", "");
            foreach (var t in o.Objects("tests"))
                level.Tests.Add(new TestInfo { Id = t.String("id", ""), Title = t.String("title", "") });
        }
    }

    /// <summary>Пути и тела запросов API v1.</summary>
    public static class ApiRoutes
    {
        public const string Health = "/api/v1/health";
        public const string Levels = "/api/v1/levels";

        public static string Level(string id) => Levels + "/" + id;
        public static string Run(string id) => Level(id) + "/run";
        public static string Check(string id) => Level(id) + "/check";
        public static string Debug(string id) => Level(id) + "/debug";

        public static string CodeBody(string code)
        {
            return JsonValue.Write(new Dictionary<string, object> { { "code", code ?? "" } });
        }

        public static string DebugBody(string code, string testId)
        {
            return JsonValue.Write(new Dictionary<string, object> { { "code", code ?? "" }, { "testId", testId } });
        }
    }
}
