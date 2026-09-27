using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using NUnit.Framework;
using SubroutineCity.Core.Launch;

namespace SubroutineCity.Core.Tests
{
    public class JdkVersionTests
    {
        [TestCase("openjdk version \"21.0.10\" 2026-01-20\nOpenJDK Runtime Environment", 21)]
        [TestCase("java version \"1.8.0_392\"", 8)]
        [TestCase("openjdk version \"17\" 2021-09-14", 17)]
        [TestCase("Picked up JAVA_TOOL_OPTIONS: -Dx\nopenjdk version \"23-ea\"", 23)]
        [TestCase("The operation couldn’t be completed. Unable to locate a Java Runtime.", -1)]
        [TestCase("", -1)]
        public void ParsesMajorVersion(string output, int expected)
        {
            Assert.That(JdkLocator.ParseMajorVersion(output), Is.EqualTo(expected));
        }

        [Test]
        public void AdoptiumUrlMatchesPlatform()
        {
            Assert.That(JdkInstaller.AdoptiumUrl(new HostPlatform(HostOs.MacOS, HostArch.Arm64)),
                Is.EqualTo("https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"));
            Assert.That(new HostPlatform(HostOs.Windows, HostArch.X64).UsesZip, Is.True);
        }
    }

    /// <summary>Установка JDK через локальный HTTP-сервер с фиктивным архивом (формат и .sha256.txt как у Temurin).</summary>
    [NonParallelizable]
    public class JdkInstallerTests
    {
        private string _work;
        private HttpListener _listener;
        private byte[] _archive;
        private string _checksum;
        private int _port;

        [SetUp]
        public void SetUp()
        {
            if (HostPlatform.Current().Os == HostOs.Windows) Assert.Ignore("Фиктивный JDK собирается shell-скриптами");
            _work = Path.Combine(Path.GetTempPath(), "sc-jdk-" + Guid.NewGuid().ToString("N"));
            string bin = Path.Combine(_work, "src", "jdk-21.0.4+7", "bin");
            Directory.CreateDirectory(bin);
            File.WriteAllText(Path.Combine(bin, "java"), "#!/bin/sh\necho 'openjdk version \"21.0.4\" 2024-07-16' >&2\n");
            File.WriteAllText(Path.Combine(bin, "javac"), "#!/bin/sh\nexit 0\n");
            ProcessRunner.Run("chmod", "+x java javac", bin, TimeSpan.FromSeconds(10));
            string tarball = Path.Combine(_work, "jdk.tar.gz");
            var tar = ProcessRunner.Run("tar", "-czf " + tarball + " -C " + Path.Combine(_work, "src") + " jdk-21.0.4+7", null, TimeSpan.FromSeconds(30));
            Assert.That(tar.ExitCode, Is.EqualTo(0), tar.Output);
            _archive = File.ReadAllBytes(tarball);
            _checksum = JdkInstaller.Sha256(tarball) + "  OpenJDK21U-jdk_x64_linux_hotspot.tar.gz";

            _port = FreePort();
            _listener = new HttpListener();
            _listener.Prefixes.Add("http://127.0.0.1:" + _port + "/");
            _listener.Start();
            Task.Run(Serve);
            Environment.SetEnvironmentVariable(JdkInstaller.OverrideUrlVariable, "http://127.0.0.1:" + _port + "/jdk.tar.gz");
        }

        [TearDown]
        public void TearDown()
        {
            Environment.SetEnvironmentVariable(JdkInstaller.OverrideUrlVariable, null);
            try { _listener?.Stop(); } catch (ObjectDisposedException) { }
            if (_work != null && Directory.Exists(_work)) Directory.Delete(_work, true);
        }

        private void Serve()
        {
            while (_listener.IsListening)
            {
                HttpListenerContext context;
                try
                {
                    context = _listener.GetContext();
                }
                catch (Exception)
                {
                    return;
                }
                try
                {
                    string path = context.Request.Url.AbsolutePath;
                    string checksum = _checksum;
                    byte[] body = path == "/jdk.tar.gz" ? _archive
                        : path == "/jdk.tar.gz.sha256.txt" && checksum != null ? System.Text.Encoding.ASCII.GetBytes(checksum) : null;
                    context.Response.StatusCode = body == null ? 404 : 200;
                    if (body != null)
                    {
                        context.Response.ContentLength64 = body.Length;
                        context.Response.OutputStream.Write(body, 0, body.Length);
                    }
                }
                finally
                {
                    context.Response.Close();
                }
            }
        }

        [Test]
        public async Task InstallsVerifiesAndLocatesJdk()
        {
            string managed = Path.Combine(_work, ".jdk");
            float lastProgress = 0;
            string home = await new JdkInstaller(Http()).InstallAsync(HostPlatform.Current(), managed, p => lastProgress = p, null, CancellationToken.None);
            Assert.That(lastProgress, Is.EqualTo(1f));
            Assert.That(home, Does.EndWith("jdk-21.0.4+7"));
            JdkInfo jdk = JdkLocator.Find(HostPlatform.Current(), new[] { managed });
            Assert.That(jdk, Is.Not.Null);
            Assert.That(jdk.MajorVersion, Is.EqualTo(21));
            Assert.That(jdk.Source, Is.EqualTo("установлен игрой"));
            Assert.That(Directory.GetFileSystemEntries(managed), Has.Length.EqualTo(1), "Во временных файлах ничего не осталось");
        }

        [Test]
        public void RejectsChecksumMismatch()
        {
            _checksum = new string('0', 64) + "  подделка.tar.gz";
            string managed = Path.Combine(_work, ".jdk");
            var error = Assert.ThrowsAsync<InvalidDataException>(() =>
                new JdkInstaller(Http()).InstallAsync(HostPlatform.Current(), managed, null, null, CancellationToken.None));
            Assert.That(error.Message, Does.Contain("Контрольная сумма"));
            JdkInfo found = JdkLocator.Find(HostPlatform.Current(), new[] { managed });
            Assert.That(found == null || found.Source != "установлен игрой", "Отвергнутый JDK не должен быть найден");
            Assert.That(Directory.GetFileSystemEntries(managed), Is.Empty, "Непроверенный архив не должен остаться на диске");
        }

        [Test]
        public void RefusesToInstallWithoutChecksum()
        {
            _checksum = null;
            var error = Assert.ThrowsAsync<InvalidDataException>(() =>
                new JdkInstaller(Http()).InstallAsync(HostPlatform.Current(), Path.Combine(_work, ".jdk"), null, null, CancellationToken.None));
            Assert.That(error.Message, Does.Contain("без проверки запрещена"));
        }

        private static System.Net.Http.HttpClient Http() => new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(20) };

        internal static int FreePort()
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            int port = ((IPEndPoint)listener.LocalEndpoint).Port;
            listener.Stop();
            return port;
        }
    }

    /// <summary>Реальная сборка и запуск сервера из исходников репозитория (нужен JDK 21 на машине).</summary>
    [NonParallelizable]
    public class ServerLaunchTests
    {
        private static string Repository()
        {
            var dir = new DirectoryInfo(TestContext.CurrentContext.TestDirectory);
            while (dir != null && !Directory.Exists(Path.Combine(dir.FullName, ServerBuilder.SourcesRoot))) dir = dir.Parent;
            return dir?.FullName;
        }

        private static JdkInfo RequireJdk()
        {
            JdkInfo jdk = JdkLocator.Find(HostPlatform.Current(), Array.Empty<string>());
            if (jdk == null) Assert.Ignore("На машине нет JDK 21");
            return jdk;
        }

        [Test]
        public void BuildsIncrementally()
        {
            JdkInfo jdk = RequireJdk();
            string repository = Repository();
            string classes = Path.Combine(Path.GetTempPath(), "sc-classes-" + Guid.NewGuid().ToString("N"), "classes");
            try
            {
                Assert.That(ServerBuilder.IsStale(repository, classes), Is.True);
                ServerBuilder.Build(jdk, repository, classes, null);
                Assert.That(File.Exists(Path.Combine(classes, "city", "subroutine", "server", "GameServer.class")));
                Assert.That(ServerBuilder.IsStale(repository, classes), Is.False);
                File.SetLastWriteTimeUtc(Path.Combine(classes, ServerBuilder.StampFile), DateTime.UtcNow.AddYears(-30));
                Assert.That(ServerBuilder.IsStale(repository, classes), Is.True, "Исходник новее метки — нужна пересборка");
            }
            finally
            {
                Directory.Delete(Path.GetDirectoryName(classes), true);
            }
        }

        [Test]
        public async Task PipelineBuildsStartsAndStopsServer()
        {
            RequireJdk();
            string root = Path.Combine(Path.GetTempPath(), "sc-pipeline-" + Guid.NewGuid().ToString("N"));
            var options = new LaunchOptions
            {
                Repository = Repository(),
                ClassesDirectory = Path.Combine(root, "classes"),
                ManagedJdkRoot = Path.Combine(root, ".jdk"),
                Port = JdkInstallerTests.FreePort()
            };
            try
            {
                using (var pipeline = new LaunchPipeline(options))
                {
                    await pipeline.RunAsync(CancellationToken.None);
                    Assert.That(pipeline.Stage, Is.EqualTo(LaunchStage.Running), pipeline.Error + "\n" + pipeline.ServerLog);
                    Assert.That(pipeline.ServerRunning, Is.True);
                    Assert.That(pipeline.ServerLog, Does.Contain("Сборка сервера"));
                }
                using (var client = new System.Net.Http.HttpClient())
                {
                    Assert.ThrowsAsync<System.Net.Http.HttpRequestException>(() =>
                        client.GetStringAsync("http://127.0.0.1:" + options.Port + "/api/v1/health"), "После Dispose сервер остановлен");
                }
            }
            finally
            {
                if (Directory.Exists(root)) Directory.Delete(root, true);
            }
        }
    }
}
