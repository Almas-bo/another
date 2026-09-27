using System;
using System.Collections.Generic;

namespace SubroutineCity.Core.Editing
{
    /// <summary>
    /// Автодополнение без языкового сервера: ключевые слова, разрешённый песочницей API, идентификаторы
    /// из кода игрока и из контракта уровня. Ранжирование: точное совпадение регистра префикса, затем частота в коде.
    /// </summary>
    public sealed class CompletionEngine
    {
        /// <summary>Типы и члены, доступные в песочнице (белый список SandboxPolicy на сервере).</summary>
        private static readonly string[] SandboxApi =
        {
            "String", "StringBuilder", "Math", "Integer", "Long", "Double", "Boolean", "Character", "Object",
            "Objects", "Arrays", "Collections", "List", "ArrayList", "LinkedList", "Map", "HashMap", "LinkedHashMap",
            "TreeMap", "Set", "HashSet", "LinkedHashSet", "TreeSet", "Deque", "ArrayDeque", "Queue", "PriorityQueue",
            "Iterator", "Optional", "OptionalInt", "OptionalLong", "OptionalDouble", "Comparator", "Stream", "IntStream",
            "Collectors", "Function", "Supplier", "Consumer", "Predicate", "BiFunction", "Runnable", "AutoCloseable",
            "Thread", "ExecutorService", "Executors", "Future", "CompletableFuture", "CountDownLatch", "Semaphore",
            "ReentrantLock", "ReentrantReadWriteLock", "Lock", "Condition", "ConcurrentHashMap", "CopyOnWriteArrayList",
            "AtomicInteger", "AtomicLong", "AtomicLongArray", "AtomicReference", "LongAdder", "TimeUnit",
            "IllegalArgumentException", "IllegalStateException", "NullPointerException", "UnsupportedOperationException",
            "IndexOutOfBoundsException", "ArithmeticException", "RuntimeException", "Exception", "IOException",
            "UncheckedIOException", "InterruptedException", "Override", "FunctionalInterface",
            "System", "out", "println", "printf", "length", "size", "isEmpty", "get", "put", "getOrDefault",
            "computeIfAbsent", "merge", "remove", "add", "addAll", "contains", "containsKey", "equals", "hashCode",
            "toString", "compareTo", "requireNonNull", "incrementAndGet", "getAndIncrement", "addAndGet", "increment",
            "sum", "pollFirst", "pollLast", "addFirst", "addLast", "peekFirst", "offer", "poll", "peek", "stream",
            "forEach", "map", "filter", "collect", "toList", "reduce", "max", "min", "abs", "addSuppressed",
            "getSuppressed", "removeEldestEntry", "lock", "unlock", "tryLock", "await", "countDown", "submit",
            "shutdown", "awaitTermination", "valueOf", "parseInt", "MAX_VALUE", "MIN_VALUE", "Entry", "entrySet",
            "keySet", "values", "of", "empty", "isPresent", "orElse", "orElseThrow", "ifPresent", "synchronized"
        };

        private readonly HashSet<string> _static = new HashSet<string>(StringComparer.Ordinal);
        private readonly Dictionary<string, int> _documentWords = new Dictionary<string, int>(StringComparer.Ordinal);
        private int _indexedVersion = -1;

        public CompletionEngine(string contractText = null)
        {
            foreach (string keyword in JavaLexer.AllKeywords) _static.Add(keyword);
            foreach (string primitive in JavaLexer.AllPrimitives) _static.Add(primitive);
            foreach (string api in SandboxApi) _static.Add(api);
            if (!string.IsNullOrEmpty(contractText)) AddWords(contractText, null, _static);
        }

        /// <summary>До <paramref name="limit"/> вариантов для префикса перед курсором.</summary>
        public List<string> Suggest(CodeDocument document, int limit = 10)
        {
            string prefix = document.WordBeforeCaret();
            var result = new List<string>();
            if (prefix.Length == 0) return result;
            Reindex(document);

            var candidates = new Dictionary<string, int>(StringComparer.Ordinal);
            foreach (string word in _static)
                if (Matches(word, prefix)) candidates[word] = 0;
            foreach (var pair in _documentWords)
            {
                // слово, которое сейчас набирается, само по себе не подсказываем
                if (pair.Key == prefix && pair.Value <= 1) continue;
                if (Matches(pair.Key, prefix))
                    candidates[pair.Key] = candidates.TryGetValue(pair.Key, out int score) ? score + pair.Value : pair.Value;
            }
            result.AddRange(candidates.Keys);
            result.Sort((a, b) =>
            {
                int caseA = a.StartsWith(prefix, StringComparison.Ordinal) ? 0 : 1;
                int caseB = b.StartsWith(prefix, StringComparison.Ordinal) ? 0 : 1;
                if (caseA != caseB) return caseA.CompareTo(caseB);
                int frequency = candidates[b].CompareTo(candidates[a]);
                if (frequency != 0) return frequency;
                int length = a.Length.CompareTo(b.Length);
                return length != 0 ? length : string.CompareOrdinal(a, b);
            });
            result.Remove(prefix);
            if (result.Count > limit) result.RemoveRange(limit, result.Count - limit);
            return result;
        }

        private static bool Matches(string word, string prefix)
        {
            return word.Length > prefix.Length && word.StartsWith(prefix, StringComparison.OrdinalIgnoreCase);
        }

        private void Reindex(CodeDocument document)
        {
            if (_indexedVersion == document.Version) return;
            _documentWords.Clear();
            var tokens = new List<Token>();
            LexState state = LexState.Normal;
            for (int i = 0; i < document.LineCount; i++)
            {
                string line = document.Line(i);
                state = JavaLexer.TokenizeLine(line, state, tokens);
                foreach (var token in tokens)
                {
                    if (token.Kind != TokenKind.Identifier && token.Kind != TokenKind.TypeName && token.Kind != TokenKind.MethodCall)
                        continue;
                    string word = line.Substring(token.Start, token.Length);
                    if (word.Length < 2) continue;
                    _documentWords[word] = _documentWords.TryGetValue(word, out int count) ? count + 1 : 1;
                }
            }
            _indexedVersion = document.Version;
        }

        private static void AddWords(string text, Dictionary<string, int> counts, HashSet<string> set)
        {
            int i = 0;
            while (i < text.Length)
            {
                if (JavaLexer.IsIdentifierStart(text[i]))
                {
                    int start = i;
                    while (i < text.Length && JavaLexer.IsIdentifierPart(text[i])) i++;
                    string word = text.Substring(start, i - start);
                    if (word.Length >= 2) set.Add(word);
                }
                else
                {
                    i++;
                }
            }
        }
    }
}
