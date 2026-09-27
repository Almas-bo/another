using System;
using System.Collections.Generic;
using SubroutineCity.Core.Protocol;

namespace SubroutineCity.Core.Debugging
{
    /// <summary>
    /// Навигация по записанной трассе («отладчик с машиной времени»). Все команды работают по записанным шагам,
    /// поэтому доступны и шаги назад. Step Over / Step Out сравнивают глубину стека в пределах одного потока.
    /// </summary>
    public sealed class TraceNavigator
    {
        private readonly DebugTrace _trace;
        private readonly HashSet<int> _breakpoints;

        public TraceNavigator(DebugTrace trace, IEnumerable<int> breakpointLines = null)
        {
            _trace = trace ?? throw new ArgumentNullException(nameof(trace));
            _breakpoints = new HashSet<int>(breakpointLines ?? Array.Empty<int>());
            Index = _trace.Steps.Count > 0 ? 0 : -1;
        }

        public DebugTrace Trace => _trace;

        /// <summary>Индекс текущего шага или -1, если трасса пуста.</summary>
        public int Index { get; private set; }

        public int Count => _trace.Steps.Count;

        public TraceStep Current => Index >= 0 ? _trace.Steps[Index] : null;

        public bool AtStart => Index <= 0;

        public bool AtEnd => Index < 0 || Index >= _trace.Steps.Count - 1;

        public void SetBreakpoints(IEnumerable<int> lines)
        {
            _breakpoints.Clear();
            foreach (int line in lines) _breakpoints.Add(line);
        }

        public void JumpTo(int index)
        {
            if (Count == 0) return;
            Index = Math.Max(0, Math.Min(Count - 1, index));
        }

        /// <summary>Следующий шаг в любом месте (заходит внутрь вызовов).</summary>
        public bool StepInto() => Move(Index + 1);

        /// <summary>Предыдущий шаг.</summary>
        public bool StepBack() => Move(Index - 1);

        /// <summary>Следующая строка в том же или внешнем кадре того же потока (вызовы проходятся целиком).</summary>
        public bool StepOver()
        {
            TraceStep current = Current;
            if (current == null) return false;
            return Move(Find(Index + 1, 1, s => s.Thread == current.Thread && s.Depth <= current.Depth));
        }

        /// <summary>Предыдущая строка того же или внешнего кадра того же потока.</summary>
        public bool StepBackOver()
        {
            TraceStep current = Current;
            if (current == null) return false;
            return Move(Find(Index - 1, -1, s => s.Thread == current.Thread && s.Depth <= current.Depth));
        }

        /// <summary>Выход из текущего метода: следующий шаг внешнего кадра того же потока.</summary>
        public bool StepOut()
        {
            TraceStep current = Current;
            if (current == null) return false;
            return Move(Find(Index + 1, 1, s => s.Thread == current.Thread && s.Depth < current.Depth));
        }

        /// <summary>До следующей точки останова (или до конца трассы).</summary>
        public bool Continue()
        {
            if (Count == 0) return false;
            int target = Find(Index + 1, 1, s => _breakpoints.Contains(s.Line));
            return Move(target >= 0 ? target : Count - 1);
        }

        /// <summary>Назад до предыдущей точки останова (или до начала трассы).</summary>
        public bool ReverseContinue()
        {
            if (Count == 0) return false;
            int target = Find(Index - 1, -1, s => _breakpoints.Contains(s.Line));
            return Move(target >= 0 ? target : 0);
        }

        /// <summary>
        /// Имена переменных, чьё значение изменилось по сравнению с предыдущим шагом того же кадра (поток + глубина).
        /// Новые переменные тоже считаются изменёнными.
        /// </summary>
        public HashSet<string> ChangedVariables()
        {
            var changed = new HashSet<string>(StringComparer.Ordinal);
            TraceStep current = Current;
            if (current == null) return changed;
            int previousIndex = Find(Index - 1, -1, s => s.Thread == current.Thread && s.Depth == current.Depth
                                                        && s.Method == current.Method && s.ClassName == current.ClassName);
            if (previousIndex < 0) return changed;
            var before = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var variable in _trace.Steps[previousIndex].Locals) before[variable.Name] = variable.Value;
            foreach (var variable in current.Locals)
            {
                if (!before.TryGetValue(variable.Name, out string old) || old != variable.Value) changed.Add(variable.Name);
            }
            return changed;
        }

        /// <summary>Сколько раз строка исполнялась в записанной трассе (тепловая карта строк в редакторе).</summary>
        public Dictionary<int, int> LineHits()
        {
            var hits = new Dictionary<int, int>();
            foreach (var step in _trace.Steps) hits[step.Line] = hits.TryGetValue(step.Line, out int n) ? n + 1 : 1;
            return hits;
        }

        private int Find(int from, int direction, Func<TraceStep, bool> predicate)
        {
            for (int i = from; i >= 0 && i < _trace.Steps.Count; i += direction)
                if (predicate(_trace.Steps[i])) return i;
            return -1;
        }

        private bool Move(int target)
        {
            if (target < 0 || target >= Count || target == Index) return false;
            Index = target;
            return true;
        }
    }
}
