using System;
using System.Diagnostics;
using System.Text;

namespace SubroutineCity.Core.Launch
{
    /// <summary>Процесс локального игрового сервера. Вывод собирается в ограниченный журнал.</summary>
    public sealed class ServerProcess : IDisposable
    {
        public const string MainClass = "city.subroutine.server.GameServer";
        private const int MaxLogChars = 12000;

        private readonly StringBuilder _log = new StringBuilder();
        private Process _process;

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

        public void Start(JdkInfo jdk, string classesDirectory, int port)
        {
            var start = new ProcessStartInfo
            {
                FileName = jdk.Java,
                Arguments = "-cp " + ProcessRunner.Quote(classesDirectory) + " " + MainClass + " --port " + port,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            start.EnvironmentVariables.Remove("JAVA_TOOL_OPTIONS");
            start.EnvironmentVariables.Remove("_JAVA_OPTIONS");
            _process = new Process { StartInfo = start, EnableRaisingEvents = true };
            _process.OutputDataReceived += (_, e) => Append(e.Data);
            _process.ErrorDataReceived += (_, e) => Append(e.Data);
            _process.Start();
            _process.BeginOutputReadLine();
            _process.BeginErrorReadLine();
            Append("Запущен сервер: " + start.FileName + " " + start.Arguments);
        }

        public void Append(string line)
        {
            if (line == null) return;
            lock (_log)
            {
                _log.AppendLine(line);
                if (_log.Length > MaxLogChars) _log.Remove(0, _log.Length - MaxLogChars);
            }
        }

        /// <summary>Останавливает сервер. Его простаивающие воркеры завершаются сами, получив конец stdin.</summary>
        public void Dispose()
        {
            try
            {
                if (_process != null && !_process.HasExited) _process.Kill();
            }
            catch (InvalidOperationException)
            {
            }
            catch (System.ComponentModel.Win32Exception)
            {
            }
            _process = null;
        }
    }
}
