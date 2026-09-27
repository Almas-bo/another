using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;

namespace SubroutineCity.Core.Launch
{
    /// <summary>Найденный JDK: java и javac из одного каталога bin.</summary>
    public sealed class JdkInfo
    {
        public string Home;
        public string Java;
        public string Javac;
        public int MajorVersion;
        public string Source;

        public override string ToString() => "JDK " + MajorVersion + " (" + Source + "): " + Home;
    }

    /// <summary>
    /// Поиск подходящего JDK (не JRE: нужен javac) версии не ниже <see cref="RequiredMajor"/>.
    /// Порядок: управляемый JDK игры (.jdk/, общий со скриптами) → JAVA_HOME → PATH → стандартные каталоги ОС.
    /// </summary>
    public static class JdkLocator
    {
        public const int RequiredMajor = 21;
        private static readonly Regex VersionPattern = new Regex("version \"(\\d+)(?:\\.(\\d+))?", RegexOptions.Compiled);

        /// <summary>Мажорная версия из вывода «java -version»: «21.0.10» → 21, «1.8.0_392» → 8; -1, если не распознано.</summary>
        public static int ParseMajorVersion(string versionOutput)
        {
            if (string.IsNullOrEmpty(versionOutput)) return -1;
            Match match = VersionPattern.Match(versionOutput);
            if (!match.Success) return -1;
            int first = int.Parse(match.Groups[1].Value);
            if (first == 1 && match.Groups[2].Success) return int.Parse(match.Groups[2].Value);
            return first;
        }

        public static JdkInfo Find(HostPlatform platform, IEnumerable<string> managedRoots, Action<string> log = null)
        {
            foreach (var candidate in Candidates(platform, managedRoots))
            {
                JdkInfo jdk = Probe(candidate.Key, candidate.Value, platform, log);
                if (jdk != null) return jdk;
            }
            return null;
        }

        /// <summary>Проверяет каталог JDK (содержит bin/java и bin/javac).</summary>
        public static JdkInfo Probe(string home, string source, HostPlatform platform, Action<string> log = null)
        {
            if (string.IsNullOrEmpty(home)) return null;
            string java = Path.Combine(home, "bin", "java" + platform.ExecutableSuffix);
            string javac = Path.Combine(home, "bin", "javac" + platform.ExecutableSuffix);
            if (!File.Exists(java)) return null;
            if (!File.Exists(javac))
            {
                log?.Invoke("Пропущено (JRE без javac): " + home);
                return null;
            }
            ProcessResult result;
            try
            {
                result = ProcessRunner.Run(java, "-version", null, TimeSpan.FromSeconds(20));
            }
            catch (Exception e)
            {
                log?.Invoke("Не удалось запустить " + java + ": " + e.Message);
                return null;
            }
            int major = result.ExitCode == 0 ? ParseMajorVersion(result.Output) : -1;
            if (major < RequiredMajor)
            {
                log?.Invoke("Пропущено (версия " + (major < 0 ? "?" : major.ToString()) + " < " + RequiredMajor + "): " + home);
                return null;
            }
            return new JdkInfo { Home = home, Java = java, Javac = javac, MajorVersion = major, Source = source };
        }

        private static IEnumerable<KeyValuePair<string, string>> Candidates(HostPlatform platform, IEnumerable<string> managedRoots)
        {
            if (managedRoots != null)
            {
                foreach (string root in managedRoots)
                foreach (string home in JdkHomesUnder(root, platform, 4))
                    yield return new KeyValuePair<string, string>(home, "установлен игрой");
            }

            string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(javaHome)) yield return new KeyValuePair<string, string>(javaHome, "JAVA_HOME");

            string path = Environment.GetEnvironmentVariable("PATH") ?? "";
            foreach (string entry in path.Split(Path.PathSeparator))
            {
                if (string.IsNullOrWhiteSpace(entry)) continue;
                string java = Path.Combine(entry.Trim(), "java" + platform.ExecutableSuffix);
                if (!File.Exists(java)) continue;
                string resolved = ResolveHome(java);
                if (resolved != null) yield return new KeyValuePair<string, string>(resolved, "PATH");
            }

            foreach (string root in SystemRoots(platform))
            foreach (string home in JdkHomesUnder(root, platform, 3))
                yield return new KeyValuePair<string, string>(home, "система");
        }

        /// <summary>Каталоги JDK внутри root (включая macOS-раскладку Contents/Home), не глубже maxDepth.</summary>
        public static IEnumerable<string> JdkHomesUnder(string root, HostPlatform platform, int maxDepth)
        {
            var found = new List<string>();
            if (string.IsNullOrEmpty(root) || !Directory.Exists(root)) return found;
            Collect(root, platform, maxDepth, found);
            found.Sort(StringComparer.Ordinal);
            found.Reverse(); // более новые версии обычно сортируются позже по имени каталога
            return found;
        }

        private static void Collect(string directory, HostPlatform platform, int depth, List<string> found)
        {
            if (File.Exists(Path.Combine(directory, "bin", "java" + platform.ExecutableSuffix)))
            {
                found.Add(directory);
                return;
            }
            if (depth <= 0) return;
            string[] children;
            try
            {
                children = Directory.GetDirectories(directory);
            }
            catch (Exception)
            {
                return; // нет прав — пропускаем
            }
            foreach (string child in children)
            {
                string name = Path.GetFileName(child);
                if (name.StartsWith(".partial", StringComparison.Ordinal)) continue;
                Collect(child, platform, depth - 1, found);
            }
        }

        private static IEnumerable<string> SystemRoots(HostPlatform platform)
        {
            switch (platform.Os)
            {
                case HostOs.Windows:
                    string programFiles = Environment.GetEnvironmentVariable("ProgramFiles") ?? "C:\\Program Files";
                    return new[]
                    {
                        Path.Combine(programFiles, "Eclipse Adoptium"), Path.Combine(programFiles, "Java"),
                        Path.Combine(programFiles, "Microsoft"), Path.Combine(programFiles, "Zulu"),
                        Path.Combine(programFiles, "Amazon Corretto"), Path.Combine(programFiles, "BellSoft")
                    };
                case HostOs.MacOS:
                    return new[] { "/Library/Java/JavaVirtualMachines", "/opt/homebrew/opt", "/usr/local/opt" };
                default:
                    return new[] { "/usr/lib/jvm", "/usr/java", "/opt" };
            }
        }

        /// <summary>…/bin/java (возможно, символическая ссылка) → домашний каталог JDK.</summary>
        private static string ResolveHome(string java)
        {
            try
            {
                string target = java;
                var info = new FileInfo(java);
#if NET6_0_OR_GREATER
                if (info.LinkTarget != null)
                {
                    var resolved = info.ResolveLinkTarget(true);
                    if (resolved != null) target = resolved.FullName;
                }
#endif
                string bin = Path.GetDirectoryName(target);
                return bin == null ? null : Path.GetDirectoryName(bin);
            }
            catch (Exception)
            {
                return null;
            }
        }
    }
}
