using System.Collections.Generic;
using System.IO;
using SubroutineCity.Core.Launch;
using UnityEngine;

namespace SubroutineCity.Net
{
    /// <summary>
    /// Unity-часть автозапуска сервера: где искать репозиторий, собранный сервер и куда ставить JDK.
    /// Сама логика (поиск/скачивание JDK, сборка, запуск) — в Core.Launch и покрыта тестами.
    /// </summary>
    public static class LocalServerLauncher
    {
        public static bool Supported =>
            Application.platform == RuntimePlatform.WindowsEditor || Application.platform == RuntimePlatform.WindowsPlayer
            || Application.platform == RuntimePlatform.OSXEditor || Application.platform == RuntimePlatform.OSXPlayer
            || Application.platform == RuntimePlatform.LinuxEditor || Application.platform == RuntimePlatform.LinuxPlayer;

        /// <summary>
        /// Корень репозитория с исходниками сервера: путь из настроек или репозиторий, в котором лежит этот Unity-проект
        /// (unity/SubroutineCity/Assets → ../../..). null — исходников рядом нет.
        /// </summary>
        public static string FindRepository(string configured)
        {
            var candidates = new List<string>();
            if (!string.IsNullOrWhiteSpace(configured)) candidates.Add(configured.Trim());
            candidates.Add(Path.Combine(Application.dataPath, "..", "..", ".."));
            foreach (string candidate in candidates)
            {
                try
                {
                    string full = Path.GetFullPath(candidate);
                    if (ServerBuilder.HasSources(full)) return full;
                }
                catch (System.Exception)
                {
                    // некорректный путь в настройках
                }
            }
            return null;
        }

        public static LaunchOptions Options(string configuredRepository, int port)
        {
            string repository = FindRepository(configuredRepository);
            string personalJdk = Path.Combine(Application.persistentDataPath, "jdk");
            var options = new LaunchOptions
            {
                Repository = repository,
                Port = port,
                // Без репозитория — готовый сервер из StreamingAssets (сборка игры) и JDK в данных пользователя.
                ClassesDirectory = repository == null ? Path.Combine(Application.streamingAssetsPath, "server", "classes") : null,
                ManagedJdkRoot = repository == null ? personalJdk : null
            };
            options.ExtraJdkRoots.Add(personalJdk);
            return options;
        }
    }
}
