using System.Collections.Generic;
using System.Globalization;
using SubroutineCity.Core.Protocol;

namespace SubroutineCity.Core.Localization
{
    /// <summary>Таблица локализации ru-RU. Сервер присылает коды — текст для игрока собирается здесь.</summary>
    public static class Ru
    {
        public static string Title(ExecutionStatus status)
        {
            switch (status)
            {
                case ExecutionStatus.SUCCESS: return "Все тесты пройдены";
                case ExecutionStatus.COMPILED: return "Код компилируется";
                case ExecutionStatus.TESTS_FAILED: return "Часть тестов не пройдена";
                case ExecutionStatus.COMPILATION_ERROR: return "Ошибка компиляции";
                case ExecutionStatus.POLICY_VIOLATION: return "Нарушение правил песочницы";
                case ExecutionStatus.CONTRACT_VIOLATION: return "Нарушен контракт уровня";
                case ExecutionStatus.TIMEOUT: return "Превышен лимит времени";
                case ExecutionStatus.DEADLOCK: return "Взаимная блокировка (deadlock)";
                case ExecutionStatus.MEMORY_LIMIT_EXCEEDED: return "Исчерпана память";
                case ExecutionStatus.THREAD_LIMIT_EXCEEDED: return "Слишком много потоков";
                case ExecutionStatus.REJECTED: return "Запрос отклонён";
                case ExecutionStatus.SANDBOX_FAILURE: return "Сбой песочницы";
                default: return "Неизвестный статус";
            }
        }

        /// <summary>Что произошло с городом — одна строка «сюжетного» пояснения к статусу.</summary>
        public static string CityEvent(ExecutionStatus status)
        {
            switch (status)
            {
                case ExecutionStatus.SUCCESS: return "Район работает штатно. Подпрограмма принята в эксплуатацию.";
                case ExecutionStatus.COMPILED: return "Чертёж принят: код компилируется и соответствует контракту.";
                case ExecutionStatus.TESTS_FAILED: return "В районе аварии: часть систем работает неверно.";
                case ExecutionStatus.COMPILATION_ERROR: return "Чертёж не прошёл проверку — район обесточен.";
                case ExecutionStatus.POLICY_VIOLATION: return "Сработал городской файрвол: код пытался выйти за пределы песочницы.";
                case ExecutionStatus.CONTRACT_VIOLATION: return "Подпрограмма не подходит к разъёмам района: контракт не соблюдён.";
                case ExecutionStatus.TIMEOUT: return "Движение в районе замерло: код не завершился вовремя.";
                case ExecutionStatus.DEADLOCK: return "Потоки заблокировали друг друга — район парализован.";
                case ExecutionStatus.MEMORY_LIMIT_EXCEEDED: return "Хранилища переполнены: память района исчерпана.";
                case ExecutionStatus.THREAD_LIMIT_EXCEEDED: return "Шторм потоков: диспетчер района отключил подпрограмму.";
                case ExecutionStatus.REJECTED: return "Чертёж отклонён до запуска.";
                case ExecutionStatus.SANDBOX_FAILURE: return "Сбой городской вычислительной сети (не ваша ошибка).";
                default: return "";
            }
        }

        public static string Label(TestStatus status)
        {
            switch (status)
            {
                case TestStatus.PASSED: return "ПРОЙДЕН";
                case TestStatus.FAILED: return "ПРОВАЛЕН";
                case TestStatus.ERROR: return "ИСКЛЮЧЕНИЕ";
                case TestStatus.TIMEOUT: return "ТАЙМАУТ";
                case TestStatus.DEADLOCK: return "DEADLOCK";
                case TestStatus.MEMORY_LIMIT_EXCEEDED: return "ПАМЯТЬ";
                case TestStatus.THREAD_LIMIT_EXCEEDED: return "ПОТОКИ";
                case TestStatus.SKIPPED: return "ПРОПУЩЕН";
                case TestStatus.SANDBOX_CRASH: return "СБОЙ";
                default: return "—";
            }
        }

        public static string Rule(PolicyRule rule)
        {
            switch (rule)
            {
                case PolicyRule.FORBIDDEN_TYPE: return "Запрещённый тип";
                case PolicyRule.FORBIDDEN_MEMBER: return "Запрещённый метод или поле";
                case PolicyRule.NATIVE_METHOD: return "native-метод";
                case PolicyRule.WRONG_PACKAGE: return "Класс вне пакета игрока";
                case PolicyRule.MALFORMED_CLASS: return "Повреждённый класс";
                default: return "Нарушение";
            }
        }

        public static string ThreadState(string state)
        {
            switch (state)
            {
                case "RUNNABLE": return "выполняется";
                case "BLOCKED": return "ждёт монитор";
                case "WAITING": return "ждёт";
                case "TIMED_WAITING": return "ждёт (с таймаутом)";
                case "NEW": return "не запущен";
                case "TERMINATED": return "завершён";
                default: return state ?? "";
            }
        }

        public static string Difficulty(int level)
        {
            if (level < 1) level = 1;
            if (level > 5) level = 5;
            return new string('◆', level) + new string('◇', 5 - level);
        }

        public static string Bytes(long value)
        {
            if (value < 1024) return value + " Б";
            if (value < 1024 * 1024) return (value / 1024.0).ToString("0.0", CultureInfo.InvariantCulture) + " КБ";
            return (value / (1024.0 * 1024.0)).ToString("0.0", CultureInfo.InvariantCulture) + " МБ";
        }

        public static string Millis(long nanos)
        {
            double ms = nanos / 1_000_000.0;
            return (ms < 10 ? ms.ToString("0.00", CultureInfo.InvariantCulture) : ms.ToString("0", CultureInfo.InvariantCulture)) + " мс";
        }

        // ------------------------------------------------------------------ UI

        public static class Ui
        {
            public const string GameTitle = "SUBROUTINE CITY";
            public const string Tagline = "Город, который работает на вашем коде";
            public const string Connecting = "Подключение к серверу песочницы…";
            public const string StartingServer = "Запуск локального сервера песочницы…";
            public const string ServerUnavailable = "Сервер песочницы недоступен";
            public const string ServerHint = "Запустите scripts/run-server (или run-server.bat) и нажмите «Повторить».";
            public const string Retry = "Повторить";
            public const string Campaign = "Кампания";
            public const string Play = "Играть";
            public const string Completed = "пройден";
            public const string Back = "◀ Город";
            public const string Run = "▶ Запустить  F5";
            public const string Debug = "Отладка  F6";
            public const string Running = "Исполнение в песочнице…";
            public const string Checking = "проверка…";
            public const string TabCode = "Код";
            public const string TabBrief = "Задание";
            public const string TabContract = "Контракт";
            public const string TabResults = "Результаты";
            public const string TabInspector = "Инспектор";
            public const string TabDebugger = "Отладчик";
            public const string TabOutput = "Вывод";
            public const string Requirements = "Требования";
            public const string Goals = "Чему учит уровень";
            public const string NoResults = "Запустите код (F5), чтобы увидеть, как город отреагирует.";
            public const string SelectBuilding = "Выберите здание в городе или тест в списке результатов.";
            public const string Expected = "Ожидалось";
            public const string Actual = "Получено";
            public const string Exception = "Исключение";
            public const string CausedBy = "Причина";
            public const string Suppressed = "Подавлено (suppressed)";
            public const string Threads = "Потоки";
            public const string LeakedThreads = "Потоки, оставшиеся после теста";
            public const string YourCode = "ваш код";
            public const string Metrics = "Метрики";
            public const string DebugPickTest = "Тест для отладки:";
            public const string DebugStart = "Записать трассу";
            public const string DebugRecording = "Запись трассы выполнения…";
            public const string DebugEmpty = "Трасса пуста.";
            public const string DebugTruncated = "Трасса обрезана по лимиту шагов — дальше программа выполнялась без записи.";
            public const string StepBack = "◀ Назад";
            public const string StepInto = "Шаг внутрь";
            public const string StepOver = "Шаг с обходом";
            public const string StepOut = "Выйти";
            public const string Continue = "До точки ▶";
            public const string ReverseContinue = "◀ До точки";
            public const string Locals = "Переменные";
            public const string CallStack = "Стек вызовов";
            public const string Changed = "изм.";
            public const string Settings = "Настройки";
            public const string ServerAddress = "Адрес сервера";
            public const string Victory = "УРОВЕНЬ ПРОЙДЕН";
            public const string Hotkeys = "F5 — запуск · F6 — отладка · Ctrl+Space — подсказки · Ctrl+/ — комментарий · Ctrl+Z/Y — отмена/повтор";
            public const string Output = "Вывод программы";
            public const string NoOutput = "Программа ничего не выводила.";
        }

        // ------------------------------------------------------------------ javac

        private static readonly Dictionary<string, string> Diagnostics = new Dictionary<string, string>
        {
            { "compiler.err.expected", "Пропущен символ" },
            { "compiler.err.expected2", "Пропущен один из символов" },
            { "compiler.err.expected3", "Пропущен один из символов" },
            { "compiler.err.expected4", "Пропущен один из символов" },
            { "compiler.err.premature.eof", "Файл закончился раньше времени — вероятно, не хватает закрывающей '}'" },
            { "compiler.err.cant.resolve", "Имя не найдено: опечатка, не объявлено или не импортировано" },
            { "compiler.err.cant.resolve.location", "Имя не найдено: опечатка, не объявлено или не импортировано" },
            { "compiler.err.cant.resolve.location.args", "Метод не найден: проверьте имя и типы аргументов" },
            { "compiler.err.cant.resolve.args", "Метод не найден: проверьте имя и типы аргументов" },
            { "compiler.err.prob.found.req", "Несовместимые типы" },
            { "compiler.err.missing.ret.stmt", "Не на всех путях выполнения есть return" },
            { "compiler.err.var.might.not.have.been.initialized", "Переменная может быть не инициализирована" },
            { "compiler.err.already.defined", "Такое имя уже объявлено в этой области видимости" },
            { "compiler.err.unreported.exception.need.to.catch.or.throw", "Проверяемое исключение не обработано: перехватите его или объявите в throws" },
            { "compiler.err.does.not.override.abstract", "Класс не реализует обязательный метод интерфейса" },
            { "compiler.err.class.public.should.be.in.file", "Имя public-класса должно совпадать с именем из контракта уровня" },
            { "compiler.err.illegal.start.of.expr", "Недопустимое начало выражения" },
            { "compiler.err.illegal.start.of.type", "Недопустимое начало типа" },
            { "compiler.err.not.stmt", "Это выражение не является оператором" },
            { "compiler.err.unreachable.stmt", "Недостижимый код" },
            { "compiler.err.non-static.cant.be.ref", "Обращение к нестатическому члену из статического контекста" },
            { "compiler.err.cant.apply.symbol", "Метод вызван с неподходящими аргументами" },
            { "compiler.err.cant.apply.symbols", "Ни одна перегрузка метода не подходит к аргументам" },
            { "compiler.err.missing.meth.body.or.decl.abstract", "У метода нет тела" },
            { "compiler.err.report.access", "Член недоступен (private или protected)" },
            { "compiler.err.cant.deref", "У примитивного типа нет методов и полей" },
            { "compiler.err.operator.cant.be.applied", "Оператор неприменим к этому типу" },
            { "compiler.err.operator.cant.be.applied.1", "Оператор неприменим к этим типам" },
            { "compiler.err.incomparable.types", "Эти типы нельзя сравнивать" },
            { "compiler.err.override.weaker.access", "При переопределении нельзя сужать доступ (нужен public)" },
            { "compiler.err.override.meth", "Метод нельзя переопределить" },
            { "compiler.err.method.does.not.override.superclass", "@Override стоит, но метод ничего не переопределяет" },
            { "compiler.err.invalid.meth.decl.ret.type.req", "Не указан тип результата метода" },
            { "compiler.err.unclosed.str.lit", "Строка не закрыта кавычкой" },
            { "compiler.err.unclosed.comment", "Комментарий не закрыт" },
            { "compiler.err.unclosed.char.lit", "Символьный литерал не закрыт" },
            { "compiler.err.else.without.if", "else без if" },
            { "compiler.err.break.outside.switch.loop", "break вне цикла или switch" },
            { "compiler.err.duplicate.class", "Класс объявлен дважды" },
            { "compiler.err.abstract.cant.be.instantiated", "Нельзя создать экземпляр абстрактного класса или интерфейса" },
            { "compiler.err.cant.inherit.from.final", "Нельзя наследоваться от final-класса" },
            { "compiler.err.not.def.public.cant.access", "Тип недоступен извне своего пакета" },
            { "compiler.err.doesnt.exist", "Такого пакета нет" },
            { "compiler.err.cant.assign.val.to.var", "Нельзя присвоить значение final-переменной" },
            { "compiler.err.ref.ambiguous", "Неоднозначная ссылка" },
            { "compiler.err.int.number.too.large", "Число слишком велико для int — добавьте суффикс L" },
            { "compiler.err.illegal.char", "Недопустимый символ в коде" },
            { "compiler.err.class.not.allowed", "Здесь нельзя объявлять класс" },
            { "compiler.err.not.encl.class", "Нет охватывающего экземпляра" },
            { "compiler.err.non-static.cant.be.ref.1", "Обращение к нестатическому члену из статического контекста" },
            { "sandbox.javac.failed", "Компилятор не смог обработать код" },
        };

        /// <summary>Русское пояснение к диагностике javac по её коду. Исходный текст javac остаётся доступен отдельно.</summary>
        public static string ExplainDiagnostic(CompilationDiagnostic diagnostic)
        {
            if (diagnostic == null) return "";
            if (Diagnostics.TryGetValue(diagnostic.Code ?? "", out string text))
            {
                string symbol = ExtractSymbol(diagnostic.Message);
                if (diagnostic.Code.StartsWith("compiler.err.expected", System.StringComparison.Ordinal))
                {
                    string token = ExtractQuoted(diagnostic.Message);
                    return token == null ? text : "Ожидается " + token;
                }
                return symbol == null ? text : text + ": " + symbol;
            }
            if (diagnostic.Code != null && diagnostic.Code.StartsWith("compiler.warn", System.StringComparison.Ordinal))
                return "Предупреждение компилятора";
            if (diagnostic.Code != null && diagnostic.Code.StartsWith("compiler.note", System.StringComparison.Ordinal))
                return "Заметка компилятора";
            return "Ошибка компиляции";
        }

        /// <summary>«symbol:   variable total» → «переменная total».</summary>
        private static string ExtractSymbol(string message)
        {
            if (string.IsNullOrEmpty(message)) return null;
            foreach (string rawLine in message.Split('\n'))
            {
                string line = rawLine.Trim();
                if (!line.StartsWith("symbol:", System.StringComparison.Ordinal)) continue;
                string rest = line.Substring("symbol:".Length).Trim();
                int space = rest.IndexOf(' ');
                if (space < 0) return rest;
                string kind = rest.Substring(0, space);
                string name = rest.Substring(space + 1).Trim();
                switch (kind)
                {
                    case "variable": return "переменная " + name;
                    case "method": return "метод " + name;
                    case "class": return "класс " + name;
                    default: return rest;
                }
            }
            return null;
        }

        private static string ExtractQuoted(string message)
        {
            if (string.IsNullOrEmpty(message)) return null;
            int start = message.IndexOf('\'');
            if (start < 0) return null;
            int end = message.IndexOf('\'', start + 1);
            if (end <= start) return null;
            // «';' expected» или «'(' or '[' expected»
            string head = message.Substring(start).Replace(" expected", "").Replace(" or ", " или ");
            int newline = head.IndexOf('\n');
            return newline < 0 ? head.Trim() : head.Substring(0, newline).Trim();
        }
    }
}
