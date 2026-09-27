using System.Collections.Generic;
using SubroutineCity.Core.City;
using SubroutineCity.Core.Protocol;
using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>Район города = уровень кампании. Здания — тесты уровня.</summary>
    public sealed class DistrictView : MonoBehaviour
    {
        private readonly List<BuildingView> _buildings = new List<BuildingView>();
        private LineRenderer _road;
        private Material _roadMaterial;

        public LevelSummary Level { get; private set; }
        public DistrictPlan Plan { get; private set; }
        public Color ThemeColor { get; private set; }
        public IReadOnlyList<BuildingView> Buildings => _buildings;
        public Vector3 Center => Plan.Center.ToWorld();

        public void Build(LevelSummary level, int index, int count)
        {
            Level = level;
            Plan = CityLayout.PlanDistrict(index, count, level.Tests.Count, level.District);
            ThemeColor = Palette.District(level.District).ToColor();
            for (int i = 0; i < level.Tests.Count; i++)
            {
                BuildingSlot slot = Plan.Buildings[i];
                var go = new GameObject("Тест " + level.Tests[i].Id, typeof(MeshFilter), typeof(MeshRenderer));
                go.transform.SetParent(transform, false);
                go.transform.position = slot.Position.ToWorld();
                go.transform.rotation = Quaternion.Euler(0, -Plan.Angle * Mathf.Rad2Deg, 0);
                var building = go.AddComponent<BuildingView>();
                building.Init(this, level.Tests[i], slot, ThemeColor);
                _buildings.Add(building);
            }
            BuildRoad();
        }

        public BuildingView Find(string testId)
        {
            foreach (var building in _buildings)
                if (building.TestId == testId) return building;
            return null;
        }

        public void ResetStates()
        {
            foreach (var building in _buildings) building.ResetState();
        }

        public void SetScanning()
        {
            foreach (var building in _buildings) building.SetMode(BuildingMode.Scanning, instant: false);
        }

        /// <summary>Цвет «шины данных» от ядра к району отражает итог последнего запуска.</summary>
        public void SetRoadColor(Color color, bool stalled)
        {
            if (_roadMaterial == null) return;
            _roadMaterial.SetColor(CityMaterials.ColorId, color);
            _roadMaterial.SetFloat(CityMaterials.StallId, stalled ? 1f : 0f);
        }

        private void BuildRoad()
        {
            var go = new GameObject("Шина данных");
            go.transform.SetParent(transform, false);
            _road = go.AddComponent<LineRenderer>();
            _roadMaterial = CityMaterials.Create(CityMaterials.EnergyLine);
            _roadMaterial.SetColor(CityMaterials.ColorId, ThemeColor * 0.8f);
            _roadMaterial.SetFloat(CityMaterials.SpeedId, 0.6f);
            _road.sharedMaterial = _roadMaterial;
            _road.textureMode = LineTextureMode.Tile;
            _road.widthMultiplier = 1.2f;
            _road.numCapVertices = 2;
            _road.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _road.alignment = LineAlignment.TransformZ;
            go.transform.rotation = Quaternion.Euler(90, 0, 0);
            Vector3 center = Center;
            Vector3 from = center.normalized * 12f + Vector3.up * 0.05f;
            Vector3 to = center - center.normalized * (Plan.Radius * 0.9f) + Vector3.up * 0.05f;
            _road.positionCount = 2;
            _road.SetPosition(0, from);
            _road.SetPosition(1, to);
        }
    }
}
