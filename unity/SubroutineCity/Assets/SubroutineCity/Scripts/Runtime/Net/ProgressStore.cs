using SubroutineCity.Core.Progress;
using UnityEngine;

namespace SubroutineCity.Net
{
    /// <summary>Сохранение прогресса в PlayerPrefs (формат — JSON из Core, версия 1).</summary>
    public static class ProgressStore
    {
        private const string Key = "SubroutineCity.Progress.v1";

        public static ProgressState Load()
        {
            return ProgressState.Deserialize(PlayerPrefs.GetString(Key, ""));
        }

        public static void Save(ProgressState state)
        {
            PlayerPrefs.SetString(Key, state.Serialize());
            PlayerPrefs.Save();
        }
    }
}
