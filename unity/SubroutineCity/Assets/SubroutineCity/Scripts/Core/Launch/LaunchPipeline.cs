using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace SubroutineCity.Core.Launch
{
    public enum LaunchStage
    {
        Idle,
        LocatingJdk,
        /// <summary>Подходящий JDK не найден — нужно согласие игрока на скачивание.</summary>
        NeedsJdk,
        DownloadingJdk,
        Building,
        Starting,
        WaitingForServer,
        Running,
        Failed
    }

    public sealed class LaunchOptions
    {
        /// <summary>Корень репозитория (src/main/java, target/classes); null — сервер уже собран в ClassesDirectory.</summary>
        public string Repository;
        /// <summary>Каталог классов; по умолчанию Repository/target/classes.</summary>
        public string ClassesDirectory;
        /// <summary>Куда устанавливать JDK; по умолчанию Repository/.jdk (общий со скриптами).</summary>
        public string ManagedJdkRoot;
        /// <summary>Дополнительные каталоги поиска управляемого JDK.</summary>
        public List<string> ExtraJdkRoots = new List<string>();
        public int Port = 8787;
        public HostPlatform Platform = HostPlatform.Current();
        /// <summary>Проверка «сервер отвечает»; по умолчанию GET /api/v1/health.</summary>
        public Func<CancellationToken, Task<bool>> HealthCheck;
    }

    /// <summary>
    /// Запуск локального сервера «в один клик»: найти JDK 21 → (с согласия) скачать Temurin 21 →
    /// собрать сервер, если исходники новее сборки → запустить → дождаться ответа.
    /// Потокобезопасное состояние (Stage, Progress, Message) читает интерфейс Unity каждый кадр.
    /// </summary>
    public sealed class LaunchPipeline : IDisposable
    {
        private readonly LaunchOptions _options;
        private readonly ServerProcess _server = new ServerProcess();
        private readonly HttpClient _http = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        private volatile LaunchStage _stage = LaunchStage.Idle;
        private volatile string _message = "";
        private volatile string _error;
        private float _progress = -1f;

        public LaunchPipeline(LaunchOptions options)
        {
            _options = options ?? throw new ArgumentNullException(nameof(options));
            if (string.IsNullOrEmpty(_options.ClassesDirectory) && _options.Repository != null)
                _options.ClassesDirectory = Path.Combine(_options.Repository, "target", "classes");
            if (string.IsNullOrEmpty(_options.ManagedJdkRoot) && _options.Repository != null)
                _options.ManagedJdkRoot = Path.Combine(_options.Repository, ".jdk");
            if (_options.HealthCheck == null) _options.HealthCheck = DefaultHealthCheck;
        }

        public LaunchStage Stage => _stage;
        public string Message => _message;
        public string Error => _error;
        public float Progress => Volatile.Read(ref _progress);
        public JdkInfo Jdk { get; private set; }
        public string ServerLog => _server.Log;
        public bool ServerRunning => _server.Running;
        public string ManagedJdkRoot => _options.ManagedJdkRoot;

        /// <summary>Найти JDK и, если он есть, собрать и запустить сервер. Без JDK останавливается на NeedsJdk.</summary>
        public async Task RunAsync(CancellationToken cancel)
        {
            try
            {
                Set(LaunchStage.LocatingJdk, "Поиск Java 21…");
                Jdk = await Task.Run(() => JdkLocator.Find(_options.Platform, JdkRoots(), _server.Append), cancel).ConfigureAwait(false);
                if (Jdk == null)
                {
                    Set(LaunchStage.NeedsJdk, "Не найден JDK " + JdkLocator.RequiredMajor + " (нужен именно JDK, с javac).");
                    return;
                }
                _server.Append("Найден " + Jdk);
                await BuildAndStartAsync(cancel).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                Fail("Запуск отменён.");
            }
            catch (Exception e)
            {
                Fail(e.Message);
            }
        }

        /// <summary>Скачать Temurin 21 (после согласия игрока) и продолжить запуск.</summary>
        public async Task InstallJdkAndRunAsync(CancellationToken cancel)
        {
            try
            {
                if (string.IsNullOrEmpty(_options.ManagedJdkRoot)) throw new InvalidOperationException("Не задан каталог для установки JDK.");
                Set(LaunchStage.DownloadingJdk, "Скачивание Java 21 (Eclipse Temurin)…");
                Volatile.Write(ref _progress, 0f);
                string home = await new JdkInstaller(_http).InstallAsync(_options.Platform, _options.ManagedJdkRoot,
                    p => Volatile.Write(ref _progress, p), _server.Append, cancel).ConfigureAwait(false);
                Volatile.Write(ref _progress, -1f);
                Jdk = JdkLocator.Probe(home, "установлен игрой", _options.Platform, _server.Append);
                if (Jdk == null) throw new InvalidDataException("Установленный JDK не запускается: " + home);
                await BuildAndStartAsync(cancel).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                Fail("Скачивание отменено.");
            }
            catch (Exception e)
            {
                Fail(e.Message);
            }
        }

        private async Task BuildAndStartAsync(CancellationToken cancel)
        {
            string classes = _options.ClassesDirectory;
            if (ServerBuilder.HasSources(_options.Repository))
            {
                if (ServerBuilder.IsStale(_options.Repository, classes))
                {
                    Set(LaunchStage.Building, "Сборка сервера песочницы…");
                    await Task.Run(() => ServerBuilder.Build(Jdk, _options.Repository, classes, _server.Append), cancel).ConfigureAwait(false);
                }
            }
            else if (string.IsNullOrEmpty(classes) || !Directory.Exists(Path.Combine(classes, "city", "subroutine", "server")))
            {
                throw new DirectoryNotFoundException("Не найдены ни исходники, ни собранный сервер. Укажите путь к репозиторию в настройках.");
            }

            Set(LaunchStage.Starting, "Запуск сервера…");
            _server.Start(Jdk, classes, _options.Port);
            Set(LaunchStage.WaitingForServer, "Ожидание ответа сервера…");
            for (int attempt = 0; attempt < 120; attempt++)
            {
                cancel.ThrowIfCancellationRequested();
                if (await _options.HealthCheck(cancel).ConfigureAwait(false))
                {
                    Set(LaunchStage.Running, "Сервер запущен.");
                    return;
                }
                if (!_server.Running) throw new InvalidOperationException("Сервер завершился при старте. Журнал:\n" + Tail(_server.Log));
                await Task.Delay(500, cancel).ConfigureAwait(false);
            }
            throw new TimeoutException("Сервер не ответил за 60 секунд.");
        }

        private async Task<bool> DefaultHealthCheck(CancellationToken cancel)
        {
            try
            {
                using (var request = new CancellationTokenSource(TimeSpan.FromSeconds(2)))
                using (var linked = CancellationTokenSource.CreateLinkedTokenSource(cancel, request.Token))
                using (HttpResponseMessage response = await _http.GetAsync("http://127.0.0.1:" + _options.Port + "/api/v1/health", linked.Token).ConfigureAwait(false))
                {
                    return response.IsSuccessStatusCode;
                }
            }
            catch (HttpRequestException)
            {
                return false;
            }
            catch (TaskCanceledException) when (!cancel.IsCancellationRequested)
            {
                return false;
            }
        }

        private IEnumerable<string> JdkRoots()
        {
            if (!string.IsNullOrEmpty(_options.ManagedJdkRoot)) yield return _options.ManagedJdkRoot;
            foreach (string root in _options.ExtraJdkRoots) yield return root;
        }

        private void Set(LaunchStage stage, string message)
        {
            _message = message;
            _stage = stage;
            _server.Append(message);
        }

        private void Fail(string error)
        {
            _error = error;
            _message = error;
            _stage = LaunchStage.Failed;
            _server.Append("Ошибка: " + error);
        }

        private static string Tail(string text) => text.Length <= 2000 ? text : text.Substring(text.Length - 2000);

        public void Dispose()
        {
            _server.Dispose();
            _http.Dispose();
        }
    }
}
