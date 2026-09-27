using System.Runtime.InteropServices;

namespace SubroutineCity.Core.Launch
{
    public enum HostOs { Windows, MacOS, Linux }

    public enum HostArch { X64, Arm64 }

    /// <summary>Платформа хоста в терминах Adoptium API.</summary>
    public struct HostPlatform
    {
        public HostOs Os;
        public HostArch Arch;

        public HostPlatform(HostOs os, HostArch arch)
        {
            Os = os;
            Arch = arch;
        }

        public static HostPlatform Current()
        {
            HostOs os = RuntimeInformation.IsOSPlatform(OSPlatform.Windows) ? HostOs.Windows
                : RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? HostOs.MacOS : HostOs.Linux;
            HostArch arch = RuntimeInformation.OSArchitecture == Architecture.Arm64 ? HostArch.Arm64 : HostArch.X64;
            return new HostPlatform(os, arch);
        }

        public string ExecutableSuffix => Os == HostOs.Windows ? ".exe" : "";

        public string AdoptiumOs => Os == HostOs.Windows ? "windows" : Os == HostOs.MacOS ? "mac" : "linux";

        public string AdoptiumArch => Arch == HostArch.Arm64 ? "aarch64" : "x64";

        public bool UsesZip => Os == HostOs.Windows;

        public override string ToString() => AdoptiumOs + "/" + AdoptiumArch;
    }
}
