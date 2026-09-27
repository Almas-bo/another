using System.Collections.Generic;
using SubroutineCity.Core.City;
using SubroutineCity.Core.Protocol;
using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Весь город: небо, земля с тепловой картой, ядро JVM в центре, районы-уровни по кольцу, горизонт.
    /// Переводит ExecutionResult в визуальные события:
    /// <list type="bullet">
    /// <item>PASSED/FAILED/ERROR — состояния зданий (свечение, мерцание, глитч);</item>
    /// <item>TIMEOUT — иней и замерший трафик; DEADLOCK — цепи ожидания между потоками;</item>
    /// <item>MEMORY_LIMIT — перегрев тепловой карты; THREAD_LIMIT — шторм частиц;</item>
    /// <item>POLICY_VIOLATION — тревога купола-файрвола; COMPILATION_ERROR — обесточивание района.</item>
    /// </list>
    /// </summary>
    public sealed class CityView : MonoBehaviour
    {
        public struct WorldLabel
        {
            public Vector3 Position;
            public string Text;
            public Color Color;
            public bool Small;
        }

        private readonly Dictionary<string, DistrictView> _districts = new Dictionary<string, DistrictView>();
        private readonly Dictionary<string, TrafficFlow> _traffic = new Dictionary<string, TrafficFlow>();
        private readonly List<WorldLabel> _labels = new List<WorldLabel>();
        private Material _groundMaterial;
        private Material _domeMaterial;
        private Transform _dome;
        private MemoryHeatmap _heatmap;
        private DeadlockVisualizer _deadlocks;
        private float _power = 1f;
        private float _powerTarget = 1f;
        private float _alert;
        private float _alertTarget;
        private float _domeOpacity;
        private BuildingView _selected;
        private BuildingView _inspected;

        public CameraRig Rig { get; private set; }
        public CityPostFx PostFx { get; private set; }
        public IReadOnlyList<WorldLabel> Labels => _labels;
        public DeadlockVisualizer Deadlocks => _deadlocks;
        public BuildingView Selected => _selected;

        public void Init(Camera camera)
        {
            // Не «??»: в редакторе GetComponent возвращает «фальшивый null», который оператор ?? не распознаёт.
            Rig = camera.gameObject.GetComponent<CameraRig>();
            if (Rig == null) Rig = camera.gameObject.AddComponent<CameraRig>();
            Rig.Init(camera);
            PostFx = camera.gameObject.GetComponent<CityPostFx>();
            if (PostFx == null) PostFx = camera.gameObject.AddComponent<CityPostFx>();
            BuildSky();
            BuildGround();
            BuildCore();
            BuildSkyline();
            BuildDome();
            _deadlocks = new GameObject("Deadlock Visualizer").AddComponent<DeadlockVisualizer>();
            _deadlocks.transform.SetParent(transform, false);
        }

        // ------------------------------------------------------------------ районы

        public void BuildDistricts(IList<LevelSummary> levels)
        {
            foreach (var district in _districts.Values) Destroy(district.gameObject);
            foreach (var flow in _traffic.Values) Destroy(flow.gameObject);
            _districts.Clear();
            _traffic.Clear();
            for (int i = 0; i < levels.Count; i++)
            {
                var go = new GameObject("Район " + levels[i].Id);
                go.transform.SetParent(transform, false);
                var district = go.AddComponent<DistrictView>();
                district.Build(levels[i], i, levels.Count);
                _districts[levels[i].Id] = district;

                var flow = new GameObject("Трафик " + levels[i].Id).AddComponent<TrafficFlow>();
                flow.transform.SetParent(transform, false);
                Vector3 center = district.Center;
                flow.Build(center.normalized * 12f, center - center.normalized * district.Plan.Radius * 0.8f, district.ThemeColor);
                _traffic[levels[i].Id] = flow;
            }
            RebuildLabels();
        }

        public DistrictView District(string levelId)
        {
            return levelId != null && _districts.TryGetValue(levelId, out DistrictView district) ? district : null;
        }

        /// <summary>Пройденные уровни горят зелёным в обзоре города.</summary>
        public void MarkCompleted(string levelId)
        {
            DistrictView district = District(levelId);
            if (district == null) return;
            foreach (var building in district.Buildings) building.SetStatus(TestStatus.PASSED);
            district.SetRoadColor(Palette.Green.ToColor(), false);
        }

        public void FocusCity()
        {
            Rig.AutoRotate = true;
            Rig.LeftInset = 0f;
            Rig.Focus(Vector3.zero, 200f, pitch: 40f);
        }

        public void FocusDistrict(string levelId, float leftInset)
        {
            DistrictView district = District(levelId);
            if (district == null) return;
            Rig.AutoRotate = false;
            Rig.LeftInset = leftInset;
            float yaw = -district.Plan.Angle * Mathf.Rad2Deg - 90f;
            Rig.Focus(district.Center + Vector3.up * 4f, district.Plan.Radius * 2.6f + 20f, yaw, 34f);
        }

        public void FocusBuilding(BuildingView building)
        {
            if (building == null) return;
            Rig.Focus(building.transform.position + Vector3.up * building.Height * 0.5f, 42f);
        }

        // ------------------------------------------------------------------ результаты

        /// <summary>Запуск начат: район переходит в режим сканирования.</summary>
        public void BeginRun(string levelId)
        {
            DistrictView district = District(levelId);
            if (district == null) return;
            ClearEffects();
            district.SetScanning();
            if (_traffic.TryGetValue(levelId, out TrafficFlow flow)) flow.SetState(Palette.Cyan.ToColor(), 40f, false, false);
        }

        public void ApplyResult(string levelId, ExecutionResult result)
        {
            DistrictView district = District(levelId);
            if (district == null) return;
            ClearEffects();
            district.ResetStates();

            float glitch = 0f;
            foreach (var test in result.Tests)
            {
                BuildingView building = district.Find(test.Id);
                if (building == null) continue;
                building.SetStatus(test.Status);
                _heatmap.Stamp(building.transform.position, test);
                if (test.Status == TestStatus.ERROR || test.Status == TestStatus.SANDBOX_CRASH) glitch = Mathf.Max(glitch, 0.6f);
                if (test.Status == TestStatus.FAILED) glitch = Mathf.Max(glitch, 0.25f);
                if (test.Status == TestStatus.DEADLOCK) _deadlocks.Show(building, test.Threads);
            }

            switch (result.Status)
            {
                case ExecutionStatus.COMPILATION_ERROR:
                case ExecutionStatus.CONTRACT_VIOLATION:
                case ExecutionStatus.REJECTED:
                    _powerTarget = 0.25f;
                    foreach (var building in district.Buildings) building.SetMode(BuildingMode.Ghost, instant: false);
                    break;
                case ExecutionStatus.POLICY_VIOLATION:
                    _alertTarget = 1f;
                    PlaceDome(district);
                    foreach (var building in district.Buildings) building.SetMode(BuildingMode.Ghost, instant: false);
                    glitch = 0.8f;
                    break;
                case ExecutionStatus.MEMORY_LIMIT_EXCEEDED:
                case ExecutionStatus.THREAD_LIMIT_EXCEEDED:
                case ExecutionStatus.SANDBOX_FAILURE:
                    glitch = Mathf.Max(glitch, 0.7f);
                    break;
            }

            if (_traffic.TryGetValue(levelId, out TrafficFlow flow))
            {
                Color color = Palette.ForExecution(result.Status).ToColor();
                bool frozen = result.Status == ExecutionStatus.TIMEOUT || result.Status == ExecutionStatus.DEADLOCK;
                bool storm = result.Status == ExecutionStatus.THREAD_LIMIT_EXCEEDED;
                float cpuMs = result.Metrics.TotalCpuNanos / 1_000_000f;
                float rate = Mathf.Clamp(4f + Mathf.Log(1f + cpuMs) * 6f, 4f, 60f);
                flow.SetState(color, rate, frozen, storm);
                district.SetRoadColor(color, frozen);
            }
            if (glitch > 0f) PostFx.Kick(glitch);
        }

        /// <summary>Результат быстрой проверки: при ошибке компиляции район гаснет, при успехе снова под напряжением.</summary>
        public void ApplyCheck(string levelId, ExecutionResult check)
        {
            bool broken = check.Status == ExecutionStatus.COMPILATION_ERROR || check.Status == ExecutionStatus.CONTRACT_VIOLATION;
            _powerTarget = broken ? 0.45f : 1f;
        }

        public void ClearEffects()
        {
            _heatmap.Clear();
            _deadlocks.Clear();
            _powerTarget = 1f;
            _alertTarget = 0f;
        }

        // ------------------------------------------------------------------ выбор

        public BuildingView Pick(Ray ray)
        {
            return Physics.Raycast(ray, out RaycastHit hit, 2000f) ? hit.collider.GetComponent<BuildingView>() : null;
        }

        public void Select(BuildingView building)
        {
            if (_selected != null) _selected.Selected = false;
            _selected = building;
            if (_selected != null) _selected.Selected = true;
            RebuildLabels();
        }

        /// <summary>Режим инспектора: выбранное здание показывается каркасом.</summary>
        public void Inspect(BuildingView building)
        {
            if (_inspected != null) _inspected.Inspecting = false;
            _inspected = building;
            if (_inspected != null) _inspected.Inspecting = true;
        }

        // ------------------------------------------------------------------ кадр

        private void Update()
        {
            float dt = Time.deltaTime;
            _heatmap?.Tick(dt);
            _power = Mathf.MoveTowards(_power, _powerTarget, dt * 1.2f);
            _groundMaterial.SetFloat(CityMaterials.PowerId, _power);
            _alert = Mathf.MoveTowards(_alert, _alertTarget, dt * 1.5f);
            _domeOpacity = Mathf.MoveTowards(_domeOpacity, _alertTarget > 0f ? 0.6f : 0f, dt * 0.8f);
            _domeMaterial.SetFloat(CityMaterials.AlertId, _alert);
            _domeMaterial.SetFloat(CityMaterials.OpacityId, _domeOpacity);
            _dome.gameObject.SetActive(_domeOpacity > 0.005f);
        }

        private void RebuildLabels()
        {
            _labels.Clear();
            foreach (var district in _districts.Values)
            {
                _labels.Add(new WorldLabel
                {
                    Position = district.Center + Vector3.up * 30f,
                    Text = district.Level.Order + ". " + district.Level.Title,
                    Color = district.ThemeColor
                });
            }
            if (_selected != null)
            {
                _labels.Add(new WorldLabel
                {
                    Position = _selected.Top + Vector3.up * 2f,
                    Text = _selected.Title,
                    Color = Color.white,
                    Small = true
                });
            }
        }

        // ------------------------------------------------------------------ построение мира

        private void BuildSky()
        {
            var sky = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            sky.name = "Небо";
            Destroy(sky.GetComponent<Collider>());
            sky.transform.SetParent(transform, false);
            sky.transform.localScale = Vector3.one * 1800f;
            var renderer = sky.GetComponent<MeshRenderer>();
            renderer.sharedMaterial = CityMaterials.Create(CityMaterials.Sky);
            renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        }

        private void BuildGround()
        {
            var ground = new GameObject("Земля", typeof(MeshFilter), typeof(MeshRenderer));
            ground.transform.SetParent(transform, false);
            ground.GetComponent<MeshFilter>().sharedMesh = MeshFactory.Ground(420f, 1);
            _groundMaterial = CityMaterials.Create(CityMaterials.Ground);
            ground.GetComponent<MeshRenderer>().sharedMaterial = _groundMaterial;
            _heatmap = new MemoryHeatmap(_groundMaterial);
        }

        private void BuildCore()
        {
            var core = new GameObject("Ядро JVM", typeof(MeshFilter), typeof(MeshRenderer));
            core.transform.SetParent(transform, false);
            core.GetComponent<MeshFilter>().sharedMesh = MeshFactory.SteppedTower(12f, 12f, 38f, "Core");
            var renderer = core.GetComponent<MeshRenderer>();
            renderer.sharedMaterial = CityMaterials.SharedMaterial(CityMaterials.Hologram);
            var block = new MaterialPropertyBlock();
            block.SetColor(CityMaterials.ColorId, Palette.Cyan.ToColor());
            block.SetColor(CityMaterials.DistrictColorId, Palette.Cyan.ToColor());
            block.SetFloat(CityMaterials.HeightId, 38f);
            block.SetFloat(CityMaterials.OpacityId, 0.8f);
            block.SetFloat(CityMaterials.PulseId, 0.4f);
            block.SetFloat(CityMaterials.ScanId, 1f);
            block.SetFloat(CityMaterials.RiseId, 1f);
            renderer.SetPropertyBlock(block);

            var ring = new GameObject("Кольцо ядра");
            ring.transform.SetParent(transform, false);
            var line = ring.AddComponent<LineRenderer>();
            Material material = CityMaterials.Create(CityMaterials.EnergyLine);
            material.SetColor(CityMaterials.ColorId, Palette.Cyan.ToColor());
            material.SetFloat(CityMaterials.SpeedId, 0.4f);
            line.sharedMaterial = material;
            line.loop = true;
            line.textureMode = LineTextureMode.Tile;
            line.widthMultiplier = 0.6f;
            line.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            const int segments = 64;
            line.positionCount = segments;
            for (int i = 0; i < segments; i++)
            {
                float angle = i * Mathf.PI * 2f / segments;
                line.SetPosition(i, new Vector3(Mathf.Cos(angle) * 14f, 0.2f, Mathf.Sin(angle) * 14f));
            }
        }

        private void BuildSkyline()
        {
            var skyline = new GameObject("Горизонт", typeof(MeshFilter), typeof(MeshRenderer));
            skyline.transform.SetParent(transform, false);
            skyline.GetComponent<MeshFilter>().sharedMesh = MeshFactory.Skyline(220, 150f, 330f, 2077);
            var renderer = skyline.GetComponent<MeshRenderer>();
            renderer.sharedMaterial = CityMaterials.SharedMaterial(CityMaterials.Hologram);
            var block = new MaterialPropertyBlock();
            block.SetColor(CityMaterials.ColorId, new Color(0.15f, 0.25f, 0.45f));
            block.SetColor(CityMaterials.DistrictColorId, new Color(0.25f, 0.15f, 0.4f));
            block.SetFloat(CityMaterials.HeightId, 80f);
            block.SetFloat(CityMaterials.OpacityId, 0.35f);
            block.SetFloat(CityMaterials.RiseId, 1f);
            renderer.SetPropertyBlock(block);
        }

        private void BuildDome()
        {
            var dome = new GameObject("Файрвол", typeof(MeshFilter), typeof(MeshRenderer));
            dome.transform.SetParent(transform, false);
            dome.GetComponent<MeshFilter>().sharedMesh = MeshFactory.Dome(1f);
            _domeMaterial = CityMaterials.Create(CityMaterials.Dome);
            dome.GetComponent<MeshRenderer>().sharedMaterial = _domeMaterial;
            _dome = dome.transform;
            dome.SetActive(false);
        }

        private void PlaceDome(DistrictView district)
        {
            _dome.position = district.Center;
            _dome.localScale = Vector3.one * (district.Plan.Radius + 10f);
        }
    }
}
