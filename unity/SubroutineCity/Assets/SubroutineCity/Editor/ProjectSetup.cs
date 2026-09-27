using UnityEditor;
using UnityEngine;

namespace SubroutineCity.EditorTools
{
    /// <summary>
    /// Настройка проекта при загрузке редактора. Unity 2022.1+ по умолчанию запрещает HTTP без TLS, а сервер
    /// песочницы — локальный (http://127.0.0.1:8787). Разрешаем HTTP один раз и сообщаем об этом в консоли.
    /// </summary>
    [InitializeOnLoad]
    internal static class ProjectSetup
    {
        static ProjectSetup()
        {
#if UNITY_2022_1_OR_NEWER
            if (PlayerSettings.insecureHttpOption != InsecureHttpOption.AlwaysAllowed)
            {
                PlayerSettings.insecureHttpOption = InsecureHttpOption.AlwaysAllowed;
                Debug.Log("Subroutine City: разрешены HTTP-запросы (Player Settings → Allow downloads over HTTP) — "
                          + "клиент обращается к локальному серверу песочницы http://127.0.0.1:8787.");
            }
#endif
        }
    }
}
