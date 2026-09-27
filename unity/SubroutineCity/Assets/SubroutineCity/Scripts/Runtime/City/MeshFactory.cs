using System.Collections.Generic;
using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Процедурные меши. Вершины не разделяются между треугольниками: в UV2 записаны барицентрические
    /// координаты — шейдер рисует каркас без геометрического шейдера. UV0: x — доля периметра, y — высота в метрах.
    /// </summary>
    public static class MeshFactory
    {
        private sealed class Builder
        {
            public readonly List<Vector3> Vertices = new List<Vector3>();
            public readonly List<Vector3> Normals = new List<Vector3>();
            public readonly List<Vector2> Uvs = new List<Vector2>();
            public readonly List<Vector3> Bary = new List<Vector3>();
            public readonly List<int> Triangles = new List<int>();

            public void Triangle(Vector3 a, Vector3 b, Vector3 c, Vector2 ua, Vector2 ub, Vector2 uc)
            {
                Vector3 normal = Vector3.Cross(b - a, c - a).normalized;
                int start = Vertices.Count;
                Vertices.Add(a);
                Vertices.Add(b);
                Vertices.Add(c);
                Normals.Add(normal);
                Normals.Add(normal);
                Normals.Add(normal);
                Uvs.Add(ua);
                Uvs.Add(ub);
                Uvs.Add(uc);
                Bary.Add(new Vector3(1, 0, 0));
                Bary.Add(new Vector3(0, 1, 0));
                Bary.Add(new Vector3(0, 0, 1));
                Triangles.Add(start);
                Triangles.Add(start + 1);
                Triangles.Add(start + 2);
            }

            /// <summary>Четырёхугольник a-b-c-d по часовой стрелке, если смотреть снаружи.</summary>
            public void Quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Vector2 ua, Vector2 ub, Vector2 uc, Vector2 ud)
            {
                Triangle(a, b, c, ua, ub, uc);
                Triangle(a, c, d, ua, uc, ud);
            }

            public Mesh Build(string name)
            {
                var mesh = new Mesh { name = name };
                if (Vertices.Count > 65000) mesh.indexFormat = UnityEngine.Rendering.IndexFormat.UInt32;
                mesh.SetVertices(Vertices);
                mesh.SetNormals(Normals);
                mesh.SetUVs(0, Uvs);
                mesh.SetUVs(1, Bary);
                mesh.SetTriangles(Triangles, 0);
                mesh.RecalculateBounds();
                return mesh;
            }
        }

        /// <summary>Призма с правильным многоугольником в основании (4 стороны — башня, 24 — резервуар).</summary>
        public static Mesh Prism(float width, float depth, float height, int sides, float topScale = 1f, string name = "Prism")
        {
            var b = new Builder();
            AddPrism(b, width, depth, 0f, height, sides, topScale);
            return b.Build(name);
        }

        /// <summary>Ступенчатая башня: три яруса с уменьшающимся сечением.</summary>
        public static Mesh SteppedTower(float width, float depth, float height, string name = "SteppedTower")
        {
            var b = new Builder();
            float[] levels = { 0f, 0.45f, 0.78f, 1f };
            float[] scales = { 1f, 0.72f, 0.45f };
            for (int i = 0; i < 3; i++)
            {
                AddPrism(b, width * scales[i], depth * scales[i], levels[i] * height, levels[i + 1] * height, 4, 1f);
            }
            return b.Build(name);
        }

        private static void AddPrism(Builder b, float width, float depth, float y0, float y1, int sides, float topScale)
        {
            var bottom = new Vector3[sides];
            var top = new Vector3[sides];
            float offset = sides == 4 ? Mathf.PI / 4f : 0f;
            float rx = sides == 4 ? width / Mathf.Sqrt(2f) : width / 2f;
            float rz = sides == 4 ? depth / Mathf.Sqrt(2f) : depth / 2f;
            for (int i = 0; i < sides; i++)
            {
                float angle = offset + i * Mathf.PI * 2f / sides;
                bottom[i] = new Vector3(Mathf.Cos(angle) * rx, y0, Mathf.Sin(angle) * rz);
                top[i] = new Vector3(Mathf.Cos(angle) * rx * topScale, y1, Mathf.Sin(angle) * rz * topScale);
            }
            for (int i = 0; i < sides; i++)
            {
                int j = (i + 1) % sides;
                float u0 = i / (float)sides;
                float u1 = (i + 1) / (float)sides;
                b.Quad(bottom[i], top[i], top[j], bottom[j],
                    new Vector2(u0, y0), new Vector2(u0, y1), new Vector2(u1, y1), new Vector2(u1, y0));
            }
            var topCenter = new Vector3(0, y1, 0);
            for (int i = 0; i < sides; i++)
            {
                int j = (i + 1) % sides;
                b.Triangle(topCenter, top[j], top[i], new Vector2(0.5f, y1), new Vector2(0, y1), new Vector2(1, y1));
            }
        }

        /// <summary>Горизонтальный квадрат с нормалью вверх (земля).</summary>
        public static Mesh Ground(float size, int subdivisions)
        {
            var b = new Builder();
            float step = size / subdivisions;
            float half = size / 2f;
            for (int x = 0; x < subdivisions; x++)
            for (int z = 0; z < subdivisions; z++)
            {
                var a = new Vector3(-half + x * step, 0, -half + z * step);
                var c = a + new Vector3(step, 0, step);
                b.Quad(a, new Vector3(a.x, 0, c.z), c, new Vector3(c.x, 0, a.z), Vector2.zero, Vector2.up, Vector2.one, Vector2.right);
            }
            return b.Build("Ground");
        }

        /// <summary>Полусфера для купола файрвола (UV: x — долгота, y — широта).</summary>
        public static Mesh Dome(float radius, int segments = 48, int rings = 16)
        {
            var b = new Builder();
            for (int r = 0; r < rings; r++)
            {
                float lat0 = r / (float)rings * Mathf.PI / 2f;
                float lat1 = (r + 1) / (float)rings * Mathf.PI / 2f;
                for (int s = 0; s < segments; s++)
                {
                    float lon0 = s / (float)segments * Mathf.PI * 2f;
                    float lon1 = (s + 1) / (float)segments * Mathf.PI * 2f;
                    Vector3 p00 = Sphere(radius, lat0, lon0);
                    Vector3 p01 = Sphere(radius, lat0, lon1);
                    Vector3 p10 = Sphere(radius, lat1, lon0);
                    Vector3 p11 = Sphere(radius, lat1, lon1);
                    var u00 = new Vector2(s / (float)segments, r / (float)rings);
                    var u01 = new Vector2((s + 1) / (float)segments, r / (float)rings);
                    var u10 = new Vector2(s / (float)segments, (r + 1) / (float)rings);
                    var u11 = new Vector2((s + 1) / (float)segments, (r + 1) / (float)rings);
                    b.Quad(p00, p10, p11, p01, u00, u10, u11, u01);
                }
            }
            Mesh mesh = b.Build("Dome");
            // нормали наружу от центра — для френеля купола
            var normals = new List<Vector3>();
            mesh.GetVertices(b.Vertices);
            foreach (var v in b.Vertices) normals.Add(v.normalized);
            mesh.SetNormals(normals);
            return mesh;
        }

        private static Vector3 Sphere(float radius, float latitude, float longitude)
        {
            return new Vector3(Mathf.Cos(latitude) * Mathf.Cos(longitude), Mathf.Sin(latitude), Mathf.Cos(latitude) * Mathf.Sin(longitude)) * radius;
        }

        /// <summary>Объединение множества призм в один меш (фон-горизонт): один draw call.</summary>
        public static Mesh Skyline(int count, float innerRadius, float outerRadius, int seed)
        {
            var b = new Builder();
            var random = new System.Random(seed);
            for (int i = 0; i < count; i++)
            {
                float angle = (float)(random.NextDouble() * Mathf.PI * 2f);
                float distance = innerRadius + (float)random.NextDouble() * (outerRadius - innerRadius);
                float height = 12f + (float)(random.NextDouble() * random.NextDouble()) * 70f;
                float width = 5f + (float)random.NextDouble() * 9f;
                var center = new Vector3(Mathf.Cos(angle) * distance, 0, Mathf.Sin(angle) * distance);
                int before = b.Vertices.Count;
                AddPrism(b, width, width * (0.7f + (float)random.NextDouble() * 0.6f), 0f, height, 4, 0.85f + (float)random.NextDouble() * 0.15f);
                for (int v = before; v < b.Vertices.Count; v++) b.Vertices[v] += center;
            }
            return b.Build("Skyline");
        }
    }
}
