using System.Linq;
using NUnit.Framework;
using SubroutineCity.Core.Json;
using SubroutineCity.Core.Localization;
using SubroutineCity.Core.Protocol;

namespace SubroutineCity.Core.Tests
{
    public class ProtocolTests
    {
        [Test]
        public void ParsesCampaignLevels()
        {
            var levels = ModelParser.ParseLevels(Fixtures.Read("levels.json"));
            Assert.That(levels.Count, Is.EqualTo(6));
            Assert.That(levels[0].Id, Is.EqualTo("powergrid-01"));
            Assert.That(levels.Select(l => l.Order), Is.Ordered);
            Assert.That(levels[0].Tests.Count, Is.EqualTo(7));
            Assert.That(levels.Select(l => l.District), Does.Contain("energy"));
        }

        [Test]
        public void ParsesLevelDetail()
        {
            var level = ModelParser.ParseLevel(Fixtures.Read("level-energy.json"));
            Assert.That(level.EntryPoint.Kind, Is.EqualTo("contract"));
            Assert.That(level.ContractText, Does.Contain("interface EnergyGrid"));
            Assert.That(level.Requirements, Is.Not.Empty);
            Assert.That(level.Limits.HeapMegabytes, Is.EqualTo(128));
            Assert.That(level.StarterCode, Does.StartWith("package city.player;"));
        }

        [Test]
        public void ParsesFailedTestsWithExpectedActualAndPlayerFrame()
        {
            var result = ModelParser.ParseRunResponse(Fixtures.Read("run-tests-failed.json"));
            Assert.That(result.Status, Is.EqualTo(ExecutionStatus.TESTS_FAILED));
            var overflow = result.Test("int-overflow");
            Assert.That(overflow.Status, Is.EqualTo(TestStatus.FAILED));
            Assert.That(overflow.Expected, Is.EqualTo("4294967299"));
            Assert.That(overflow.Actual, Is.EqualTo("3"));
            var npe = result.Test("null-grid").Error;
            Assert.That(npe.ExceptionClass, Is.EqualTo("java.lang.NullPointerException"));
            Assert.That(npe.FirstPlayerFrame().LineNumber, Is.EqualTo(13));
            Assert.That(result.Test("hot-loop-allocations").Metrics.AllocatedBytes, Is.GreaterThan(1_000_000));
        }

        [Test]
        public void ParsesDeadlockThreadGraph()
        {
            var result = ModelParser.ParseRunResponse(Fixtures.Read("run-deadlock.json"));
            Assert.That(result.Status, Is.EqualTo(ExecutionStatus.DEADLOCK));
            var test = result.Tests.First(t => t.Status == TestStatus.DEADLOCK);
            // JVM помечает и потоки, ждущие участника цикла; сам цикл выделяет DeadlockGraph
            var graph = new SubroutineCity.Core.City.DeadlockGraph(test.Threads);
            Assert.That(graph.HasCycle, Is.True);
            Assert.That(graph.Cycle.Count, Is.EqualTo(2));
            var byName = test.Threads.ToDictionary(t => t.Name);
            foreach (string name in graph.Cycle)
            {
                Assert.That(byName[name].Deadlocked, Is.True);
                Assert.That(byName[name].State, Is.EqualTo("BLOCKED"));
                Assert.That(graph.Cycle, Does.Contain(byName[name].LockOwnerName));
            }
            Assert.That(graph.Waiting, Is.Not.Empty);
            Assert.That(result.Tests.Last().Status, Is.EqualTo(TestStatus.SKIPPED));
        }

        [Test]
        public void ParsesPolicyMemoryAndSuccess()
        {
            var policy = ModelParser.ParseRunResponse(Fixtures.Read("run-policy.json"));
            Assert.That(policy.Status, Is.EqualTo(ExecutionStatus.POLICY_VIOLATION));
            Assert.That(policy.PolicyViolations.Any(v => v.Reference == "java.io.File"));
            Assert.That(policy.PolicyViolations.Any(v => v.Rule == PolicyRule.FORBIDDEN_MEMBER));

            var memory = ModelParser.ParseRunResponse(Fixtures.Read("run-memory.json"));
            Assert.That(memory.Status, Is.EqualTo(ExecutionStatus.MEMORY_LIMIT_EXCEEDED));
            Assert.That(memory.Test("sensor-storm").Status, Is.EqualTo(TestStatus.MEMORY_LIMIT_EXCEEDED));
            Assert.That(memory.Metrics.WorkerExitCode, Is.EqualTo(3));

            var success = ModelParser.ParseRunResponse(Fixtures.Read("run-success.json"));
            Assert.That(success.Status, Is.EqualTo(ExecutionStatus.SUCCESS));
            Assert.That(success.PassedCount, Is.EqualTo(success.Tests.Count));
        }

        [Test]
        public void CompilationDiagnosticIsExplainedInRussian()
        {
            var result = ModelParser.ParseRunResponse(Fixtures.Read("check-compile-error.json"));
            Assert.That(result.Status, Is.EqualTo(ExecutionStatus.COMPILATION_ERROR));
            var error = result.Diagnostics.First(d => d.Kind == DiagnosticKind.ERROR);
            Assert.That(error.Line, Is.EqualTo(4));
            Assert.That(Ru.ExplainDiagnostic(error), Is.EqualTo("Ожидается ';'"));
        }

        [Test]
        public void ExplainsUnknownSymbol()
        {
            var diagnostic = new CompilationDiagnostic
            {
                Code = "compiler.err.cant.resolve.location",
                Message = "cannot find symbol\n  symbol:   variable total\n  location: class city.player.PowerGrid"
            };
            Assert.That(Ru.ExplainDiagnostic(diagnostic), Does.EndWith("переменная total"));
        }

        [Test]
        public void ParsesDebugTrace()
        {
            var debug = ModelParser.ParseDebugResponse(Fixtures.Read("debug-trace.json"));
            Assert.That(debug.Result.Tests.Count, Is.EqualTo(1));
            Assert.That(debug.Trace.Steps, Is.Not.Empty);
            Assert.That(debug.Trace.Steps.All(s => s.Line > 0));
            Assert.That(debug.Trace.Steps.SelectMany(s => s.Locals).Any(v => v.Name == "sectorLoads"));
        }

        [Test]
        public void ErrorBodyAndGarbageAreHandled()
        {
            var error = ModelParser.ParseError(404, "{\"error\":{\"code\":\"unknown_level\",\"message\":\"Нет уровня\"}}");
            Assert.That(error.Code, Is.EqualTo("unknown_level"));
            Assert.That(ModelParser.ParseError(502, "<html>").Code, Is.EqualTo("http_502"));
        }

        [Test]
        public void UnknownEnumValuesDegradeGracefully()
        {
            var result = ModelParser.ParseResult(JsonValue.ParseObject(
                "{\"status\":\"SOMETHING_NEW\",\"tests\":[{\"id\":\"t\",\"status\":\"42\"}]}"));
            Assert.That(result.Status, Is.EqualTo(ExecutionStatus.Unknown));
            Assert.That(result.Tests[0].Status, Is.EqualTo(TestStatus.Unknown));
        }

        [Test]
        public void RequestBodiesAreValidJson()
        {
            string body = ApiRoutes.DebugBody("class A { String s = \"\\\"\"; }\n", "basic-sum");
            var parsed = JsonValue.ParseObject(body);
            Assert.That(parsed.String("code"), Is.EqualTo("class A { String s = \"\\\"\"; }\n"));
            Assert.That(parsed.String("testId"), Is.EqualTo("basic-sum"));
        }
    }

    public class JsonTests
    {
        [TestCase("")]
        [TestCase("{")]
        [TestCase("[1,]")]
        [TestCase("{\"a\":1,\"a\":2}")]
        [TestCase("\"\\x\"")]
        public void RejectsMalformed(string text)
        {
            Assert.Throws<JsonException>(() => JsonValue.Parse(text));
        }

        [Test]
        public void RoundTripsUnicodeAndNumbers()
        {
            string json = JsonValue.Write(new System.Collections.Generic.Dictionary<string, object>
            {
                { "текст", "кавычка \" слэш \\ \u2028 \t" }, { "n", 9007199254740993L }, { "d", 0.25 }, { "b", true }, { "z", null }
            });
            var o = JsonValue.ParseObject(json);
            Assert.That(o.String("текст"), Is.EqualTo("кавычка \" слэш \\ \u2028 \t"));
            Assert.That(o.Long("n"), Is.EqualTo(9007199254740993L));
            Assert.That(o.Double("d"), Is.EqualTo(0.25));
            Assert.That(o.Bool("b"), Is.True);
            Assert.That(o.Has("z"), Is.False);
        }
    }
}
