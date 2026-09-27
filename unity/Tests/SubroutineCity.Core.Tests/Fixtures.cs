using System.IO;
using NUnit.Framework;

namespace SubroutineCity.Core.Tests
{
    /// <summary>Эталонные ответы, записанные с настоящего сервера песочницы (см. README, раздел «Контракт API»).</summary>
    internal static class Fixtures
    {
        public static string Read(string name)
        {
            string path = Path.Combine(TestContext.CurrentContext.TestDirectory, "Fixtures", name);
            return File.ReadAllText(path);
        }
    }
}
