using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using UnityEngine;

namespace SubroutineCity.Net
{
    /// <summary>
    /// Автозапуск локального сервера песочницы (Java), если он не запущен. Ищет собранные классы сервера:
    /// рядом с репозиторием (unity/SubroutineCity → ../../target/classes) или в StreamingAssets/server/classes.
    /// Работает на настольных платформах; на остальных сервер запускается отдельно.
    /// </summary>
    public sealed class LocalServerLauncher : IDisposable
    {
        private const int MaxLogChars = 8000;
        private const string ServerMainClass = "city.subroutine.server.GameServer";

        private readonly StringBuilder _log = new StringBuilder();
        private Process _process;

        public bool Started => _process != null;

        public bool Running
        {
            get
            {
                try
                {
                    return _process != null && !_process.HasExited;
                }
                catch (InvalidOperationException)
                {
                    return false;
                }
            }
        }

        public string Log
        {
            get
            {
                lock (_log) return _log.ToString();
            }
        }

        public static bool Supported =>
            Application.platform == RuntimePlatform.WindowsEditor || Application.platform == RuntimePlatform.WindowsPlayer
            || Application.platform == RuntimePlatform.OSXEditor || Application.platform == RuntimePlatform.OSXPlayer
            || Application.platform == RuntimePlatform.LinuxEditor || Application.platform == RuntimePlatform.LinuxPlayer;

        /// <summary>Каталог с классами сервера или null.</summary>
        public static string FindServerClasses(string extraRepositoryRoot)
        {
            var candidates = new System.Collections.Generic.List<string>();
            if (!string.IsNullOrEmpty(extraRepositoryRoot)) candidates.Add(Path.Combine(extraRepositoryRoot, "target", "classes"));
            candidates.Add(Path.Combine(Application.dataPath, "..", "..", "..", "target", "classes"));
            candidates.Add(Path.Combine(Application.streamingAssetsPath, "server", "classes"));
            foreach (string candidate in candidates)
            {
                try
                {
                    string full = Path.GetFullPath(candidate);
                    if (Directory.Exists(Path.Combine(full, "city", "subroutine", "server"))) return full;
                }
                catch (Exception)
                {
                    // некорректный путь из настроек — пропускаем
                }
            }
            return null;
        }

        public bool TryStart(int port, string extraRepositoryRoot, out string error)
        {
            error = null;
            if (!Supported)
            {
                error = "Автозапуск сервера недоступен на этой платформе.";
                return false;
            }
            string classes = FindServerClasses(extraRepositoryRoot);
            if (classes == null)
            {
                error = "Не найдены классы сервера (target/classes). Соберите сервер: scripts/build.sh или scripts\\build.bat, "
                        + "либо укажите путь к репозиторию в настройках.";
                return false;
            }
            var start = new ProcessStartInfo
            {
                FileName = FindJava(),
                Arguments = "-cp \"" + classes + "\" " + ServerMainClass + " --port " + port,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            try
            {
                _process = new Process { StartInfo = start, EnableRaisingEvents = true };
                _process.OutputDataReceived += (_, e) => Append(e.Data);
                _process.ErrorDataReceived += (_, e) => Append(e.Data);
                _process.Start();
                _process.BeginOutputReadLine();
                _process.BeginErrorReadLine();
                Append("Запущен: " + start.FileName + " " + start.Arguments);
                return true;
            }
            catch (Exception e)
            {
                _process = null;
                error = "Не удалось запустить Java (" + e.Message + "). Установите JDK 21 и задайте JAVA_HOME.";
                return false;
            }
        }

        public void Dispose()
        {
            try
            {
                if (_process != null && !_process.HasExited) _process.Kill();
            }
            catch (Exception)
            {
                // процесс уже завершён
            }
            _process = null;
        }

        private static string FindJava()
        {
            string executable = Application.platform == RuntimePlatform.WindowsEditor
                                || Application.platform == RuntimePlatform.WindowsPlayer ? "java.exe" : "java";
            string home = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(home))
            {
                string path = Path.Combine(home, "bin", executable);
                if (File.Exists(path)) return path;
            }
            return executable;
        }

        private void Append(string line)
        {
            if (line == null) return;
            lock (_log)
            {
                _log.AppendLine(line);
                if (_log.Length > MaxLogChars) _log.Remove(0, _log.Length - MaxLogChars);
            }
        }
    }
}
