using UnityEngine;

namespace SubroutineCity
{
    /// <summary>
    /// Автозапуск: игра собирается кодом при старте любой сцены — не нужны ни префабы, ни настройка сцены.
    /// Откройте проект, нажмите Play. Чтобы отключить автозапуск в своей сцене, добавьте на неё
    /// объект с именем «SubroutineCity.NoAutoBoot».
    /// </summary>
    public static class Bootstrap
    {
        public const string OptOutObjectName = "SubroutineCity.NoAutoBoot";

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        private static void Boot()
        {
            if (GameObject.Find(OptOutObjectName) != null) return;
            if (GameObject.Find(RootName) != null) return;
            new GameObject(RootName).AddComponent<GameRoot>();
        }

        private const string RootName = "Subroutine City — игра";
    }
}
