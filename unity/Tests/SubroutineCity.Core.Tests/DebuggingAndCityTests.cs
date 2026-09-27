using System.Collections.Generic;
using System.Linq;
using NUnit.Framework;
using SubroutineCity.Core.City;
using SubroutineCity.Core.Debugging;
using SubroutineCity.Core.Progress;
using SubroutineCity.Core.Protocol;

namespace SubroutineCity.Core.Tests
{
    public class TraceNavigatorTests
    {
        private static TraceStep Step(int index, int depth, int line, string method = "run", string thread = "main", params (string, string)[] locals)
        {
            var step = new TraceStep { Index = index, Depth = depth, Line = line, Method = method, ClassName = "city.player.A", Thread = thread };
            foreach (var (name, value) in locals) step.Locals.Add(new VariableValue { Name = name, Type = "int", Value = value });
            return step;
        }

        // run():10 → helper():20,21 → run():11 → run():12 (breakpoint)
        private static DebugTrace Sample()
        {
            return new DebugTrace
            {
                Steps = new List<TraceStep>
                {
                    Step(0, 5, 10, locals: ("i", "0")),
                    Step(1, 6, 20, "helper"),
                    Step(2, 6, 21, "helper"),
                    Step(3, 5, 11, locals: ("i", "1")),
                    Step(4, 5, 12, locals: ("i", "1")),
                    Step(5, 4, 30, "outer")
                }
            };
        }

        [Test]
        public void StepOverSkipsCalls()
        {
            var nav = new TraceNavigator(Sample());
            Assert.That(nav.StepOver(), Is.True);
            Assert.That(nav.Current.Line, Is.EqualTo(11));
            Assert.That(nav.StepBackOver(), Is.True);
            Assert.That(nav.Current.Line, Is.EqualTo(10));
        }

        [Test]
        public void StepIntoAndOut()
        {
            var nav = new TraceNavigator(Sample());
            nav.StepInto();
            Assert.That(nav.Current.Method, Is.EqualTo("helper"));
            nav.StepOut();
            Assert.That(nav.Current.Line, Is.EqualTo(11));
            nav.StepOut();
            Assert.That(nav.Current.Method, Is.EqualTo("outer"));
            Assert.That(nav.AtEnd, Is.True);
            Assert.That(nav.StepInto(), Is.False);
        }

        [Test]
        public void ContinueStopsAtBreakpointsBothWays()
        {
            var nav = new TraceNavigator(Sample(), new[] { 21, 12 });
            nav.Continue();
            Assert.That(nav.Current.Line, Is.EqualTo(21));
            nav.Continue();
            Assert.That(nav.Current.Line, Is.EqualTo(12));
            nav.Continue();
            Assert.That(nav.AtEnd, Is.True);
            nav.ReverseContinue();
            Assert.That(nav.Current.Line, Is.EqualTo(12));
            nav.ReverseContinue();
            Assert.That(nav.Current.Line, Is.EqualTo(21));
        }

        [Test]
        public void ChangedVariablesComparesSameFrame()
        {
            var nav = new TraceNavigator(Sample());
            nav.JumpTo(3);
            Assert.That(nav.ChangedVariables(), Is.EquivalentTo(new[] { "i" }));
            nav.JumpTo(4);
            Assert.That(nav.ChangedVariables(), Is.Empty);
            Assert.That(nav.LineHits()[21], Is.EqualTo(1));
        }

        [Test]
        public void EmptyTraceIsSafe()
        {
            var nav = new TraceNavigator(new DebugTrace());
            Assert.That(nav.Current, Is.Null);
            Assert.That(nav.StepOver() || nav.StepInto() || nav.Continue() || nav.StepBack(), Is.False);
        }

        [Test]
        public void WorksOnRealServerTrace()
        {
            var debug = ModelParser.ParseDebugResponse(Fixtures.Read("debug-trace.json"));
            var nav = new TraceNavigator(debug.Trace);
            int steps = 0;
            while (nav.StepOver()) steps++;
            Assert.That(steps, Is.GreaterThan(0));
            Assert.That(nav.Current.Method, Is.EqualTo("totalLoad"));
        }
    }

    public class CityTests
    {
        [Test]
        public void LayoutIsDeterministicAndNonOverlapping()
        {
            var a = CityLayout.PlanDistrict(2, 6, 7, "water");
            var b = CityLayout.PlanDistrict(2, 6, 7, "water");
            Assert.That(a.Buildings.Select(x => (x.Position.X, x.Position.Z, x.Height)),
                Is.EqualTo(b.Buildings.Select(x => (x.Position.X, x.Position.Z, x.Height))));
            Assert.That(a.Buildings.Count, Is.EqualTo(7));
            Assert.That(a.Buildings.All(x => x.Shape == BuildingShape.Tank));
            for (int i = 0; i < a.Buildings.Count; i++)
            for (int j = i + 1; j < a.Buildings.Count; j++)
            {
                var d = new Vec2(a.Buildings[i].Position.X - a.Buildings[j].Position.X, a.Buildings[i].Position.Z - a.Buildings[j].Position.Z);
                Assert.That(d.Length, Is.GreaterThanOrEqualTo(CityLayout.Spacing - 0.01f));
            }
        }

        [Test]
        public void DistrictsSitOnTheRing()
        {
            for (int i = 0; i < 6; i++)
            {
                var plan = CityLayout.PlanDistrict(i, 6, 5, "power");
                Assert.That(plan.Center.Length, Is.EqualTo(CityLayout.RingRadius).Within(0.01f));
            }
        }

        [Test]
        public void EveryTestStatusHasAVisualMode()
        {
            foreach (TestStatus status in System.Enum.GetValues(typeof(TestStatus)))
            {
                var visual = Palette.ForMode(Palette.ModeFor(status));
                Assert.That(visual.Opacity, Is.GreaterThan(0), status.ToString());
            }
            Assert.That(Palette.ModeFor(TestStatus.DEADLOCK), Is.EqualTo(BuildingMode.Locked));
            Assert.That(Palette.Heat(0, TestStatus.PASSED), Is.EqualTo(0));
            Assert.That(Palette.Heat(10, TestStatus.MEMORY_LIMIT_EXCEEDED), Is.EqualTo(1));
            Assert.That(Palette.Heat(80_000_000, TestStatus.FAILED), Is.InRange(0.8f, 1f));
        }
    }

    public class ProgressTests
    {
        [Test]
        public void RecordsCompletionAndRoundTrips()
        {
            var state = new ProgressState();
            state.Level("powergrid-01").Draft = "package city.player;\n// «черновик»";
            var failed = ModelParser.ParseRunResponse(Fixtures.Read("run-tests-failed.json"));
            var success = ModelParser.ParseRunResponse(Fixtures.Read("run-success.json"));
            Assert.That(state.Record("powergrid-01", failed), Is.False);
            Assert.That(state.Record("water-01", success), Is.True);
            Assert.That(state.Record("water-01", success), Is.False);

            var restored = ProgressState.Deserialize(state.Serialize());
            Assert.That(restored.IsCompleted("water-01"), Is.True);
            Assert.That(restored.IsCompleted("powergrid-01"), Is.False);
            Assert.That(restored.Level("powergrid-01").Draft, Is.EqualTo("package city.player;\n// «черновик»"));
            Assert.That(restored.Level("water-01").Attempts, Is.EqualTo(2));
            Assert.That(restored.CompletedCount, Is.EqualTo(1));
        }

        [Test]
        public void CorruptedSaveGivesEmptyProgress()
        {
            Assert.That(ProgressState.Deserialize("{not json").CompletedCount, Is.EqualTo(0));
            Assert.That(ProgressState.Deserialize(null).ServerUrl, Does.StartWith("http://"));
        }
    }
}

namespace SubroutineCity.Core.Tests
{
    public class DeadlockGraphTests
    {
        private static ThreadSnapshot T(string name, string owner) => new ThreadSnapshot { Name = name, LockOwnerName = owner, State = "BLOCKED" };

        [Test]
        public void FindsThreeWayCycleAndTails()
        {
            var graph = new DeadlockGraph(new[] { T("a", "b"), T("b", "c"), T("c", "a"), T("d", "a"), T("e", null) });
            Assert.That(graph.Cycle, Is.EquivalentTo(new[] { "a", "b", "c" }));
            Assert.That(graph.Waiting, Is.EquivalentTo(new[] { "d" }));
        }

        [Test]
        public void NoCycleForSimpleWaiting()
        {
            var graph = new DeadlockGraph(new[] { T("a", "b"), T("b", null) });
            Assert.That(graph.HasCycle, Is.False);
        }
    }
}
