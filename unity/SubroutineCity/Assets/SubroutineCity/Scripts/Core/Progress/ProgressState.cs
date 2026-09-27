using System.Collections.Generic;
using SubroutineCity.Core.Json;
using SubroutineCity.Core.Protocol;

namespace SubroutineCity.Core.Progress
{
    /// <summary>Прогресс по одному уровню.</summary>
    public sealed class LevelProgress
    {
        public string LevelId;
        public bool Completed;
        public int Attempts;
        public string Draft;
        public string BestStatus;
        public int BestPassed;
        public long BestCpuNanos;
        public long BestAllocatedBytes;
    }

    /// <summary>Прогресс кампании. Хранится строкой JSON (в Unity — PlayerPrefs).</summary>
    public sealed class ProgressState
    {
        private readonly Dictionary<string, LevelProgress> _levels = new Dictionary<string, LevelProgress>();

        public string ServerUrl = "http://127.0.0.1:8787";

        public LevelProgress Level(string levelId)
        {
            if (!_levels.TryGetValue(levelId, out LevelProgress progress))
            {
                progress = new LevelProgress { LevelId = levelId };
                _levels[levelId] = progress;
            }
            return progress;
        }

        public bool IsCompleted(string levelId) => _levels.TryGetValue(levelId, out var p) && p.Completed;

        public int CompletedCount
        {
            get
            {
                int count = 0;
                foreach (var level in _levels.Values)
                    if (level.Completed) count++;
                return count;
            }
        }

        /// <summary>Учитывает результат полного прогона; возвращает true, если уровень пройден впервые.</summary>
        public bool Record(string levelId, ExecutionResult result)
        {
            LevelProgress progress = Level(levelId);
            progress.Attempts++;
            int passed = result.PassedCount;
            if (passed >= progress.BestPassed)
            {
                progress.BestPassed = passed;
                progress.BestStatus = result.Status.ToString();
            }
            if (result.Status != ExecutionStatus.SUCCESS) return false;
            long cpu = result.Metrics.TotalCpuNanos;
            long allocated = result.Metrics.TotalAllocatedBytes;
            if (progress.BestCpuNanos == 0 || cpu < progress.BestCpuNanos) progress.BestCpuNanos = cpu;
            if (progress.BestAllocatedBytes == 0 || allocated < progress.BestAllocatedBytes) progress.BestAllocatedBytes = allocated;
            bool first = !progress.Completed;
            progress.Completed = true;
            return first;
        }

        public string Serialize()
        {
            var levels = new List<object>();
            foreach (var p in _levels.Values)
            {
                levels.Add(new Dictionary<string, object>
                {
                    { "levelId", p.LevelId },
                    { "completed", p.Completed },
                    { "attempts", p.Attempts },
                    { "draft", p.Draft },
                    { "bestStatus", p.BestStatus },
                    { "bestPassed", p.BestPassed },
                    { "bestCpuNanos", p.BestCpuNanos },
                    { "bestAllocatedBytes", p.BestAllocatedBytes }
                });
            }
            return JsonValue.Write(new Dictionary<string, object>
            {
                { "version", 1 },
                { "serverUrl", ServerUrl },
                { "levels", levels }
            });
        }

        /// <summary>Повреждённое сохранение не должно ломать игру: вернётся пустой прогресс.</summary>
        public static ProgressState Deserialize(string json)
        {
            var state = new ProgressState();
            if (string.IsNullOrEmpty(json)) return state;
            try
            {
                var root = JsonValue.ParseObject(json);
                state.ServerUrl = root.String("serverUrl", state.ServerUrl);
                foreach (var level in root.Objects("levels"))
                {
                    string id = level.String("levelId");
                    if (string.IsNullOrEmpty(id)) continue;
                    var p = state.Level(id);
                    p.Completed = level.Bool("completed");
                    p.Attempts = level.Int("attempts");
                    p.Draft = level.String("draft");
                    p.BestStatus = level.String("bestStatus");
                    p.BestPassed = level.Int("bestPassed");
                    p.BestCpuNanos = level.Long("bestCpuNanos");
                    p.BestAllocatedBytes = level.Long("bestAllocatedBytes");
                }
            }
            catch (JsonException)
            {
                return new ProgressState();
            }
            return state;
        }
    }
}
