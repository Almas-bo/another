using SubroutineCity.Core.Protocol;

namespace SubroutineCity.Core.City
{
    public struct Rgba
    {
        public float R;
        public float G;
        public float B;
        public float A;

        public Rgba(float r, float g, float b, float a = 1f)
        {
            R = r;
            G = g;
            B = b;
            A = a;
        }

        public static Rgba Hex(int rgb, float a = 1f)
        {
            return new Rgba(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, a);
        }
    }

    /// <summary>Визуальный режим здания — прямое отображение статуса теста в поведение шейдера.</summary>
    public enum BuildingMode
    {
        /// <summary>Тест ещё не запускался: тусклая голограмма.</summary>
        Idle,
        /// <summary>Идёт исполнение: бегущее сканирование.</summary>
        Scanning,
        Passed,
        /// <summary>Неверный результат: красное мерцание, сбой окон.</summary>
        Failed,
        /// <summary>Необработанное исключение: сильный глитч, смещение вершин.</summary>
        Crashed,
        /// <summary>Таймаут: янтарная «заморозка», остановка трафика.</summary>
        Frozen,
        /// <summary>Deadlock: фиолетовая блокировка, цепи между потоками.</summary>
        Locked,
        /// <summary>Память: перегрев, тепловая карта в максимуме.</summary>
        Overheated,
        /// <summary>Шторм потоков.</summary>
        Storm,
        /// <summary>Не запускался (песочница остановлена раньше): полупрозрачный каркас.</summary>
        Ghost
    }

    public sealed class VisualParams
    {
        public BuildingMode Mode;
        public Rgba Color;
        /// <summary>0..1 — сила глитча (смещение вершин, разрывы сканлайнов).</summary>
        public float Glitch;
        /// <summary>Частота пульсации, Гц (0 — без пульсации).</summary>
        public float Pulse;
        /// <summary>0..1 — «заморозка» (иней, остановка анимации окон).</summary>
        public float Freeze;
        /// <summary>0..1 — доля каркасного отображения (инспектор).</summary>
        public float Wireframe;
        /// <summary>Непрозрачность корпуса 0..1.</summary>
        public float Opacity;
    }

    /// <summary>Единая палитра и отображение статусов в визуальные параметры (используется и UI, и шейдерами).</summary>
    public static class Palette
    {
        public static readonly Rgba Cyan = Rgba.Hex(0x22E1FF);
        public static readonly Rgba Green = Rgba.Hex(0x3DFFA0);
        public static readonly Rgba Red = Rgba.Hex(0xFF3B5C);
        public static readonly Rgba Amber = Rgba.Hex(0xFFB020);
        public static readonly Rgba Violet = Rgba.Hex(0xA66BFF);
        public static readonly Rgba Magenta = Rgba.Hex(0xFF3DCB);
        public static readonly Rgba Orange = Rgba.Hex(0xFF6A2B);
        public static readonly Rgba Dim = Rgba.Hex(0x3A5A80);
        public static readonly Rgba Text = Rgba.Hex(0xD6E4FF);
        public static readonly Rgba TextDim = Rgba.Hex(0x7C8FB0);
        public static readonly Rgba Background = Rgba.Hex(0x05080F);
        public static readonly Rgba Panel = Rgba.Hex(0x0A1220, 0.92f);

        public static Rgba District(string theme)
        {
            switch (theme)
            {
                case "power": return Rgba.Hex(0xFFD23F);
                case "water": return Rgba.Hex(0x2FA8FF);
                case "warehouse": return Rgba.Hex(0xFF8A3D);
                case "telemetry": return Rgba.Hex(0x3DFFA0);
                case "traffic": return Rgba.Hex(0xFF3DCB);
                case "energy": return Rgba.Hex(0xA66BFF);
                default: return Cyan;
            }
        }

        public static Rgba ForTest(TestStatus status)
        {
            return ForMode(ModeFor(status)).Color;
        }

        public static Rgba ForExecution(ExecutionStatus status)
        {
            switch (status)
            {
                case ExecutionStatus.SUCCESS:
                case ExecutionStatus.COMPILED: return Green;
                case ExecutionStatus.TESTS_FAILED: return Red;
                case ExecutionStatus.TIMEOUT: return Amber;
                case ExecutionStatus.DEADLOCK: return Violet;
                case ExecutionStatus.MEMORY_LIMIT_EXCEEDED: return Orange;
                case ExecutionStatus.THREAD_LIMIT_EXCEEDED: return Magenta;
                case ExecutionStatus.POLICY_VIOLATION: return Red;
                default: return Amber;
            }
        }

        public static BuildingMode ModeFor(TestStatus status)
        {
            switch (status)
            {
                case TestStatus.PASSED: return BuildingMode.Passed;
                case TestStatus.FAILED: return BuildingMode.Failed;
                case TestStatus.ERROR: return BuildingMode.Crashed;
                case TestStatus.TIMEOUT: return BuildingMode.Frozen;
                case TestStatus.DEADLOCK: return BuildingMode.Locked;
                case TestStatus.MEMORY_LIMIT_EXCEEDED: return BuildingMode.Overheated;
                case TestStatus.THREAD_LIMIT_EXCEEDED: return BuildingMode.Storm;
                case TestStatus.SKIPPED: return BuildingMode.Ghost;
                case TestStatus.SANDBOX_CRASH: return BuildingMode.Crashed;
                default: return BuildingMode.Idle;
            }
        }

        public static VisualParams ForMode(BuildingMode mode)
        {
            switch (mode)
            {
                case BuildingMode.Scanning:
                    return new VisualParams { Mode = mode, Color = Cyan, Pulse = 2.5f, Opacity = 0.55f };
                case BuildingMode.Passed:
                    return new VisualParams { Mode = mode, Color = Green, Pulse = 0.25f, Opacity = 0.85f };
                case BuildingMode.Failed:
                    return new VisualParams { Mode = mode, Color = Red, Glitch = 0.35f, Pulse = 1.2f, Opacity = 0.8f };
                case BuildingMode.Crashed:
                    return new VisualParams { Mode = mode, Color = Red, Glitch = 1f, Pulse = 3f, Opacity = 0.75f };
                case BuildingMode.Frozen:
                    return new VisualParams { Mode = mode, Color = Amber, Freeze = 1f, Opacity = 0.8f };
                case BuildingMode.Locked:
                    return new VisualParams { Mode = mode, Color = Violet, Freeze = 0.6f, Pulse = 0.6f, Opacity = 0.85f };
                case BuildingMode.Overheated:
                    return new VisualParams { Mode = mode, Color = Orange, Glitch = 0.5f, Pulse = 4f, Opacity = 0.9f };
                case BuildingMode.Storm:
                    return new VisualParams { Mode = mode, Color = Magenta, Glitch = 0.8f, Pulse = 6f, Opacity = 0.8f };
                case BuildingMode.Ghost:
                    return new VisualParams { Mode = mode, Color = Dim, Wireframe = 1f, Opacity = 0.25f };
                default:
                    return new VisualParams { Mode = BuildingMode.Idle, Color = Dim, Pulse = 0.1f, Opacity = 0.45f };
            }
        }

        /// <summary>Нагрев ячейки тепловой карты 0..1 по аллокациям теста (логарифмическая шкала 1 КБ … 256 МБ).</summary>
        public static float Heat(long allocatedBytes, TestStatus status)
        {
            if (status == TestStatus.MEMORY_LIMIT_EXCEEDED) return 1f;
            if (allocatedBytes <= 1024) return 0f;
            double t = (System.Math.Log(allocatedBytes) - System.Math.Log(1024)) / (System.Math.Log(256.0 * 1024 * 1024) - System.Math.Log(1024));
            return (float)System.Math.Max(0, System.Math.Min(1, t));
        }
    }
}
