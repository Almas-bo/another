using System;
using System.Collections;
using System.Text;
using SubroutineCity.Core.Protocol;
using UnityEngine;
using UnityEngine.Networking;

namespace SubroutineCity.Net
{
    /// <summary>
    /// HTTP-клиент API v1 на UnityWebRequest (работает на всех платформах Unity, без сторонних библиотек).
    /// Все методы — корутины с колбэками; парсинг JSON выполняется в Core и покрыт тестами.
    /// </summary>
    public sealed class ApiClient
    {
        private const int HealthTimeoutSeconds = 3;
        private const int ReadTimeoutSeconds = 15;
        private const int RunTimeoutSeconds = 120;
        private const int DebugTimeoutSeconds = 240;

        public ApiClient(string baseUrl)
        {
            BaseUrl = baseUrl;
        }

        public string BaseUrl { get; set; }

        public IEnumerator Health(Action<bool> done)
        {
            yield return Send("GET", ApiRoutes.Health, null, HealthTimeoutSeconds, _ => done(true), _ => done(false));
        }

        public IEnumerator Levels(Action<System.Collections.Generic.List<LevelSummary>> ok, Action<ApiError> fail)
        {
            yield return Send("GET", ApiRoutes.Levels, null, ReadTimeoutSeconds, body => Parse(() => ModelParser.ParseLevels(body), ok, fail), fail);
        }

        public IEnumerator Level(string id, Action<LevelDetail> ok, Action<ApiError> fail)
        {
            yield return Send("GET", ApiRoutes.Level(id), null, ReadTimeoutSeconds, body => Parse(() => ModelParser.ParseLevel(body), ok, fail), fail);
        }

        public IEnumerator Run(string levelId, string code, Action<ExecutionResult> ok, Action<ApiError> fail)
        {
            yield return Send("POST", ApiRoutes.Run(levelId), ApiRoutes.CodeBody(code), RunTimeoutSeconds,
                body => Parse(() => ModelParser.ParseRunResponse(body), ok, fail), fail);
        }

        public IEnumerator Check(string levelId, string code, Action<ExecutionResult> ok, Action<ApiError> fail)
        {
            yield return Send("POST", ApiRoutes.Check(levelId), ApiRoutes.CodeBody(code), RunTimeoutSeconds,
                body => Parse(() => ModelParser.ParseRunResponse(body), ok, fail), fail);
        }

        public IEnumerator Debug(string levelId, string code, string testId, Action<DebugResult> ok, Action<ApiError> fail)
        {
            yield return Send("POST", ApiRoutes.Debug(levelId), ApiRoutes.DebugBody(code, testId), DebugTimeoutSeconds,
                body => Parse(() => ModelParser.ParseDebugResponse(body), ok, fail), fail);
        }

        private IEnumerator Send(string method, string path, string jsonBody, int timeoutSeconds, Action<string> ok, Action<ApiError> fail)
        {
            string url = BaseUrl.TrimEnd('/') + path;
            using (var request = new UnityWebRequest(url, method))
            {
                request.downloadHandler = new DownloadHandlerBuffer();
                if (jsonBody != null)
                {
                    request.uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(jsonBody));
                    request.SetRequestHeader("Content-Type", "application/json; charset=utf-8");
                }
                request.timeout = timeoutSeconds;
                UnityWebRequestAsyncOperation operation = null;
                string startError = null;
                try
                {
                    operation = request.SendWebRequest();
                }
                catch (InvalidOperationException e)
                {
                    // Unity 2022+: «Insecure connection not allowed» — HTTP запрещён настройками проекта.
                    startError = e.Message;
                }
                if (startError != null)
                {
                    fail(new ApiError(0, "http_blocked", "Unity запретил HTTP-запрос (" + startError + "). Включите "
                        + "Project Settings → Player → Other Settings → Allow downloads over HTTP = Always allowed."));
                    yield break;
                }
                yield return operation;

                string body = request.downloadHandler != null ? request.downloadHandler.text : "";
                if (request.result == UnityWebRequest.Result.ConnectionError)
                {
                    fail(new ApiError(0, "unreachable", "Сервер песочницы недоступен (" + request.error + ")"));
                    yield break;
                }
                long status = request.responseCode;
                if (status >= 200 && status < 300)
                {
                    ok(body);
                }
                else
                {
                    fail(ModelParser.ParseError((int)status, body));
                }
            }
        }

        private static void Parse<T>(Func<T> parse, Action<T> ok, Action<ApiError> fail)
        {
            T value;
            try
            {
                value = parse();
            }
            catch (Exception e)
            {
                fail(new ApiError(200, "bad_response", "Не удалось разобрать ответ сервера: " + e.Message));
                return;
            }
            ok(value);
        }
    }
}
