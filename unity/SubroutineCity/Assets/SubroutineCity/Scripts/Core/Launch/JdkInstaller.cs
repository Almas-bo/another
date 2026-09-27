using System;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace SubroutineCity.Core.Launch
{
    /// <summary>
    /// Установка Eclipse Temurin 21 (сборка OpenJDK от Adoptium) в управляемый каталог игры.
    /// Архив скачивается по официальному API Adoptium; контрольная сумма берётся из файла «.sha256.txt»,
    /// который публикуется рядом с каждым архивом, и сверяется обязательно — без неё установка отменяется.
    /// Распаковка идёт во временный каталог и переносится на место только целиком.
    /// </summary>
    public sealed class JdkInstaller
    {
        public const string OverrideUrlVariable = "SUBROUTINE_JDK_URL";

        private readonly HttpClient _http;

        public JdkInstaller(HttpClient http = null)
        {
            _http = http ?? new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        }

        public static string AdoptiumUrl(HostPlatform platform)
        {
            return "https://api.adoptium.net/v3/binary/latest/" + JdkLocator.RequiredMajor + "/ga/" + platform.AdoptiumOs
                   + "/" + platform.AdoptiumArch + "/jdk/hotspot/normal/eclipse";
        }

        /// <summary>URL архива: переопределение из окружения (зеркало, офлайн-сервер) или Adoptium.</summary>
        public static string SourceUrl(HostPlatform platform)
        {
            string custom = Environment.GetEnvironmentVariable(OverrideUrlVariable);
            return string.IsNullOrWhiteSpace(custom) ? AdoptiumUrl(platform) : custom.Trim();
        }

        /// <summary>
        /// Скачивает, проверяет и распаковывает JDK в <paramref name="managedRoot"/>.
        /// </summary>
        /// <param name="progress">доля скачанного 0..1 (или -1, если размер неизвестен)</param>
        /// <returns>каталог установленного JDK</returns>
        public async Task<string> InstallAsync(HostPlatform platform, string managedRoot, Action<float> progress,
                                               Action<string> log, CancellationToken cancel)
        {
            Directory.CreateDirectory(managedRoot);
            string url = SourceUrl(platform);
            string archive = Path.Combine(managedRoot, ".download" + (platform.UsesZip ? ".zip" : ".tar.gz"));
            string partial = Path.Combine(managedRoot, ".partial-" + Guid.NewGuid().ToString("N"));
            try
            {
                log?.Invoke("Скачивание JDK: " + url);
                Uri finalUri = await DownloadAsync(url, archive, progress, cancel).ConfigureAwait(false);
                log?.Invoke("Источник: " + finalUri);

                string expected = await ExpectedChecksumAsync(finalUri, cancel).ConfigureAwait(false);
                string actual = Sha256(archive);
                if (!string.Equals(expected, actual, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("Контрольная сумма JDK не совпала: ожидалась " + expected + ", получена " + actual);
                log?.Invoke("SHA-256 совпадает: " + actual);

                Directory.CreateDirectory(partial);
                Extract(platform, archive, partial);
                string home = FindSingleHome(partial, platform);
                // Архив содержит один каталог верхнего уровня (jdk-21.0.x+y); на macOS JDK внутри него в Contents/Home.
                string topLevel = TopLevelDirectory(partial, home);
                string target = Path.Combine(managedRoot, Path.GetFileName(topLevel));
                if (Directory.Exists(target)) Directory.Delete(target, true);
                Directory.Move(topLevel, target);
                string installed = Path.Combine(target, RelativeTo(topLevel, home));
                log?.Invoke("JDK установлен: " + installed);
                return installed;
            }
            finally
            {
                TryDelete(archive);
                if (Directory.Exists(partial))
                {
                    try { Directory.Delete(partial, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
                }
            }
        }

        private async Task<Uri> DownloadAsync(string url, string file, Action<float> progress, CancellationToken cancel)
        {
            using (HttpResponseMessage response = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false))
            {
                response.EnsureSuccessStatusCode();
                long total = response.Content.Headers.ContentLength ?? -1;
                using (Stream source = await response.Content.ReadAsStreamAsync().ConfigureAwait(false))
                using (var target = new FileStream(file, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
                {
                    var buffer = new byte[1 << 16];
                    long received = 0;
                    int read;
                    while ((read = await source.ReadAsync(buffer, 0, buffer.Length, cancel).ConfigureAwait(false)) > 0)
                    {
                        await target.WriteAsync(buffer, 0, read, cancel).ConfigureAwait(false);
                        received += read;
                        progress?.Invoke(total > 0 ? (float)received / total : -1f);
                    }
                }
                return response.RequestMessage?.RequestUri ?? new Uri(url);
            }
        }

        /// <summary>Temurin публикует «&lt;архив&gt;.sha256.txt» рядом с архивом: «&lt;hex&gt;  &lt;имя&gt;».</summary>
        private async Task<string> ExpectedChecksumAsync(Uri archiveUri, CancellationToken cancel)
        {
            string checksumUrl = archiveUri.GetLeftPart(UriPartial.Path) + ".sha256.txt";
            using (HttpResponseMessage response = await _http.GetAsync(checksumUrl, cancel).ConfigureAwait(false))
            {
                if (!response.IsSuccessStatusCode)
                    throw new InvalidDataException("Нет контрольной суммы для JDK (" + checksumUrl + ", HTTP " + (int)response.StatusCode
                                                   + "). Установка без проверки запрещена.");
                string text = (await response.Content.ReadAsStringAsync().ConfigureAwait(false)).Trim();
                string hex = text.Split(new[] { ' ', '\t', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries)[0];
                if (hex.Length != 64) throw new InvalidDataException("Некорректная контрольная сумма: " + text);
                return hex;
            }
        }

        public static string Sha256(string file)
        {
            using (SHA256 sha = SHA256.Create())
            using (FileStream stream = File.OpenRead(file))
            {
                byte[] hash = sha.ComputeHash(stream);
                return BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
            }
        }

        private static void Extract(HostPlatform platform, string archive, string destination)
        {
            if (platform.UsesZip)
            {
                ZipFile.ExtractToDirectory(archive, destination);
                return;
            }
            // tar есть на macOS и во всех дистрибутивах Linux; он сохраняет права на исполнение
            ProcessResult result = ProcessRunner.Run("tar", "-xzf " + ProcessRunner.Quote(archive) + " -C " + ProcessRunner.Quote(destination),
                null, TimeSpan.FromMinutes(10));
            if (result.ExitCode != 0) throw new IOException("Не удалось распаковать JDK: " + result.Output);
        }

        private static string FindSingleHome(string root, HostPlatform platform)
        {
            foreach (string home in JdkLocator.JdkHomesUnder(root, platform, 4)) return home;
            throw new InvalidDataException("В архиве нет bin/java — это не JDK.");
        }

        /// <summary>Каталог первого уровня внутри распаковки, содержащий JDK (jdk-21…+…).</summary>
        private static string TopLevelDirectory(string root, string home)
        {
            string relative = RelativeTo(root, home);
            string first = relative.Split(new[] { Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar }, StringSplitOptions.RemoveEmptyEntries)[0];
            return Path.Combine(root, first);
        }

        private static string RelativeTo(string root, string path)
        {
            string fullRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            string fullPath = Path.GetFullPath(path);
            return fullPath.Length > fullRoot.Length ? fullPath.Substring(fullRoot.Length) : "";
        }

        private static void TryDelete(string file)
        {
            try
            {
                if (File.Exists(file)) File.Delete(file);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }
}
