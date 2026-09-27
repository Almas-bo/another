using SubroutineCity.Core.City;
using SubroutineCity.Core.Protocol;
using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Тепловая карта памяти на земле района: каждое здание-тест «нагревает» квартал пропорционально
    /// аллокациям (логарифмическая шкала). Тест с MEMORY_LIMIT_EXCEEDED запускает волну перегрева.
    /// </summary>
    public sealed class MemoryHeatmap
    {
        public const int Resolution = 96;
        public const float WorldSize = 240f;

        private readonly Texture2D _texture;
        private readonly float[] _current = new float[Resolution * Resolution];
        private readonly float[] _target = new float[Resolution * Resolution];
        private readonly Color32[] _pixels = new Color32[Resolution * Resolution];
        private readonly Material _ground;
        private float _overflow;
        private float _overflowTarget;
        private bool _dirty;

        public MemoryHeatmap(Material ground)
        {
            _ground = ground;
            _texture = new Texture2D(Resolution, Resolution, TextureFormat.RGBA32, false, true)
            {
                name = "Heatmap",
                wrapMode = TextureWrapMode.Clamp,
                filterMode = FilterMode.Bilinear,
                hideFlags = HideFlags.DontSave
            };
            _ground.SetTexture(CityMaterials.HeatTexId, _texture);
            _ground.SetVector(CityMaterials.HeatRectId, new Vector4(-WorldSize / 2f, -WorldSize / 2f, WorldSize, 0f));
            Upload();
        }

        public void Clear()
        {
            System.Array.Clear(_target, 0, _target.Length);
            _overflowTarget = 0f;
            _dirty = true;
        }

        /// <summary>Нагревает квартал вокруг здания.</summary>
        public void Stamp(Vector3 world, TestOutcome outcome)
        {
            float heat = Palette.Heat(outcome.Metrics.AllocatedBytes, outcome.Status);
            if (heat <= 0f) return;
            float radius = 3f + heat * 5f;
            int cx = ToCell(world.x);
            int cz = ToCell(world.z);
            int r = Mathf.CeilToInt(radius / WorldSize * Resolution) + 1;
            for (int z = cz - r; z <= cz + r; z++)
            for (int x = cx - r; x <= cx + r; x++)
            {
                if (x < 0 || z < 0 || x >= Resolution || z >= Resolution) continue;
                float wx = (x + 0.5f) / Resolution * WorldSize - WorldSize / 2f;
                float wz = (z + 0.5f) / Resolution * WorldSize - WorldSize / 2f;
                float d = new Vector2(wx - world.x, wz - world.z).magnitude / radius;
                float value = heat * Mathf.Exp(-d * d * 2.5f);
                int i = z * Resolution + x;
                _target[i] = Mathf.Max(_target[i], value);
            }
            if (outcome.Status == TestStatus.MEMORY_LIMIT_EXCEEDED)
            {
                _overflowTarget = 1f;
                _ground.SetVector(CityMaterials.OverflowCenterId, new Vector4(world.x, world.z, 0f, 0f));
            }
            _dirty = true;
        }

        /// <summary>Плавный нагрев/остывание; вызывается каждый кадр.</summary>
        public void Tick(float deltaTime)
        {
            _overflow = Mathf.MoveTowards(_overflow, _overflowTarget, deltaTime * 0.8f);
            _ground.SetFloat(CityMaterials.OverflowId, _overflow);
            if (!_dirty) return;
            float k = 1f - Mathf.Exp(-3f * deltaTime);
            bool settled = true;
            for (int i = 0; i < _current.Length; i++)
            {
                float next = Mathf.Lerp(_current[i], _target[i], k);
                if (Mathf.Abs(next - _target[i]) < 0.002f) next = _target[i];
                else settled = false;
                _current[i] = next;
            }
            Upload();
            _dirty = !settled;
        }

        private void Upload()
        {
            for (int i = 0; i < _current.Length; i++)
            {
                byte value = (byte)Mathf.Clamp(Mathf.RoundToInt(_current[i] * 255f), 0, 255);
                _pixels[i] = new Color32(value, 0, 0, 255);
            }
            _texture.SetPixels32(_pixels);
            _texture.Apply(false, false);
        }

        private static int ToCell(float world) => Mathf.FloorToInt((world + WorldSize / 2f) / WorldSize * Resolution);
    }
}
