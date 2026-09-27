using System.Collections.Generic;
using SubroutineCity.Core.Protocol;

namespace SubroutineCity.Core.City
{
    /// <summary>
    /// Граф ожидания «поток → владелец нужного ему монитора». JVM помечает как deadlocked и потоки,
    /// которые лишь ждут участника цикла; для визуализации нужен сам цикл и «хвосты», ведущие в него.
    /// </summary>
    public sealed class DeadlockGraph
    {
        public DeadlockGraph(IList<ThreadSnapshot> threads)
        {
            var byName = new Dictionary<string, ThreadSnapshot>();
            foreach (var thread in threads) byName[thread.Name] = thread;

            foreach (var thread in threads)
            {
                if (thread.LockOwnerName != null && byName.ContainsKey(thread.LockOwnerName))
                    Edges.Add(new KeyValuePair<string, string>(thread.Name, thread.LockOwnerName));
            }

            // поиск цикла: идём по единственному исходящему ребру (поток ждёт ровно один монитор)
            var next = new Dictionary<string, string>();
            foreach (var edge in Edges) next[edge.Key] = edge.Value;
            foreach (var start in next.Keys)
            {
                var path = new List<string>();
                var index = new Dictionary<string, int>();
                string current = start;
                while (current != null && !index.ContainsKey(current))
                {
                    index[current] = path.Count;
                    path.Add(current);
                    current = next.TryGetValue(current, out string owner) ? owner : null;
                }
                if (current != null)
                {
                    Cycle.AddRange(path.GetRange(index[current], path.Count - index[current]));
                    break;
                }
            }
            foreach (var edge in Edges)
                if (!Cycle.Contains(edge.Key)) Waiting.Add(edge.Key);
        }

        /// <summary>Рёбра ожидания: (ожидающий поток, владелец монитора).</summary>
        public List<KeyValuePair<string, string>> Edges { get; } = new List<KeyValuePair<string, string>>();

        /// <summary>Потоки цикла взаимной блокировки в порядке ожидания; пусто, если цикла нет.</summary>
        public List<string> Cycle { get; } = new List<string>();

        /// <summary>Потоки, которые ждут, но в цикл не входят.</summary>
        public List<string> Waiting { get; } = new List<string>();

        public bool HasCycle => Cycle.Count > 0;

        public bool InCycle(string thread) => Cycle.Contains(thread);
    }
}
