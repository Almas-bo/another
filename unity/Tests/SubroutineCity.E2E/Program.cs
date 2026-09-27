using System;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using SubroutineCity.Core.City;
using SubroutineCity.Core.Debugging;
using SubroutineCity.Core.Editing;
using SubroutineCity.Core.Localization;
using SubroutineCity.Core.Protocol;

/// <summary>
/// Сквозная проверка: код клиента Unity (SubroutineCity.Core) против живого сервера песочницы.
/// Эмулирует игровой цикл: кампания → набор кода с фоновой проверкой → запуск → отладка → deadlock.
/// Запуск: scripts/run-server.sh в одном терминале, затем
///   dotnet run --project unity/Tests/SubroutineCity.E2E [-- http://127.0.0.1:8787]
/// </summary>
class Program
{
    static HttpClient Http;
    static int failures;

    static string Get(string path) => Http.GetStringAsync(path).Result;
    static string Post(string path, string body)
    {
        var response = Http.PostAsync(path, new StringContent(body, Encoding.UTF8, "application/json")).Result;
        string text = response.Content.ReadAsStringAsync().Result;
        if (!response.IsSuccessStatusCode) throw new Exception(ModelParser.ParseError((int)response.StatusCode, text).ToString());
        return text;
    }
    static void Check(bool ok, string what) { Console.WriteLine((ok ? "ok    " : "FAIL  ") + what); if (!ok) failures++; }

    static int Main(string[] args)
    {
        string baseUrl = args.Length > 0 ? args[0] : "http://127.0.0.1:8787";
        Http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromMinutes(3) };
        string repo = FindRepository();
        Console.WriteLine("Сервер: " + baseUrl + ", репозиторий: " + repo);
        // 1. Кампания
        var levels = ModelParser.ParseLevels(Get(ApiRoutes.Levels));
        Check(levels.Count == 6, "кампания: 6 уровней, первый " + levels[0].Id);
        for (int i = 0; i < levels.Count; i++)
        {
            var plan = CityLayout.PlanDistrict(i, levels.Count, levels[i].Tests.Count, levels[i].District);
            Check(plan.Buildings.Count == levels[i].Tests.Count, "район " + levels[i].Id + ": зданий " + plan.Buildings.Count);
        }
        var level = ModelParser.ParseLevel(Get(ApiRoutes.Level("powergrid-01")));
        Check(level.StarterCode.StartsWith("package city.player;"), "уровень загружен, стартовый код без решения");

        // 2. Игрок печатает в редакторе (CodeDocument), фоновая проверка находит ошибку
        var doc = new CodeDocument(level.StarterCode);
        doc.MoveDocumentEnd(false);
        foreach (char c in "public final class PowerGrid {") doc.Type(c);
        doc.NewLine();
        foreach (char c in "public static long totalLoad(int[] loads) {") doc.Type(c);
        doc.NewLine();
        foreach (char c in "return tota") doc.Type(c);
        var check = ModelParser.ParseRunResponse(Post(ApiRoutes.Check(level.Id), ApiRoutes.CodeBody(doc.Text)));
        var error = check.Diagnostics.FirstOrDefault(d => d.Kind == DiagnosticKind.ERROR);
        Check(check.Status == ExecutionStatus.COMPILATION_ERROR && error != null, "проверка при наборе: " + Ru.ExplainDiagnostic(error) + " (строка " + error?.Line + ")");
        var from = doc.PositionOf((int)error.StartPosition);
        Check(from.Line + 1 == error.Line, "позиция javac совпадает со строкой документа редактора");

        // 3. Полный запуск эталонного и ошибочного решений
        foreach (var (file, expected) in new[] { ("PowerGrid.java", ExecutionStatus.SUCCESS), ("PowerGridBuggy.java", ExecutionStatus.TESTS_FAILED) })
        {
            string code = File.ReadAllText(Path.Combine(repo, "examples/powergrid", file));
            var result = ModelParser.ParseRunResponse(Post(ApiRoutes.Run(level.Id), ApiRoutes.CodeBody(code)));
            Check(result.Status == expected, file + " → " + Ru.Title(result.Status) + " " + result.PassedCount + "/" + result.Tests.Count);
            foreach (var t in result.Tests.Where(t => !t.Passed))
                Console.WriteLine("        " + Ru.Label(t.Status) + " " + t.Title + " · тепло " + Palette.Heat(t.Metrics.AllocatedBytes, t.Status).ToString("0.00") + " · режим " + Palette.ModeFor(t.Status));
        }

        // 4. Отладчик: запись трассы и шаги по ней
        string buggy = File.ReadAllText(Path.Combine(repo, "examples/powergrid/PowerGridBuggy.java"));
        var debug = ModelParser.ParseDebugResponse(Post(ApiRoutes.Debug(level.Id), ApiRoutes.DebugBody(buggy, "int-overflow")));
        var nav = new TraceNavigator(debug.Trace, new[] { 17 });
        Check(nav.Count > 0, "трасса int-overflow: шагов " + nav.Count);
        nav.Continue();
        var step = nav.Current;
        Check(step.Line == 17, "«До точки» остановился на строке 17: " + string.Join(", ", step.Locals.Select(v => v.Name + "=" + v.Value)));
        nav.StepOver();
        var total = nav.Current.Locals.FirstOrDefault(v => v.Name == "total");
        Check(total != null && total.Value == "3", "после шага total = " + total?.Value + " — переполнение int видно в переменных");

        // 5. Deadlock: граф ожидания для визуализатора
        string deadlock = File.ReadAllText(Path.Combine(repo, "examples/energy/EnergyBankDeadlock.java"));
        var dl = ModelParser.ParseRunResponse(Post(ApiRoutes.Run("energy-01"), ApiRoutes.CodeBody(deadlock)));
        var locked = dl.Tests.First(t => t.Status == TestStatus.DEADLOCK);
        var graph = new DeadlockGraph(locked.Threads);
        Check(dl.Status == ExecutionStatus.DEADLOCK && graph.HasCycle, "deadlock: цикл " + string.Join(" → ", graph.Cycle) + ", ждут: " + graph.Waiting.Count);

        Console.WriteLine(failures == 0 ? "\nСКВОЗНАЯ ПРОВЕРКА ПРОЙДЕНА" : "\nОШИБОК: " + failures);
        return failures == 0 ? 0 : 1;
    }

    /// <summary>Корень репозитория: ближайший родительский каталог с examples/.</summary>
    static string FindRepository()
    {
        var dir = new DirectoryInfo(Directory.GetCurrentDirectory());
        while (dir != null && !Directory.Exists(Path.Combine(dir.FullName, "examples", "powergrid"))) dir = dir.Parent;
        if (dir == null) throw new InvalidOperationException("Запускайте из репозитория: не найден каталог examples/");
        return dir.FullName;
    }
}
