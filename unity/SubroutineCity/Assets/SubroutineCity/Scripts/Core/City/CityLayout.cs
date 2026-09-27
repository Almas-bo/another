using System;
using System.Collections.Generic;

namespace SubroutineCity.Core.City
{
    public struct Vec2
    {
        public float X;
        public float Z;

        public Vec2(float x, float z)
        {
            X = x;
            Z = z;
        }

        public static Vec2 operator +(Vec2 a, Vec2 b) => new Vec2(a.X + b.X, a.Z + b.Z);
        public static Vec2 operator *(Vec2 a, float k) => new Vec2(a.X * k, a.Z * k);
        public float Length => (float)Math.Sqrt(X * X + Z * Z);
    }

    public enum BuildingShape
    {
        /// <summary>Прямоугольная башня.</summary>
        Tower,
        /// <summary>Ступенчатая башня (энергетика).</summary>
        SteppedTower,
        /// <summary>Цилиндрический резервуар (вода, телеметрия).</summary>
        Tank,
        /// <summary>Длинный низкий ангар (склады).</summary>
        Hangar
    }

    public sealed class BuildingSlot
    {
        public int Index;
        public Vec2 Position;
        public float Width;
        public float Depth;
        public float Height;
        public BuildingShape Shape;
    }

    public sealed class DistrictPlan
    {
        public int Index;
        public Vec2 Center;
        /// <summary>Угол направления от ядра города к району, радианы.</summary>
        public float Angle;
        public float Radius;
        public List<BuildingSlot> Buildings = new List<BuildingSlot>();
    }

    /// <summary>
    /// Детерминированная раскладка города: ядро (JVM) в центре, районы-уровни по кольцу, здания-тесты
    /// сеткой внутри района. Чистая математика — одинаковый результат при каждом запуске и в тестах.
    /// </summary>
    public static class CityLayout
    {
        public const float RingRadius = 70f;
        public const float Spacing = 9f;

        public static DistrictPlan PlanDistrict(int districtIndex, int districtCount, int testCount, string theme)
        {
            if (districtCount <= 0) throw new ArgumentOutOfRangeException(nameof(districtCount));
            float angle = (float)(2 * Math.PI * districtIndex / districtCount - Math.PI / 2);
            var plan = new DistrictPlan
            {
                Index = districtIndex,
                Angle = angle,
                Center = new Vec2((float)Math.Cos(angle) * RingRadius, (float)Math.Sin(angle) * RingRadius)
            };
            BuildingShape shape = ShapeFor(theme);
            int columns = Math.Max(1, (int)Math.Ceiling(Math.Sqrt(Math.Max(1, testCount))));
            int rows = (int)Math.Ceiling(testCount / (double)columns);
            var random = new Random(districtIndex * 7919 + testCount * 31 + (theme ?? "").Length);
            for (int i = 0; i < testCount; i++)
            {
                int row = i / columns;
                int column = i % columns;
                float x = (column - (columns - 1) / 2f) * Spacing;
                float z = (row - (rows - 1) / 2f) * Spacing;
                // локальные оси района: «вперёд» — от ядра наружу
                var local = Rotate(new Vec2(x, z), angle + (float)Math.PI / 2);
                float height = shape == BuildingShape.Hangar ? 4f + (float)random.NextDouble() * 2f
                    : 8f + (float)random.NextDouble() * 14f + (i % 3) * 3f;
                plan.Buildings.Add(new BuildingSlot
                {
                    Index = i,
                    Position = plan.Center + local,
                    Width = shape == BuildingShape.Hangar ? 7f : 4.5f + (float)random.NextDouble() * 1.5f,
                    Depth = shape == BuildingShape.Hangar ? 4.5f : 4.5f + (float)random.NextDouble() * 1.5f,
                    Height = height,
                    Shape = shape
                });
            }
            plan.Radius = Math.Max(columns, rows) * Spacing * 0.75f + 6f;
            return plan;
        }

        public static BuildingShape ShapeFor(string theme)
        {
            switch (theme)
            {
                case "power":
                case "energy": return BuildingShape.SteppedTower;
                case "water":
                case "telemetry": return BuildingShape.Tank;
                case "warehouse": return BuildingShape.Hangar;
                default: return BuildingShape.Tower;
            }
        }

        public static Vec2 Rotate(Vec2 v, float angle)
        {
            float cos = (float)Math.Cos(angle);
            float sin = (float)Math.Sin(angle);
            return new Vec2(v.X * cos - v.Z * sin, v.X * sin + v.Z * cos);
        }
    }
}
