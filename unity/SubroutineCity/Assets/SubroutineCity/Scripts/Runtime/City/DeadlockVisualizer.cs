using System.Collections.Generic;
using SubroutineCity.Core.City;
using SubroutineCity.Core.Protocol;
using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Deadlock Visualizer: вокруг здания зависшего теста появляются «опоры потоков», между ними — цепи
    /// ожидания (поток → владелец нужного монитора). Цикл взаимной блокировки — яркие фиолетовые цепи
    /// с остановленным током; потоки, лишь ждущие участников цикла, — тусклые серые.
    /// </summary>
    public sealed class DeadlockVisualizer : MonoBehaviour
    {
        public struct Label
        {
            public Vector3 Position;
            public string Text;
            public bool InCycle;
        }

        private const int ArcSegments = 20;
        private readonly List<GameObject> _spawned = new List<GameObject>();
        private readonly List<Label> _labels = new List<Label>();

        public IReadOnlyList<Label> Labels => _labels;

        public void Show(BuildingView building, IList<ThreadSnapshot> threads)
        {
            Clear();
            if (building == null || threads == null || threads.Count == 0) return;
            var graph = new DeadlockGraph(threads);
            var involved = new List<string>(graph.Cycle);
            foreach (string waiting in graph.Waiting)
                if (!involved.Contains(waiting)) involved.Add(waiting);
            if (involved.Count == 0) return;

            var positions = new Dictionary<string, Vector3>();
            Vector3 center = building.transform.position;
            float radius = 9f;
            for (int i = 0; i < involved.Count; i++)
            {
                string name = involved[i];
                bool inCycle = graph.InCycle(name);
                float angle = i * Mathf.PI * 2f / involved.Count;
                float height = inCycle ? 7f : 4f;
                Vector3 basePosition = center + new Vector3(Mathf.Cos(angle), 0, Mathf.Sin(angle)) * (inCycle ? radius : radius + 5f);
                positions[name] = basePosition + Vector3.up * height;
                SpawnPylon(basePosition, height, inCycle);
                _labels.Add(new Label { Position = basePosition + Vector3.up * (height + 1.5f), Text = name, InCycle = inCycle });
            }
            foreach (var edge in graph.Edges)
            {
                if (!positions.ContainsKey(edge.Key) || !positions.ContainsKey(edge.Value)) continue;
                bool cycleEdge = graph.InCycle(edge.Key) && graph.InCycle(edge.Value);
                SpawnChain(positions[edge.Key], positions[edge.Value], cycleEdge);
            }
        }

        public void Clear()
        {
            foreach (var go in _spawned)
                if (go != null) Destroy(go);
            _spawned.Clear();
            _labels.Clear();
        }

        private void SpawnPylon(Vector3 position, float height, bool inCycle)
        {
            var go = new GameObject("Поток", typeof(MeshFilter), typeof(MeshRenderer));
            go.transform.SetParent(transform, false);
            go.transform.position = position;
            go.GetComponent<MeshFilter>().sharedMesh = MeshFactory.Prism(1.2f, 1.2f, height, 6, 0.6f, "Pylon");
            var renderer = go.GetComponent<MeshRenderer>();
            renderer.sharedMaterial = CityMaterials.SharedMaterial(CityMaterials.Hologram);
            var block = new MaterialPropertyBlock();
            Color color = inCycle ? Palette.Violet.ToColor() : Palette.Dim.ToColor();
            block.SetColor(CityMaterials.ColorId, color);
            block.SetColor(CityMaterials.DistrictColorId, color);
            block.SetFloat(CityMaterials.HeightId, height);
            block.SetFloat(CityMaterials.OpacityId, inCycle ? 0.9f : 0.5f);
            block.SetFloat(CityMaterials.PulseId, inCycle ? 1.5f : 0f);
            block.SetFloat(CityMaterials.FreezeId, inCycle ? 0.8f : 0.3f);
            block.SetFloat(CityMaterials.RiseId, 1f);
            renderer.SetPropertyBlock(block);
            _spawned.Add(go);
        }

        private void SpawnChain(Vector3 from, Vector3 to, bool cycleEdge)
        {
            var go = new GameObject("Цепь ожидания");
            go.transform.SetParent(transform, false);
            var line = go.AddComponent<LineRenderer>();
            Material material = CityMaterials.Create(CityMaterials.EnergyLine);
            material.SetColor(CityMaterials.ColorId, cycleEdge ? Palette.Violet.ToColor() : Palette.TextDim.ToColor());
            material.SetFloat(CityMaterials.StallId, cycleEdge ? 1f : 0.3f);
            material.SetFloat(CityMaterials.IntensityId, cycleEdge ? 3f : 1.2f);
            line.sharedMaterial = material;
            line.textureMode = LineTextureMode.Tile;
            line.widthMultiplier = cycleEdge ? 0.45f : 0.2f;
            line.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            line.positionCount = ArcSegments + 1;
            float lift = Vector3.Distance(from, to) * 0.35f;
            for (int i = 0; i <= ArcSegments; i++)
            {
                float t = i / (float)ArcSegments;
                Vector3 point = Vector3.Lerp(from, to, t) + Vector3.up * Mathf.Sin(t * Mathf.PI) * lift;
                line.SetPosition(i, point);
            }
            _spawned.Add(go);
        }
    }
}
