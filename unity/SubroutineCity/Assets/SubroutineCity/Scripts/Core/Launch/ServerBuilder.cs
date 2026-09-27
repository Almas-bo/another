using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace SubroutineCity.Core.Launch
{
    /// <summary>
    /// Сборка сервера из исходников тем же javac-вызовом, что и scripts/build.sh. Инкрементальность — по метке
    /// target/classes/.build-stamp (её пишут и скрипты, и клиент): сборка нужна, если метки нет или хоть один
    /// исходник новее неё.
    /// </summary>
    public static class ServerBuilder
    {
        public const string StampFile = ".build-stamp";
        public const string SourcesRoot = "src/main/java";

        public static bool HasSources(string repository) =>
            !string.IsNullOrEmpty(repository) && Directory.Exists(Path.Combine(repository, SourcesRoot));

        public static bool IsStale(string repository, string classesDirectory)
        {
            string stamp = Path.Combine(classesDirectory, StampFile);
            if (!File.Exists(stamp)) return true;
            DateTime built = File.GetLastWriteTimeUtc(stamp);
            foreach (string source in Directory.EnumerateFiles(Path.Combine(repository, SourcesRoot), "*.java", SearchOption.AllDirectories))
                if (File.GetLastWriteTimeUtc(source) > built) return true;
            return false;
        }

        /// <summary>Полная пересборка в <paramref name="classesDirectory"/>; при ошибке бросает исключение с выводом javac.</summary>
        public static void Build(JdkInfo jdk, string repository, string classesDirectory, Action<string> log)
        {
            string sourcesRoot = Path.Combine(repository, SourcesRoot);
            var sources = new List<string>();
            foreach (string source in Directory.EnumerateFiles(sourcesRoot, "*.java", SearchOption.AllDirectories))
                sources.Add(source);
            if (sources.Count == 0) throw new IOException("Нет исходников сервера в " + sourcesRoot);
            sources.Sort(StringComparer.Ordinal);

            if (Directory.Exists(classesDirectory)) Directory.Delete(classesDirectory, true);
            Directory.CreateDirectory(classesDirectory);
            // Список файлов — в argfile: пути в кавычках, прямые слэши (javac понимает их на всех ОС).
            string argfile = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(classesDirectory)) ?? repository, "sources.txt");
            var content = new StringBuilder();
            foreach (string source in sources) content.Append('"').Append(source.Replace('\\', '/')).Append("\"\n");
            File.WriteAllText(argfile, content.ToString(), new UTF8Encoding(false));

            log?.Invoke("Сборка сервера: " + sources.Count + " файлов (" + jdk + ")");
            string arguments = "-encoding UTF-8 --release " + JdkLocator.RequiredMajor + " -parameters -d "
                               + ProcessRunner.Quote(classesDirectory.Replace('\\', '/')) + " " + ProcessRunner.Quote("@" + argfile.Replace('\\', '/'));
            ProcessResult result = ProcessRunner.Run(jdk.Javac, arguments, repository, TimeSpan.FromMinutes(5));
            if (result.ExitCode != 0)
                throw new IOException("javac завершился с кодом " + result.ExitCode + (result.TimedOut ? " (таймаут)" : "") + ":\n" + result.Output);
            File.WriteAllText(Path.Combine(classesDirectory, StampFile), DateTime.UtcNow.ToString("o"));
            log?.Invoke("Сервер собран: " + classesDirectory);
        }
    }
}
