using SubroutineCity.Core.City;
using SubroutineCity.Core.Protocol;
using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Здание = тест уровня. Состояние теста задаёт целевые параметры шейдера, здание плавно к ним переходит.
    /// Один общий материал на весь город, параметры — через MaterialPropertyBlock (без лишних материалов и draw-call-ов).
    /// </summary>
    [RequireComponent(typeof(MeshFilter), typeof(MeshRenderer))]
    public sealed class BuildingView : MonoBehaviour
    {
        private const float Blend = 4f;

        private MaterialPropertyBlock _block;
        private MeshRenderer _renderer;
        private VisualParams _target;
        private Color _color;
        private float _glitch;
        private float _pulse;
        private float _freeze;
        private float _wire;
        private float _opacity;
        private float _scan;
        private float _rise;
        private float _selected;
        private float _flash;

        public string TestId { get; private set; }
        public string Title { get; private set; }
        public DistrictView District { get; private set; }
        public float Height { get; private set; }
        public TestStatus Status { get; private set; } = TestStatus.Unknown;
        public bool Selected { get; set; }
        public bool Inspecting { get; set; }

        public Vector3 Top => transform.position + Vector3.up * Height;

        public void Init(DistrictView district, TestInfo test, BuildingSlot slot, Color districtColor)
        {
            District = district;
            TestId = test.Id;
            Title = test.Title;
            Height = slot.Height;
            _renderer = GetComponent<MeshRenderer>();
            _renderer.sharedMaterial = CityMaterials.SharedMaterial(CityMaterials.Hologram);
            _renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _renderer.receiveShadows = false;

            Mesh mesh;
            switch (slot.Shape)
            {
                case BuildingShape.SteppedTower: mesh = MeshFactory.SteppedTower(slot.Width, slot.Depth, slot.Height); break;
                case BuildingShape.Tank: mesh = MeshFactory.Prism(slot.Width, slot.Width, slot.Height, 24, 0.92f, "Tank"); break;
                case BuildingShape.Hangar: mesh = MeshFactory.Prism(slot.Width, slot.Depth, slot.Height, 4, 0.8f, "Hangar"); break;
                default: mesh = MeshFactory.Prism(slot.Width, slot.Depth, slot.Height, 4, 0.9f, "Tower"); break;
            }
            GetComponent<MeshFilter>().sharedMesh = mesh;
            var box = gameObject.AddComponent<BoxCollider>();
            box.center = new Vector3(0, slot.Height / 2f, 0);
            box.size = new Vector3(slot.Width, slot.Height, slot.Depth);

            _block = new MaterialPropertyBlock();
            _block.SetColor(CityMaterials.DistrictColorId, districtColor);
            _block.SetFloat(CityMaterials.HeightId, slot.Height);
            _block.SetFloat(CityMaterials.TimeOffsetId, Random.Range(0f, 100f));
            SetMode(BuildingMode.Idle, instant: true);
            _rise = 0f;
        }

        public void SetStatus(TestStatus status)
        {
            Status = status;
            SetMode(Palette.ModeFor(status), instant: false);
            _flash = 1f;
        }

        public void SetMode(BuildingMode mode, bool instant)
        {
            _target = Palette.ForMode(mode);
            if (instant)
            {
                _color = _target.Color.ToColor();
                _glitch = _target.Glitch;
                _pulse = _target.Pulse;
                _freeze = _target.Freeze;
                _wire = _target.Wireframe;
                _opacity = _target.Opacity;
            }
        }

        public void ResetState()
        {
            Status = TestStatus.Unknown;
            SetMode(BuildingMode.Idle, instant: false);
        }

        private void Update()
        {
            if (_block == null) return;
            float k = 1f - Mathf.Exp(-Blend * Time.deltaTime);
            _color = Color.Lerp(_color, _target.Color.ToColor(), k);
            _glitch = Mathf.Lerp(_glitch, _target.Glitch, k);
            _pulse = Mathf.Lerp(_pulse, _target.Pulse, k);
            _freeze = Mathf.Lerp(_freeze, _target.Freeze, k);
            _wire = Mathf.Lerp(_wire, Inspecting ? 1f : _target.Wireframe, k);
            _opacity = Mathf.Lerp(_opacity, _target.Opacity, k);
            _scan = Mathf.Lerp(_scan, _target.Mode == BuildingMode.Scanning ? 1f : 0f, k);
            _rise = Mathf.MoveTowards(_rise, 1f, Time.deltaTime * 0.8f);
            _selected = Mathf.Lerp(_selected, Selected ? 1f : 0f, k);
            _flash = Mathf.MoveTowards(_flash, 0f, Time.deltaTime * 1.5f);

            _block.SetColor(CityMaterials.ColorId, Color.Lerp(_color, Color.white, _flash * 0.5f));
            _block.SetFloat(CityMaterials.GlitchId, Mathf.Clamp01(_glitch + _flash * 0.3f));
            _block.SetFloat(CityMaterials.PulseId, _pulse);
            _block.SetFloat(CityMaterials.FreezeId, _freeze);
            _block.SetFloat(CityMaterials.WireId, _wire);
            _block.SetFloat(CityMaterials.OpacityId, _opacity);
            _block.SetFloat(CityMaterials.ScanId, _scan);
            _block.SetFloat(CityMaterials.RiseId, Mathf.SmoothStep(0f, 1f, _rise));
            _block.SetFloat(CityMaterials.SelectedId, _selected);
            _renderer.SetPropertyBlock(_block);
        }
    }
}
