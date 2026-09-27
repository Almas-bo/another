using System;
using System.Collections.Generic;
using SubroutineCity.City;
using SubroutineCity.Core.City;
using SubroutineCity.Core.Debugging;
using SubroutineCity.Core.Localization;
using SubroutineCity.Core.Protocol;
using UnityEngine;

namespace SubroutineCity.UI
{
    /// <summary>
    /// Интерфейс игры на IMGUI (без ассетов и пакетов). Виртуальное разрешение — высота 1080, ширина по экрану.
    /// Порядок кадра: горячие клавиши → экран → подписи над городом → уведомления → ввод камеры (что не забрал UI).
    /// Все действия, меняющие состояние (кнопки, вкладки, клавиши), откладываются до конца прохода OnGUI:
    /// иначе структура GUILayout в проходе события разойдётся с проходом Layout («Mismatched LayoutGroup»).
    /// </summary>
    public sealed class GameUI
    {
        private const float ReferenceHeight = 1080f;
        private const float HeaderHeight = 52f;
        private const float MenuWidth = 560f;

        private readonly GameRoot _root;
        private readonly Theme _theme = new Theme();
        private readonly List<Rect> _blockers = new List<Rect>();
        private readonly List<Action> _deferred = new List<Action>();
        private float _scale = 1f;
        private float _width = 1920f;
        private float _height = ReferenceHeight;
        private Vector2 _menuScroll;
        private Vector2 _textScroll;
        private Vector2 _resultsScroll;
        private Vector2 _inspectorScroll;
        private Vector2 _debugScroll;
        private Vector2 _outputScroll;
        private string _serverUrlField;
        private string _repositoryField;
        private bool _settingsOpen;
        private Vector2 _pressPosition;
        private bool _pressed;
        private bool _orbiting;

        public GameUI(GameRoot root)
        {
            _root = root;
        }

        public void Draw()
        {
            _theme.EnsureBuilt();
            _scale = Mathf.Clamp(Screen.height / ReferenceHeight, 0.5f, 3f);
            _width = Screen.width / _scale;
            _height = Screen.height / _scale;
            GUI.matrix = Matrix4x4.Scale(new Vector3(_scale, _scale, 1f));
            _blockers.Clear();

            HandleHotkeys();
            switch (_root.State)
            {
                case GameState.Connecting: DrawConnecting(); break;
                case GameState.NeedsJdk: DrawNeedsJdk(); break;
                case GameState.Offline: DrawOffline(); break;
                case GameState.Menu: DrawMenu(); break;
                case GameState.Level: DrawLevel(); break;
            }
            DrawWorldLabels();
            DrawToast();
            DrawVictory();
            HandleCameraInput();

            // Состояние меняется только здесь — после того как весь проход отрисован.
            if (_deferred.Count > 0)
            {
                var actions = new List<Action>(_deferred);
                _deferred.Clear();
                foreach (Action action in actions) action();
            }
        }

        private void Defer(Action action)
        {
            _deferred.Add(action);
        }

        // ================================================================== подключение

        private void DrawConnecting()
        {
            var panel = Centered(640f, 210f);
            Block(panel);
            GUI.Box(panel, GUIContent.none, _theme.Panel);
            GUILayout.BeginArea(Inset(panel, 24f));
            GUILayout.Label(Ru.Ui.GameTitle, _theme.Title);
            GUILayout.Label(Ru.Ui.Tagline, _theme.BodyDim);
            GUILayout.Space(18f);
            GUILayout.Label(_root.StatusMessage + Dots(), _theme.Body);
            float progress = _root.Pipeline != null && _root.Pipeline.Stage == Core.Launch.LaunchStage.DownloadingJdk ? _root.Pipeline.Progress : -1f;
            if (progress >= 0f)
            {
                Rect bar = GUILayoutUtility.GetRect(10f, 14f, GUILayout.ExpandWidth(true));
                _theme.FillRect(bar, new Color(0.13f, 0.88f, 1f, 0.15f));
                _theme.FillRect(new Rect(bar.x, bar.y, bar.width * progress, bar.height), new Color(0.13f, 0.88f, 1f, 0.9f));
                GUILayout.Label(Mathf.RoundToInt(progress * 100f) + " %", _theme.SmallDim);
            }
            GUILayout.EndArea();
        }

        private void DrawNeedsJdk()
        {
            var panel = Centered(760f, 430f);
            Block(panel);
            GUI.Box(panel, GUIContent.none, _theme.Panel);
            GUILayout.BeginArea(Inset(panel, 24f));
            GUILayout.Label(Ru.Ui.GameTitle, _theme.Title);
            GUILayout.Space(6f);
            GUILayout.Label("Для песочницы нужна Java 21 (JDK)", _theme.Heading);
            GUILayout.Label("Код игрока компилируется и исполняется настоящей JVM. Подходящий JDK на компьютере не найден "
                            + "(проверены JAVA_HOME, PATH и стандартные каталоги).", _theme.Body);
            GUILayout.Space(10f);
            if (GUILayout.Button("Установить Java 21 автоматически", _theme.ButtonPrimary, GUILayout.Height(46f)))
                Defer(_root.InstallJdk);
            GUILayout.Label("Eclipse Temurin 21 (OpenJDK) с официального сервера Adoptium, около 200 МБ. Контрольная сумма "
                            + "SHA-256 проверяется. Установка только для игры, в " + (_root.Pipeline?.ManagedJdkRoot ?? "каталог игры")
                            + " — системные настройки не меняются.", _theme.SmallDim);
            GUILayout.Space(10f);
            if (GUILayout.Button("Я установлю JDK 21 сам — проверить снова", _theme.Button, GUILayout.Height(36f)))
                Defer(() => _root.Reconnect(_root.Progress.ServerUrl, _root.Progress.RepositoryPath));
            GUILayout.EndArea();
        }

        private void DrawOffline()
        {
            var panel = Centered(760f, 560f);
            Block(panel);
            GUI.Box(panel, GUIContent.none, _theme.Panel);
            GUILayout.BeginArea(Inset(panel, 24f));
            GUILayout.Label(Ru.Ui.GameTitle, _theme.Title);
            GUILayout.Space(8f);
            GUILayout.Label("<color=#FF3B5C>" + Ru.Ui.ServerUnavailable + "</color>", _theme.Heading);
            if (!string.IsNullOrEmpty(_root.OfflineDetail)) GUILayout.Label(_root.OfflineDetail, _theme.Body);
            GUILayout.Label(Ru.Ui.ServerHint, _theme.BodyDim);
            GUILayout.Space(10f);
            DrawServerSettings();
            string log = _root.LaunchLog;
            if (!string.IsNullOrEmpty(log))
            {
                GUILayout.Space(8f);
                GUILayout.Label("Журнал сервера:", _theme.SmallDim);
                _textScroll = GUILayout.BeginScrollView(_textScroll, GUILayout.Height(140f));
                GUILayout.Label(Tail(log, 1600), _theme.MonoSmallPlain);
                GUILayout.EndScrollView();
            }
            GUILayout.EndArea();
        }

        private void DrawServerSettings()
        {
            if (_serverUrlField == null) _serverUrlField = _root.Progress.ServerUrl;
            if (_repositoryField == null) _repositoryField = _root.Progress.RepositoryPath;
            GUILayout.Label(Ru.Ui.ServerAddress, _theme.SmallDim);
            _serverUrlField = GUILayout.TextField(_serverUrlField, _theme.TextField);
            GUILayout.Label("Путь к репозиторию (для автозапуска сервера, необязательно)", _theme.SmallDim);
            _repositoryField = GUILayout.TextField(_repositoryField, _theme.TextField);
            GUILayout.Space(6f);
            if (GUILayout.Button(Ru.Ui.Retry, _theme.ButtonPrimary, GUILayout.Height(38f), GUILayout.Width(220f)))
                Defer(() => _root.Reconnect(_serverUrlField, _repositoryField));
        }

        // ================================================================== кампания

        private void DrawMenu()
        {
            _root.City.Rig.LeftInset = Mathf.Clamp01((MenuWidth + 24f) / _width);
            var panel = new Rect(16f, 16f, MenuWidth, _height - 32f);
            Block(panel);
            GUI.Box(panel, GUIContent.none, _theme.Panel);
            GUILayout.BeginArea(Inset(panel, 20f));
            GUILayout.Label(Ru.Ui.GameTitle, _theme.Title);
            GUILayout.Label(Ru.Ui.Tagline, _theme.BodyDim);
            GUILayout.Space(6f);
            GUILayout.Label("Пройдено уровней: <b>" + _root.Progress.CompletedCount + "</b> из " + _root.Levels.Count, _theme.Body);
            GUILayout.Space(10f);

            _menuScroll = GUILayout.BeginScrollView(_menuScroll, GUILayout.ExpandHeight(true));
            string chapter = null;
            foreach (LevelSummary level in _root.Levels)
            {
                if (level.Chapter != chapter)
                {
                    chapter = level.Chapter;
                    GUILayout.Space(8f);
                    GUILayout.Label(chapter, _theme.Subheading);
                }
                bool selected = _root.MenuSelection == level;
                bool completed = _root.Progress.IsCompleted(level.Id);
                string mark = completed ? "<color=#3DFFA0>✔</color>" : "<color=#3A5A80>○</color>";
                string label = mark + "  " + level.Order + ". " + level.Title + "   <color=#7C8FB0><size=12>" + Ru.Difficulty(level.Difficulty) + "</size></color>";
                if (GUILayout.Button(label, selected ? _theme.ListItemSelected : _theme.ListItem, GUILayout.Height(40f)))
                {
                    LevelSummary target = level;
                    Defer(() =>
                    {
                        _root.MenuSelection = target;
                        _root.City.FocusDistrict(target.Id, _root.City.Rig.LeftInset);
                    });
                }
            }
            GUILayout.EndScrollView();

            LevelSummary chosen = _root.MenuSelection;
            if (chosen != null)
            {
                GUILayout.Space(8f);
                GUILayout.Label(chosen.Title, _theme.Heading);
                GUILayout.Label(chosen.Summary, _theme.Body);
                var progress = _root.Progress.Level(chosen.Id);
                string stats = "Тестов: " + chosen.Tests.Count + " · Попыток: " + progress.Attempts;
                if (progress.Completed) stats += " · Лучшее CPU: " + Ru.Millis(progress.BestCpuNanos);
                GUILayout.Label(stats, _theme.SmallDim);
                GUILayout.Space(6f);
                GUILayout.BeginHorizontal();
                if (GUILayout.Button(Ru.Ui.Play + "  ▶", _theme.ButtonPrimary, GUILayout.Height(44f))) Defer(() => _root.OpenLevel(chosen));
                if (GUILayout.Button("Обзор города", _theme.Button, GUILayout.Height(44f), GUILayout.Width(170f))) Defer(() => _root.City.FocusCity());
                GUILayout.EndHorizontal();
            }
            GUILayout.Space(8f);
            bool settings = GUILayout.Toggle(_settingsOpen, " " + Ru.Ui.Settings, _theme.ButtonGhost, GUILayout.Height(26f));
            if (settings != _settingsOpen) Defer(() => _settingsOpen = settings);
            if (_settingsOpen) DrawServerSettings();
            GUILayout.EndArea();
        }

        // ================================================================== уровень

        private void DrawLevel()
        {
            LevelDetail level = _root.Level;
            float leftWidth = Mathf.Clamp(_width * 0.46f, 620f, 1000f);
            _root.City.Rig.LeftInset = Mathf.Clamp01(leftWidth / _width);

            // ---- заголовок
            var header = new Rect(0f, 0f, leftWidth, HeaderHeight);
            Block(header);
            GUI.Box(header, GUIContent.none, _theme.PanelFlat);
            if (GUI.Button(new Rect(10f, 8f, 110f, 36f), Ru.Ui.Back, _theme.Button)) Defer(_root.BackToMenu);
            GUI.Label(new Rect(132f, 4f, leftWidth - 390f, 26f), level.Order + ". " + level.Title, _theme.Subheading);
            GUI.Label(new Rect(132f, 27f, leftWidth - 390f, 22f), level.Chapter, _theme.SmallDim);
            GUI.enabled = !_root.Running && !_root.DebugRunning;
            if (GUI.Button(new Rect(leftWidth - 250f, 8f, 240f, 36f), _root.Running ? Ru.Ui.Running : Ru.Ui.Run, _theme.ButtonPrimary))
                Defer(_root.Run);
            GUI.enabled = true;

            // ---- левая панель: код / задание / контракт
            var tabs = new Rect(0f, HeaderHeight, leftWidth, 34f);
            Block(new Rect(0f, HeaderHeight, leftWidth, _height - HeaderHeight));
            int leftTab = Tabs(tabs, (int)_root.LeftTab, Ru.Ui.TabCode, Ru.Ui.TabBrief, Ru.Ui.TabContract);
            if (leftTab != (int)_root.LeftTab) Defer(() => _root.LeftTab = (LeftTab)leftTab);
            var content = new Rect(0f, tabs.yMax, leftWidth, _height - tabs.yMax - 28f);
            switch (_root.LeftTab)
            {
                case LeftTab.Code: _root.Editor.Draw(content, _theme); break;
                case LeftTab.Brief: DrawBrief(content, level); break;
                case LeftTab.Contract: DrawContract(content, level); break;
            }
            DrawStatusBar(new Rect(0f, _height - 28f, leftWidth, 28f));

            // ---- правая часть: баннер и панель результатов
            float x0 = leftWidth + 12f;
            float rightWidth = _width - leftWidth - 24f;
            DrawBanner(new Rect(x0, 12f, rightWidth, 96f));
            var bottom = new Rect(x0, _height * 0.5f, rightWidth, _height * 0.5f - 12f);
            Block(bottom);
            GUI.Box(bottom, GUIContent.none, _theme.Panel);
            var rightTabs = new Rect(bottom.x + 2f, bottom.y + 2f, bottom.width - 4f, 34f);
            int rightTab = Tabs(rightTabs, (int)_root.RightTab, Ru.Ui.TabResults, Ru.Ui.TabInspector, Ru.Ui.TabDebugger, Ru.Ui.TabOutput);
            if (rightTab != (int)_root.RightTab) Defer(() => _root.RightTab = (RightTab)rightTab);
            var area = new Rect(bottom.x + 14f, rightTabs.yMax + 10f, bottom.width - 28f, bottom.yMax - rightTabs.yMax - 20f);
            switch (_root.RightTab)
            {
                case RightTab.Results: DrawResults(area); break;
                case RightTab.Inspector: DrawInspector(area); break;
                case RightTab.Debugger: DrawDebugger(area); break;
                case RightTab.Output: DrawOutput(area); break;
            }
        }

        private void DrawBrief(Rect rect, LevelDetail level)
        {
            GUI.Box(rect, GUIContent.none, _theme.EditorBackground);
            GUILayout.BeginArea(Inset(rect, 22f));
            _textScroll = GUILayout.BeginScrollView(_textScroll);
            GUILayout.Label(level.Title, _theme.Heading);
            GUILayout.Label(level.Brief, _theme.Body);
            GUILayout.Space(14f);
            GUILayout.Label(Ru.Ui.Requirements, _theme.Subheading);
            foreach (string requirement in level.Requirements) GUILayout.Label("•  " + requirement, _theme.Body);
            GUILayout.Space(14f);
            GUILayout.Label(Ru.Ui.Goals, _theme.Subheading);
            foreach (string goal in level.Goals) GUILayout.Label("◆  " + goal, _theme.BodyDim);
            GUILayout.Space(14f);
            if (level.Limits != null)
            {
                GUILayout.Label("Лимиты песочницы: память " + level.Limits.HeapMegabytes + " МБ · тест " +
                                level.Limits.PerTestTimeoutMillis + " мс · потоков " + level.Limits.MaxThreads, _theme.SmallDim);
            }
            GUILayout.Space(10f);
            if (GUILayout.Button("Перейти к коду  ▶", _theme.ButtonPrimary, GUILayout.Height(40f), GUILayout.Width(240f)))
            {
                Defer(() =>
                {
                    _root.LeftTab = LeftTab.Code;
                    _root.Editor.Focused = true;
                });
            }
            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        private void DrawContract(Rect rect, LevelDetail level)
        {
            GUI.Box(rect, GUIContent.none, _theme.EditorBackground);
            GUILayout.BeginArea(Inset(rect, 18f));
            _textScroll = GUILayout.BeginScrollView(_textScroll);
            GUILayout.Label("Контракт задаёт только внешнюю форму. Класс целиком пишете вы — в пакете "
                            + level.PlayerPackage + ".", _theme.BodyDim);
            GUILayout.Space(8f);
            GUILayout.Label(level.ContractText, _theme.MonoPlain);
            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        private void DrawStatusBar(Rect rect)
        {
            GUI.Box(rect, GUIContent.none, _theme.PanelFlat);
            string status;
            if (_root.Checking || _root.CheckStale) status = "<color=#7C8FB0>" + Ru.Ui.Checking + "</color>";
            else if (_root.LastCheck == null) status = "";
            else
            {
                int errors = 0;
                foreach (var diagnostic in _root.LastCheck.Diagnostics)
                    if (diagnostic.Kind == DiagnosticKind.ERROR) errors++;
                switch (_root.LastCheck.Status)
                {
                    case ExecutionStatus.COMPILED: status = "<color=#3DFFA0>✔ компилируется, контракт соблюдён</color>"; break;
                    case ExecutionStatus.COMPILATION_ERROR: status = "<color=#FF3B5C>✘ ошибок компиляции: " + errors + "</color>"; break;
                    case ExecutionStatus.POLICY_VIOLATION: status = "<color=#FF3B5C>✘ запрещённый API: " + _root.LastCheck.PolicyViolations.Count + "</color>"; break;
                    case ExecutionStatus.CONTRACT_VIOLATION: status = "<color=#FFB020>⚠ " + _root.LastCheck.StatusDetail + "</color>"; break;
                    default: status = "<color=#FFB020>" + Ru.Title(_root.LastCheck.Status) + "</color>"; break;
                }
            }
            var caret = _root.Document.Caret;
            GUI.Label(new Rect(rect.x + 10f, rect.y + 5f, 130f, 20f), "Стр " + (caret.Line + 1) + ", стлб " + (caret.Column + 1), _theme.SmallDim);
            GUI.Label(new Rect(rect.x + 140f, rect.y + 5f, rect.width - 150f, 20f), status, _theme.Small);
        }

        private void DrawBanner(Rect rect)
        {
            Block(rect);
            GUI.Box(rect, GUIContent.none, _theme.Panel);
            ExecutionResult result = _root.LastResult;
            var inner = Inset(rect, 14f);
            if (_root.Running)
            {
                GUI.Label(new Rect(inner.x, inner.y, inner.width, 30f), Ru.Ui.Running + Dots(), _theme.Heading);
                GUI.Label(new Rect(inner.x, inner.y + 34f, inner.width, 24f), "Компиляция → проверка байткода → изолированный ClassLoader → тесты", _theme.SmallDim);
                return;
            }
            if (result == null)
            {
                GUI.Label(new Rect(inner.x, inner.y, inner.width, 60f), Ru.Ui.NoResults, _theme.BodyDim);
                GUI.Label(new Rect(inner.x, inner.yMax - 20f, inner.width, 20f), Ru.Ui.Hotkeys, _theme.SmallDim);
                return;
            }
            Color color = Palette.ForExecution(result.Status).ToColor();
            string title = Ru.Title(result.Status);
            if (result.Tests.Count > 0) title += "  " + result.PassedCount + "/" + result.Tests.Count;
            GUI.Label(new Rect(inner.x, inner.y - 2f, inner.width, 30f), "<color=#" + ColorUtility.ToHtmlStringRGB(color) + ">" + title + "</color>", _theme.Heading);
            GUI.Label(new Rect(inner.x, inner.y + 28f, inner.width, 22f), Ru.CityEvent(result.Status), _theme.Small);
            ExecutionMetrics m = result.Metrics;
            string chips = "Компиляция " + Ru.Millis(m.CompileNanos) + "  ·  CPU " + Ru.Millis(m.TotalCpuNanos)
                           + "  ·  Аллокации " + Ru.Bytes(m.TotalAllocatedBytes) + "  ·  Пик кучи " + Ru.Bytes(m.PeakHeapBytes)
                           + "  ·  GC " + m.GcCount;
            GUI.Label(new Rect(inner.x, inner.y + 50f, inner.width, 20f), chips, _theme.SmallDim);
        }

        // ================================================================== результаты

        private void DrawResults(Rect rect)
        {
            ExecutionResult result = _root.LastResult;
            GUILayout.BeginArea(rect);
            _resultsScroll = GUILayout.BeginScrollView(_resultsScroll);
            if (result == null)
            {
                LevelDetail level = _root.Level;
                GUILayout.Label("Тесты уровня:", _theme.SmallDim);
                foreach (var test in level.Tests) GUILayout.Label("○  " + test.Title, _theme.Body);
            }
            else
            {
                if (!string.IsNullOrEmpty(result.StatusDetail) && result.Status != ExecutionStatus.TESTS_FAILED)
                {
                    GUILayout.Label(result.StatusDetail, _theme.Body);
                    GUILayout.Space(6f);
                }
                foreach (var diagnostic in result.Diagnostics)
                {
                    if (diagnostic.Kind != DiagnosticKind.ERROR) continue;
                    string line = "<color=#FF3B5C>строка " + diagnostic.Line + "</color>  " + Ru.ExplainDiagnostic(diagnostic);
                    if (GUILayout.Button(line, _theme.ListItem, GUILayout.Height(28f)))
                    {
                        int target = (int)diagnostic.Line;
                        Defer(() => _root.JumpToLine(target));
                    }
                }
                foreach (var violation in result.PolicyViolations)
                {
                    GUILayout.Label("<color=#FF3B5C>" + Ru.Rule(violation.Rule) + ":</color> " + violation.Reference, _theme.Body);
                    GUILayout.Label(violation.Detail, _theme.SmallDim);
                }
                foreach (var test in result.Tests) DrawTestRow(test);
                if (result.FatalError != null) DrawError(result.FatalError, 0);
            }
            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        private void DrawTestRow(TestOutcome test)
        {
            Rect row = GUILayoutUtility.GetRect(10f, 32f, GUILayout.ExpandWidth(true));
            bool selected = _root.SelectedTest == test;
            if (selected) _theme.FillRect(row, new Color(0.13f, 0.88f, 1f, 0.12f));
            _theme.DrawBadge(new Rect(row.x, row.y + 5f, 104f, 22f), Ru.Label(test.Status), Palette.ForTest(test.Status).ToColor());
            GUI.Label(new Rect(row.x + 114f, row.y + 5f, row.width - 300f, 24f), test.Title, _theme.Body);
            string metrics = Ru.Millis(test.Metrics.WallNanos) + " · " + Ru.Bytes(test.Metrics.AllocatedBytes);
            GUI.Label(new Rect(row.xMax - 180f, row.y + 7f, 180f, 22f), metrics, _theme.SmallDim);
            if (GUI.Button(row, GUIContent.none, GUIStyle.none)) Defer(() => _root.SelectTest(test));
        }

        // ================================================================== инспектор

        private void DrawInspector(Rect rect)
        {
            TestOutcome test = _root.SelectedTest;
            GUILayout.BeginArea(rect);
            if (test == null)
            {
                GUILayout.Label(Ru.Ui.SelectBuilding, _theme.BodyDim);
                GUILayout.EndArea();
                return;
            }
            _inspectorScroll = GUILayout.BeginScrollView(_inspectorScroll);
            GUILayout.BeginHorizontal();
            Rect badge = GUILayoutUtility.GetRect(110f, 24f, GUILayout.Width(110f));
            _theme.DrawBadge(badge, Ru.Label(test.Status), Palette.ForTest(test.Status).ToColor());
            GUILayout.Label(test.Title, _theme.Heading);
            GUILayout.FlexibleSpace();
            GUI.enabled = !_root.DebugRunning && !_root.Running;
            if (GUILayout.Button(Ru.Ui.Debug, _theme.Button, GUILayout.Height(30f), GUILayout.Width(150f))) Defer(() => _root.StartDebug(test.Id));
            GUI.enabled = true;
            GUILayout.EndHorizontal();

            if (!string.IsNullOrEmpty(test.Message)) GUILayout.Label(test.Message, _theme.Body);
            if (test.Expected != null || test.Actual != null)
            {
                GUILayout.BeginHorizontal();
                DiffBox(Ru.Ui.Expected, test.Expected, Palette.Green.ToColor());
                DiffBox(Ru.Ui.Actual, test.Actual, Palette.Red.ToColor());
                GUILayout.EndHorizontal();
            }
            if (test.Error != null)
            {
                GUILayout.Space(6f);
                GUILayout.Label(Ru.Ui.Exception, _theme.Subheading);
                DrawError(test.Error, 0);
            }
            if (test.Threads.Count > 0) DrawThreads(test);
            if (test.LeakedThreads.Count > 0)
            {
                GUILayout.Space(6f);
                GUILayout.Label("<color=#FFB020>⚠ " + Ru.Ui.LeakedThreads + ":</color> " + string.Join(", ", test.LeakedThreads), _theme.Body);
            }
            GUILayout.Space(8f);
            GUILayout.Label(Ru.Ui.Metrics, _theme.Subheading);
            TestMetrics m = test.Metrics;
            GUILayout.Label("Время " + Ru.Millis(m.WallNanos) + " · CPU " + Ru.Millis(m.CpuNanos) + " · Аллокации " +
                            Ru.Bytes(m.AllocatedBytes) + " · Пик кучи " + Ru.Bytes(m.PeakHeapBytes) + " · GC " + m.GcCount +
                            " (" + m.GcTimeMillis + " мс)", _theme.Small);
            if (!string.IsNullOrEmpty(test.Output))
            {
                GUILayout.Space(8f);
                GUILayout.Label(Ru.Ui.Output, _theme.Subheading);
                GUILayout.Label(Tail(test.Output, 1500), _theme.MonoSmallPlain);
            }
            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        private void DiffBox(string title, string value, Color color)
        {
            GUILayout.BeginVertical(_theme.PanelFlat, GUILayout.MinWidth(200f));
            GUILayout.Label("<color=#" + ColorUtility.ToHtmlStringRGB(color) + ">" + title + "</color>", _theme.SmallDim);
            GUILayout.Label(value ?? "—", _theme.MonoPlain);
            GUILayout.EndVertical();
        }

        private void DrawError(ErrorReport error, int depth)
        {
            if (error == null || depth > 3) return;
            GUILayout.Label("<color=#FF3B5C>" + error.ExceptionClass + "</color>" + (error.Message != null ? ": " + error.Message : ""), _theme.Mono);
            int shown = 0;
            foreach (StackFrameInfo frame in error.Frames)
            {
                if (shown++ >= 12) break;
                string text = "   at " + frame;
                if (frame.PlayerCode)
                {
                    if (GUILayout.Button("<color=#FFD166>" + text + "   ← " + Ru.Ui.YourCode + "</color>", _theme.ListItem, GUILayout.Height(22f)))
                    {
                        int line = frame.LineNumber;
                        Defer(() => _root.JumpToLine(line));
                    }
                }
                else
                {
                    GUILayout.Label("<color=#7C8FB0>" + text + "</color>", _theme.MonoSmall);
                }
            }
            int hidden = Mathf.Max(0, error.Frames.Count - 12) + error.OmittedFrames;
            if (hidden > 0) GUILayout.Label("   … ещё " + hidden + " кадров", _theme.SmallDim);
            if (error.Cause != null)
            {
                GUILayout.Label(Ru.Ui.CausedBy + ":", _theme.SmallDim);
                DrawError(error.Cause, depth + 1);
            }
            foreach (var suppressed in error.Suppressed)
            {
                GUILayout.Label(Ru.Ui.Suppressed + ":", _theme.SmallDim);
                DrawError(suppressed, depth + 1);
            }
        }

        private void DrawThreads(TestOutcome test)
        {
            GUILayout.Space(6f);
            var graph = new DeadlockGraph(test.Threads);
            GUILayout.Label(Ru.Ui.Threads + (graph.HasCycle ? "  <color=#A66BFF>цикл: " + string.Join(" → ", graph.Cycle) + " → " + graph.Cycle[0] + "</color>" : ""), _theme.Subheading);
            foreach (ThreadSnapshot thread in test.Threads)
            {
                string color = graph.InCycle(thread.Name) ? "#A66BFF" : "#D6E4FF";
                string line = "<color=" + color + ">" + thread.Name + "</color>  [" + Ru.ThreadState(thread.State) + "]";
                if (thread.LockName != null)
                    line += "  ждёт " + ShortLock(thread.LockName) + (thread.LockOwnerName != null ? ", владелец " + thread.LockOwnerName : "");
                GUILayout.Label(line, _theme.Small);
                foreach (var frame in thread.Frames)
                {
                    if (!frame.PlayerCode) continue;
                    if (GUILayout.Button("<color=#FFD166>      at " + frame + "</color>", _theme.ListItem, GUILayout.Height(20f)))
                    {
                        int target = frame.LineNumber;
                        Defer(() => _root.JumpToLine(target));
                    }
                    break;
                }
            }
        }

        // ================================================================== отладчик

        private void DrawDebugger(Rect rect)
        {
            GUILayout.BeginArea(rect);
            LevelDetail level = _root.Level;
            GUILayout.BeginHorizontal();
            GUILayout.Label(Ru.Ui.DebugPickTest, _theme.SmallDim, GUILayout.Width(130f));
            _debugScroll = GUILayout.BeginScrollView(_debugScroll, GUILayout.Height(40f));
            GUILayout.BeginHorizontal();
            foreach (var test in level.Tests)
            {
                bool active = _root.DebugTestId == test.Id;
                if (GUILayout.Button(test.Id, active ? _theme.TabActive : _theme.Tab, GUILayout.Height(28f)))
                {
                    string id = test.Id;
                    Defer(() => _root.DebugTestId = id);
                }
            }
            GUILayout.EndHorizontal();
            GUILayout.EndScrollView();
            GUI.enabled = !_root.DebugRunning && !_root.Running;
            if (GUILayout.Button(_root.DebugRunning ? Ru.Ui.DebugRecording : Ru.Ui.DebugStart, _theme.ButtonPrimary, GUILayout.Height(30f), GUILayout.Width(210f)))
                Defer(() => _root.StartDebug(_root.DebugTestId));
            GUI.enabled = true;
            GUILayout.EndHorizontal();

            TraceNavigator nav = _root.Navigator;
            if (nav == null)
            {
                GUILayout.Space(10f);
                GUILayout.Label(_root.DebugRunning ? Ru.Ui.DebugRecording + Dots()
                    : "Отладчик записывает трассу выполнения выбранного теста: каждую строку вашего кода, стек и переменные. "
                      + "По трассе можно шагать вперёд и назад. Точки останова — щелчок по номеру строки (F9).", _theme.BodyDim);
                GUILayout.EndArea();
                return;
            }
            if (nav.Count == 0)
            {
                GUILayout.Label(Ru.Ui.DebugEmpty + " " + (nav.Trace.Note ?? ""), _theme.BodyDim);
                GUILayout.EndArea();
                return;
            }

            GUILayout.Space(4f);
            GUILayout.BeginHorizontal();
            if (GUILayout.Button(Ru.Ui.ReverseContinue, _theme.Button, GUILayout.Height(30f))) Defer(() => _root.DebugCommand(n => n.ReverseContinue()));
            if (GUILayout.Button(Ru.Ui.StepBack, _theme.Button, GUILayout.Height(30f))) Defer(() => _root.DebugCommand(n => n.StepBack()));
            if (GUILayout.Button(Ru.Ui.StepOver + "  F10", _theme.Button, GUILayout.Height(30f))) Defer(() => _root.DebugCommand(n => n.StepOver()));
            if (GUILayout.Button(Ru.Ui.StepInto + "  F11", _theme.Button, GUILayout.Height(30f))) Defer(() => _root.DebugCommand(n => n.StepInto()));
            if (GUILayout.Button(Ru.Ui.StepOut, _theme.Button, GUILayout.Height(30f))) Defer(() => _root.DebugCommand(n => n.StepOut()));
            if (GUILayout.Button(Ru.Ui.Continue + "  F8", _theme.Button, GUILayout.Height(30f))) Defer(() => _root.DebugCommand(n => n.Continue()));
            GUILayout.EndHorizontal();

            float index = GUILayout.HorizontalSlider(nav.Index, 0, Mathf.Max(0, nav.Count - 1));
            if (Mathf.RoundToInt(index) != nav.Index)
            {
                int target = Mathf.RoundToInt(index);
                Defer(() => _root.DebugCommand(n =>
                {
                    n.JumpTo(target);
                    return true;
                }));
            }
            TraceStep step = nav.Current;
            GUILayout.Label("Шаг " + (nav.Index + 1) + " из " + nav.Count + "  ·  поток " + step.Thread + "  ·  " +
                            ShortClass(step.ClassName) + "." + step.Method + "(), строка " + step.Line, _theme.Small);
            if (nav.Trace.Truncated) GUILayout.Label(Ru.Ui.DebugTruncated, _theme.SmallDim);

            GUILayout.BeginHorizontal();
            GUILayout.BeginVertical(GUILayout.Width(rect.width * 0.58f));
            GUILayout.Label(Ru.Ui.Locals, _theme.Subheading);
            HashSet<string> changed = nav.ChangedVariables();
            foreach (VariableValue variable in step.Locals)
            {
                bool isChanged = changed.Contains(variable.Name);
                string line = (isChanged ? "<color=#FFB020>" : "<color=#D6E4FF>") + variable.Name + "</color> <color=#7C8FB0>" +
                              ShortClass(variable.Type) + "</color> = " + variable.Value + (isChanged ? "  <color=#FFB020>(" + Ru.Ui.Changed + ")</color>" : "");
                GUILayout.Label(line, _theme.MonoSmall);
            }
            GUILayout.EndVertical();
            GUILayout.BeginVertical();
            GUILayout.Label(Ru.Ui.CallStack, _theme.Subheading);
            foreach (StackFrameInfo frame in step.Stack)
            {
                string text = ShortClass(frame.ClassName) + "." + frame.MethodName + ":" + frame.LineNumber;
                if (frame.PlayerCode)
                {
                    if (GUILayout.Button("<color=#FFD166>" + text + "</color>", _theme.ListItem, GUILayout.Height(20f)))
                    {
                        int line = frame.LineNumber;
                        Defer(() => _root.Editor.ScrollToLine(line));
                    }
                }
                else
                {
                    GUILayout.Label("<color=#7C8FB0>" + text + "</color>", _theme.MonoSmall);
                }
            }
            GUILayout.EndVertical();
            GUILayout.EndHorizontal();
            GUILayout.EndArea();
        }

        // ================================================================== вывод

        private void DrawOutput(Rect rect)
        {
            GUILayout.BeginArea(rect);
            _outputScroll = GUILayout.BeginScrollView(_outputScroll);
            bool any = false;
            if (_root.LastResult != null)
            {
                foreach (var test in _root.LastResult.Tests)
                {
                    if (string.IsNullOrEmpty(test.Output)) continue;
                    any = true;
                    GUILayout.Label(test.Title, _theme.Subheading);
                    GUILayout.Label(Tail(test.Output, 4000) + (test.OutputTruncated ? "\n… (вывод обрезан)" : ""), _theme.MonoSmallPlain);
                }
            }
            if (!any) GUILayout.Label(Ru.Ui.NoOutput, _theme.BodyDim);
            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        // ================================================================== оверлеи

        private void DrawWorldLabels()
        {
            if (_root.State == GameState.Connecting || _root.State == GameState.Offline) return;
            Camera camera = _root.City.Rig.Camera;
            if (camera == null) return;
            foreach (CityView.WorldLabel label in _root.City.Labels)
                WorldLabel(camera, label.Position, label.Text, label.Color, label.Small);
            foreach (DeadlockVisualizer.Label label in _root.City.Deadlocks.Labels)
                WorldLabel(camera, label.Position, label.Text, label.InCycle ? Palette.Violet.ToColor() : Palette.TextDim.ToColor(), true);
        }

        private void WorldLabel(Camera camera, Vector3 world, string text, Color color, bool small)
        {
            Vector3 screen = camera.WorldToScreenPoint(world);
            if (screen.z <= 0f || !camera.pixelRect.Contains(new Vector2(screen.x, screen.y))) return;
            var point = new Vector2(screen.x / _scale, (Screen.height - screen.y) / _scale);
            foreach (Rect blocker in _blockers)
                if (blocker.Contains(point)) return;
            GUIStyle style = small ? _theme.Small : _theme.Subheading;
            var content = new GUIContent(text);
            Vector2 size = style.CalcSize(content);
            var rect = new Rect(point.x - size.x / 2f, point.y - size.y, size.x, size.y);
            Color old = style.normal.textColor;
            style.normal.textColor = new Color(0f, 0f, 0f, 0.8f);
            GUI.Label(new Rect(rect.x + 1f, rect.y + 1f, rect.width, rect.height), text, style);
            style.normal.textColor = color;
            GUI.Label(rect, text, style);
            style.normal.textColor = old;
        }

        private void DrawToast()
        {
            if (string.IsNullOrEmpty(_root.Toast) || Time.realtimeSinceStartup > _root.ToastUntil) return;
            var rect = new Rect(_width / 2f - 360f, _height - 120f, 720f, 64f);
            GUI.Box(rect, "<b>" + _root.Toast + "</b>", _theme.Tooltip);
        }

        private void DrawVictory()
        {
            if (Time.realtimeSinceStartup > _root.VictoryUntil || _root.Level == null) return;
            float remaining = _root.VictoryUntil - Time.realtimeSinceStartup;
            float alpha = Mathf.Clamp01(remaining);
            float leftWidth = Mathf.Clamp(_width * 0.46f, 620f, 1000f);
            var rect = new Rect(leftWidth + (_width - leftWidth) / 2f - 300f, 130f, 600f, 150f);
            Color old = GUI.color;
            GUI.color = new Color(1f, 1f, 1f, alpha);
            GUI.Box(rect, GUIContent.none, _theme.Panel);
            GUI.Label(new Rect(rect.x, rect.y + 22f, rect.width, 44f), "<color=#3DFFA0>" + Ru.Ui.Victory + "</color>", Centered(_theme.Title));
            GUI.Label(new Rect(rect.x, rect.y + 76f, rect.width, 30f), "Район «" + _root.Level.Title + "» работает на вашем коде", Centered(_theme.Body));
            GUI.color = old;
        }

        // ================================================================== ввод

        private void HandleHotkeys()
        {
            Event e = Event.current;
            if (e.type != EventType.KeyDown || _root.State != GameState.Level) return;
            bool shift = e.shift;
            Action action = null;
            switch (e.keyCode)
            {
                case KeyCode.F5: action = _root.Run; break;
                case KeyCode.F6: action = () => _root.StartDebug(_root.DebugTestId); break;
                case KeyCode.F9: action = () => _root.Editor.ToggleBreakpoint(_root.Document.Caret.Line + 1); break;
                case KeyCode.F10: action = () => _root.DebugCommand(n => shift ? n.StepBackOver() : n.StepOver()); break;
                case KeyCode.F11: action = () => _root.DebugCommand(n => shift ? n.StepOut() : n.StepInto()); break;
                case KeyCode.F8: action = () => _root.DebugCommand(n => shift ? n.ReverseContinue() : n.Continue()); break;
            }
            if (action != null)
            {
                Defer(action);
                e.Use();
            }
        }

        /// <summary>Вращение, приближение и выбор зданий — только в видимой части города, не занятой панелями.</summary>
        private void HandleCameraInput()
        {
            Event e = Event.current;
            if (_root.State != GameState.Menu && _root.State != GameState.Level) return;
            Vector2 mouse = e.mousePosition;
            bool overUi = false;
            foreach (Rect blocker in _blockers)
                if (blocker.Contains(mouse)) overUi = true;
            CameraRig rig = _root.City.Rig;

            switch (e.type)
            {
                case EventType.MouseDown:
                    if (overUi) return;
                    _pressed = true;
                    _orbiting = false;
                    _pressPosition = mouse;
                    e.Use();
                    break;
                case EventType.MouseDrag:
                    if (!_pressed) return;
                    if ((mouse - _pressPosition).sqrMagnitude > 16f) _orbiting = true;
                    if (_orbiting) rig.Orbit(e.delta.x, e.delta.y);
                    e.Use();
                    break;
                case EventType.MouseUp:
                    if (!_pressed) return;
                    _pressed = false;
                    if (!_orbiting && e.button == 0)
                    {
                        var screen = new Vector3(mouse.x * _scale, Screen.height - mouse.y * _scale, 0f);
                        BuildingView building = _root.City.Pick(rig.Camera.ScreenPointToRay(screen));
                        if (building != null) Defer(() => _root.SelectBuilding(building));
                    }
                    e.Use();
                    break;
                case EventType.ScrollWheel:
                    if (overUi) return;
                    rig.Zoom(e.delta.y);
                    e.Use();
                    break;
            }
        }

        // ================================================================== утилиты

        private int Tabs(Rect rect, int active, params string[] titles)
        {
            float width = rect.width / titles.Length;
            for (int i = 0; i < titles.Length; i++)
            {
                if (GUI.Button(new Rect(rect.x + i * width, rect.y, width, rect.height), titles[i], i == active ? _theme.TabActive : _theme.Tab))
                    active = i;
            }
            return active;
        }

        private void Block(Rect rect)
        {
            _blockers.Add(rect);
        }

        private Rect Centered(float width, float height)
        {
            return new Rect((_width - width) / 2f, (_height - height) / 2f, width, height);
        }

        private static Rect Inset(Rect rect, float padding)
        {
            return new Rect(rect.x + padding, rect.y + padding, rect.width - padding * 2f, rect.height - padding * 2f);
        }

        private static GUIStyle Centered(GUIStyle style)
        {
            return new GUIStyle(style) { alignment = TextAnchor.MiddleCenter };
        }

        private static string Dots()
        {
            int count = (int)(Time.realtimeSinceStartup * 3f) % 4;
            return new string('.', count);
        }

        private static string Tail(string text, int maxChars)
        {
            if (text == null) return "";
            return text.Length <= maxChars ? text : "…" + text.Substring(text.Length - maxChars);
        }

        private static string ShortClass(string name)
        {
            if (string.IsNullOrEmpty(name)) return name;
            int dot = name.LastIndexOf('.');
            return dot < 0 ? name : name.Substring(dot + 1);
        }

        private static string ShortLock(string lockName)
        {
            int at = lockName.IndexOf('@');
            string type = at < 0 ? lockName : lockName.Substring(0, at);
            return ShortClass(type) + (at < 0 ? "" : lockName.Substring(at));
        }
    }
}
