using System;
using System.Diagnostics;
using System.Text;

namespace SubroutineCity.Core.Launch
{
    /// <summary>Результат запуска внешней программы.</summary>
    public sealed class ProcessResult
    {
        public int ExitCode;
        public string Output = "";
        public bool TimedOut;
    }

    /// <summary>Синхронный запуск короткой программы (java -version, javac, tar) с общим выводом stdout+stderr.</summary>
    public static class ProcessRunner
    {
        public static ProcessResult Run(string fileName, string arguments, string workingDirectory, TimeSpan timeout)
        {
            var output = new StringBuilder();
            var start = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments,
                WorkingDirectory = workingDirectory ?? "",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            // Не наследуем агентов и флаги JVM из окружения: они печатают лишнее и ломают разбор версии.
            start.EnvironmentVariables.Remove("JAVA_TOOL_OPTIONS");
            start.EnvironmentVariables.Remove("_JAVA_OPTIONS");
            using (var process = new Process { StartInfo = start })
            {
                process.OutputDataReceived += (_, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
                process.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
                process.Start();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                bool exited = process.WaitForExit((int)timeout.TotalMilliseconds);
                if (!exited)
                {
                    try { process.Kill(); } catch (InvalidOperationException) { }
                    return new ProcessResult { ExitCode = -1, TimedOut = true, Output = output.ToString() };
                }
                process.WaitForExit(); // дочитать асинхронный вывод
                lock (output) return new ProcessResult { ExitCode = process.ExitCode, Output = output.ToString() };
            }
        }

        /// <summary>Аргумент командной строки в кавычках (пути с пробелами).</summary>
        public static string Quote(string argument) => "\"" + argument.Replace("\"", "\\\"") + "\"";
    }
}
