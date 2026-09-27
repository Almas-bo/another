using System;
using System.Collections;
using System.Collections.Generic;
using SubroutineCity.City;
using SubroutineCity.Core.Debugging;
using SubroutineCity.Core.Editing;
using SubroutineCity.Core.Localization;
using SubroutineCity.Core.Progress;
using SubroutineCity.Core.Protocol;
using SubroutineCity.Net;
using SubroutineCity.UI;
using UnityEngine;

namespace SubroutineCity
{
    public enum GameState { Connecting, Offline, Menu, Level }

    public enum LeftTab { Code, Brief, Contract }

    public enum RightTab { Results, Inspector, Debugger, Output }

    /// <summary>
    /// Корневой контроллер игры: подключение к серверу песочницы, кампания, уровень, запуск, проверка при наборе,
    /// отладка по трассе, прогресс. Отрисовка — в <see cref="GameUI"/>, 3D-город — в <see cref="CityView"/>.
    /// </summary>
    public sealed class GameRoot : MonoBehaviour
    {
        public const int DefaultPort = 8787;
        private const float CheckDelaySeconds = 1.1f;
        private const float DraftSaveDelaySeconds = 2f;

        private readonly LocalServerLauncher _launcher = new LocalServerLauncher();
        private GameUI _ui;
        private int _checkedVersion = -1;
        private int _seenVersion = -1;
        private float _lastEditTime;
        private bool _draftDirty;

        public GameState State { get; private set; } = GameState.Connecting;
        public string StatusMessage { get; private set; } = Ru.Ui.Connecting;
        public string OfflineDetail { get; private set; }
        public ProgressState Progress { get; private set; }
        public ApiClient Api { get; private set; }
        public CityView City { get; private set; }
        public LocalServerLauncher Launcher => _launcher;

        public List<LevelSummary> Levels { get; } = new List<LevelSummary>();
        public LevelSummary MenuSelection { get; set; }

        public LevelDetail Level { get; private set; }
        public CodeDocument Document { get; private set; }
        public CodeEditorView Editor { get; } = new CodeEditorView();
        public ExecutionResult LastResult { get; private set; }
        public ExecutionResult LastCheck { get; private set; }
        public bool Running { get; private set; }
        public bool Checking { get; private set; }
        public bool DebugRunning { get; private set; }
        public DebugResult DebugSession { get; private set; }
        public TraceNavigator Navigator { get; private set; }
        public string DebugTestId { get; set; }
        public TestOutcome SelectedTest { get; private set; }
        public LeftTab LeftTab { get; set; }
        public RightTab RightTab { get; set; }
        public float VictoryUntil { get; private set; }
        public string Toast { get; private set; }
        public float ToastUntil { get; private set; }

        /// <summary>Документ изменён после последней проверки (диагностика может быть устаревшей).</summary>
        public bool CheckStale => Document != null && _checkedVersion != Document.Version;

        private void Awake()
        {
            Application.targetFrameRate = 60;
            Camera camera = Camera.main;
            if (camera == null)
            {
                var go = new GameObject("Main Camera") { tag = "MainCamera" };
                camera = go.AddComponent<Camera>();
            }
            foreach (Light light in FindLights()) light.intensity = 0.2f;

            City = new GameObject("Subroutine City").AddComponent<CityView>();
            City.Init(camera);
            Progress = ProgressStore.Load();
            Api = new ApiClient(Progress.ServerUrl);
            _ui = new GameUI(this);
        }

        private void Start()
        {
            City.FocusCity();
            StartCoroutine(Connect());
        }

        private void OnGUI()
        {
            _ui.Draw();
        }

        private void Update()
        {
            if (State != GameState.Level || Document == null) return;
            if (Document.Version != _seenVersion)
            {
                _seenVersion = Document.Version;
                _lastEditTime = Time.realtimeSinceStartup;
                _draftDirty = true;
            }
            float idle = Time.realtimeSinceStartup - _lastEditTime;
            if (_draftDirty && idle > DraftSaveDelaySeconds) SaveDraft();
            if (!Checking && !Running && CheckStale && idle > CheckDelaySeconds) Check();
        }

        private void OnApplicationQuit()
        {
            SaveDraft();
            _launcher.Dispose();
        }

        private void OnDestroy()
        {
            _launcher.Dispose();
        }

        // ------------------------------------------------------------------ подключение

        public void Reconnect(string serverUrl, string repositoryPath)
        {
            Progress.ServerUrl = string.IsNullOrWhiteSpace(serverUrl) ? "http://127.0.0.1:" + DefaultPort : serverUrl.Trim();
            Progress.RepositoryPath = repositoryPath ?? "";
            ProgressStore.Save(Progress);
            Api.BaseUrl = Progress.ServerUrl;
            StopAllCoroutines();
            StartCoroutine(Connect());
        }

        private IEnumerator Connect()
        {
            State = GameState.Connecting;
            StatusMessage = Ru.Ui.Connecting;
            OfflineDetail = null;
            bool healthy = false;
            yield return Api.Health(ok => healthy = ok);

            if (!healthy && IsLocalServer() && LocalServerLauncher.Supported && !_launcher.Running)
            {
                StatusMessage = Ru.Ui.StartingServer;
                if (_launcher.TryStart(PortOf(Api.BaseUrl), Progress.RepositoryPath, out string error))
                {
                    for (int attempt = 0; attempt < 40 && !healthy && _launcher.Running; attempt++)
                    {
                        yield return new WaitForSecondsRealtime(0.75f);
                        yield return Api.Health(ok => healthy = ok);
                    }
                    if (!healthy) OfflineDetail = "Сервер запущен, но не отвечает. Журнал сервера — ниже.";
                }
                else
                {
                    OfflineDetail = error;
                }
            }
            if (!healthy)
            {
                State = GameState.Offline;
                if (OfflineDetail == null) OfflineDetail = "Нет ответа от " + Api.BaseUrl;
                yield break;
            }

            ApiError failure = null;
            List<LevelSummary> levels = null;
            yield return Api.Levels(result => levels = result, error => failure = error);
            if (failure != null)
            {
                State = GameState.Offline;
                OfflineDetail = failure.Message;
                yield break;
            }
            Levels.Clear();
            Levels.AddRange(levels);
            City.BuildDistricts(Levels);
            foreach (var level in Levels)
                if (Progress.IsCompleted(level.Id)) City.MarkCompleted(level.Id);
            MenuSelection = Levels.Find(l => !Progress.IsCompleted(l.Id)) ?? (Levels.Count > 0 ? Levels[0] : null);
            State = GameState.Menu;
        }

        private bool IsLocalServer()
        {
            string url = Api.BaseUrl ?? "";
            return url.Contains("127.0.0.1") || url.Contains("localhost");
        }

        private static int PortOf(string url)
        {
            try
            {
                var uri = new Uri(url);
                return uri.Port > 0 ? uri.Port : DefaultPort;
            }
            catch (UriFormatException)
            {
                return DefaultPort;
            }
        }

        // ------------------------------------------------------------------ кампания

        public void OpenLevel(LevelSummary summary)
        {
            if (summary == null) return;
            StartCoroutine(OpenLevelRoutine(summary.Id));
        }

        private IEnumerator OpenLevelRoutine(string levelId)
        {
            StatusMessage = "Загрузка уровня…";
            LevelDetail detail = null;
            ApiError failure = null;
            yield return Api.Level(levelId, result => detail = result, error => failure = error);
            if (failure != null)
            {
                ShowToast(failure.Message);
                yield break;
            }
            Level = detail;
            string draft = Progress.Level(detail.Id).Draft;
            Document = new CodeDocument(string.IsNullOrEmpty(draft) ? detail.StarterCode : draft);
            Document.MoveDocumentEnd(false);
            Editor.SetDocument(Document, new CompletionEngine(detail.ContractText));
            Editor.Diagnostics = new List<CompilationDiagnostic>();
            Editor.Focused = true;
            _checkedVersion = -1;
            _seenVersion = Document.Version;
            _draftDirty = false;
            LastResult = null;
            LastCheck = null;
            ClearDebug();
            SelectedTest = null;
            LeftTab = string.IsNullOrEmpty(draft) ? LeftTab.Brief : LeftTab.Code;
            RightTab = RightTab.Results;
            DebugTestId = detail.Tests.Count > 0 ? detail.Tests[0].Id : null;
            State = GameState.Level;
            City.ClearEffects();
            City.District(detail.Id)?.ResetStates();
        }

        public void BackToMenu()
        {
            SaveDraft();
            ClearDebug();
            City.Select(null);
            City.Inspect(null);
            City.ClearEffects();
            if (Level != null && Progress.IsCompleted(Level.Id)) City.MarkCompleted(Level.Id);
            MenuSelection = Levels.Find(l => Level != null && l.Id == Level.Id) ?? MenuSelection;
            Level = null;
            Document = null;
            State = GameState.Menu;
            City.FocusCity();
        }

        public void SaveDraft()
        {
            if (Level == null || Document == null || !_draftDirty) return;
            Progress.Level(Level.Id).Draft = Document.Text;
            ProgressStore.Save(Progress);
            _draftDirty = false;
        }

        // ------------------------------------------------------------------ запуск и проверка

        public void Run()
        {
            if (Level == null || Running) return;
            SaveDraft();
            Running = true;
            ClearDebug();
            City.BeginRun(Level.Id);
            string levelId = Level.Id;
            StartCoroutine(Api.Run(levelId, Document.Text, result => OnRunResult(levelId, result), OnRunError));
        }

        private void OnRunResult(string levelId, ExecutionResult result)
        {
            Running = false;
            if (Level == null || Level.Id != levelId) return;
            LastResult = result;
            Editor.Diagnostics = result.Diagnostics;
            if (result.Status == ExecutionStatus.COMPILATION_ERROR) _checkedVersion = Document.Version;
            City.ApplyResult(levelId, result);
            bool firstCompletion = Progress.Record(levelId, result);
            ProgressStore.Save(Progress);
            if (firstCompletion) VictoryUntil = Time.realtimeSinceStartup + 7f;

            SelectedTest = result.Tests.Find(t => !t.Passed && t.Status != TestStatus.SKIPPED);
            if (SelectedTest != null)
            {
                BuildingView building = City.District(levelId)?.Find(SelectedTest.Id);
                City.Select(building);
                DebugTestId = SelectedTest.Id;
            }
            RightTab = RightTab.Results;
            if (result.Status == ExecutionStatus.COMPILATION_ERROR) LeftTab = LeftTab.Code;
        }

        private void OnRunError(ApiError error)
        {
            Running = false;
            if (Level != null) City.District(Level.Id)?.ResetStates();
            ShowToast(error.Message);
            if (error.HttpStatus == 0) OfflineDetail = error.Message;
        }

        private void Check()
        {
            if (Level == null) return;
            Checking = true;
            int version = Document.Version;
            string levelId = Level.Id;
            StartCoroutine(Api.Check(levelId, Document.Text, result =>
            {
                Checking = false;
                _checkedVersion = version;
                if (Level == null || Level.Id != levelId) return;
                LastCheck = result;
                if (Document.Version == version) Editor.Diagnostics = result.Diagnostics;
                City.ApplyCheck(levelId, result);
            }, error =>
            {
                Checking = false;
                _checkedVersion = version;
            }));
        }

        // ------------------------------------------------------------------ отладка

        public void StartDebug(string testId)
        {
            if (Level == null || DebugRunning || Running || string.IsNullOrEmpty(testId)) return;
            SaveDraft();
            DebugRunning = true;
            DebugTestId = testId;
            RightTab = RightTab.Debugger;
            string levelId = Level.Id;
            City.District(levelId)?.Find(testId)?.SetMode(Core.City.BuildingMode.Scanning, instant: false);
            StartCoroutine(Api.Debug(levelId, Document.Text, testId, session =>
            {
                DebugRunning = false;
                if (Level == null || Level.Id != levelId) return;
                DebugSession = session;
                Navigator = new TraceNavigator(session.Trace, Editor.Breakpoints);
                TestOutcome outcome = session.Result.Test(testId);
                if (outcome != null) City.District(levelId)?.Find(testId)?.SetStatus(outcome.Status);
                if (session.Result.Diagnostics.Count > 0) Editor.Diagnostics = session.Result.Diagnostics;
                SyncDebugLine();
            }, error =>
            {
                DebugRunning = false;
                ShowToast(error.Message);
            }));
        }

        /// <summary>Выполнить команду навигатора и синхронизировать редактор.</summary>
        public void DebugCommand(Func<TraceNavigator, bool> command)
        {
            if (Navigator == null) return;
            Navigator.SetBreakpoints(Editor.Breakpoints);
            command(Navigator);
            SyncDebugLine();
        }

        public void SyncDebugLine()
        {
            TraceStep step = Navigator?.Current;
            Editor.ExecutionLine = step?.Line ?? -1;
            Editor.LineHits = Navigator?.LineHits();
            if (step != null)
            {
                LeftTab = LeftTab.Code;
                Editor.ScrollToLine(step.Line);
            }
        }

        public void ClearDebug()
        {
            DebugSession = null;
            Navigator = null;
            Editor.ExecutionLine = -1;
            Editor.LineHits = null;
        }

        // ------------------------------------------------------------------ выбор

        public void SelectTest(TestOutcome outcome)
        {
            SelectedTest = outcome;
            if (Level == null || outcome == null) return;
            BuildingView building = City.District(Level.Id)?.Find(outcome.Id);
            City.Select(building);
            City.Inspect(building);
            City.FocusBuilding(building);
            DebugTestId = outcome.Id;
            RightTab = RightTab.Inspector;
        }

        public void SelectBuilding(BuildingView building)
        {
            if (building == null) return;
            if (State == GameState.Menu)
            {
                MenuSelection = Levels.Find(l => l.Id == building.District.Level.Id) ?? MenuSelection;
                return;
            }
            TestOutcome outcome = LastResult?.Test(building.TestId);
            if (outcome != null)
            {
                SelectTest(outcome);
            }
            else
            {
                City.Select(building);
                DebugTestId = building.TestId;
            }
        }

        public void JumpToLine(int line)
        {
            if (line < 1) return;
            LeftTab = LeftTab.Code;
            Editor.RevealLine(line);
            Editor.Focused = true;
        }

        public void ShowToast(string message)
        {
            Toast = message;
            ToastUntil = Time.realtimeSinceStartup + 6f;
        }

        private static Light[] FindLights()
        {
#if UNITY_2023_1_OR_NEWER
            return FindObjectsByType<Light>(FindObjectsSortMode.None);
#else
            return FindObjectsOfType<Light>();
#endif
        }
    }
}
