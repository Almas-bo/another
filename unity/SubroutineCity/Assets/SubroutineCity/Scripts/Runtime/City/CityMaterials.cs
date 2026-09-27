using System.Collections.Generic;
using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Материалы города. Шейдеры лежат в Resources/Shaders — Unity включает их в сборку, и Shader.Find их находит.
    /// Если шейдер недоступен (например, не скомпилировался на платформе), берётся встроенная замена —
    /// игра останется работоспособной, пусть и без эффектов.
    /// </summary>
    public static class CityMaterials
    {
        public const string Hologram = "SubroutineCity/Hologram";
        public const string Ground = "SubroutineCity/Ground";
        public const string EnergyLine = "SubroutineCity/EnergyLine";
        public const string Dome = "SubroutineCity/Dome";
        public const string Particle = "SubroutineCity/Particle";
        public const string Sky = "SubroutineCity/Sky";
        public const string PostFx = "Hidden/SubroutineCity/PostFx";

        private static readonly Dictionary<string, Material> Shared = new Dictionary<string, Material>();

        public static readonly int ColorId = Shader.PropertyToID("_Color");
        public static readonly int DistrictColorId = Shader.PropertyToID("_DistrictColor");
        public static readonly int OpacityId = Shader.PropertyToID("_Opacity");
        public static readonly int GlitchId = Shader.PropertyToID("_Glitch");
        public static readonly int PulseId = Shader.PropertyToID("_Pulse");
        public static readonly int FreezeId = Shader.PropertyToID("_Freeze");
        public static readonly int WireId = Shader.PropertyToID("_Wire");
        public static readonly int ScanId = Shader.PropertyToID("_Scan");
        public static readonly int RiseId = Shader.PropertyToID("_Rise");
        public static readonly int SelectedId = Shader.PropertyToID("_Selected");
        public static readonly int HeightId = Shader.PropertyToID("_Height");
        public static readonly int TimeOffsetId = Shader.PropertyToID("_TimeOffset");
        public static readonly int AlertId = Shader.PropertyToID("_Alert");
        public static readonly int StallId = Shader.PropertyToID("_Stall");
        public static readonly int IntensityId = Shader.PropertyToID("_Intensity");
        public static readonly int SpeedId = Shader.PropertyToID("_Speed");
        public static readonly int HeatTexId = Shader.PropertyToID("_HeatTex");
        public static readonly int HeatRectId = Shader.PropertyToID("_HeatRect");
        public static readonly int OverflowId = Shader.PropertyToID("_Overflow");
        public static readonly int OverflowCenterId = Shader.PropertyToID("_OverflowCenter");
        public static readonly int PowerId = Shader.PropertyToID("_Power");

        /// <summary>Общий материал (один на шейдер) — параметры объектов задаются через MaterialPropertyBlock.</summary>
        public static Material SharedMaterial(string shaderName)
        {
            if (Shared.TryGetValue(shaderName, out Material material) && material != null) return material;
            material = Create(shaderName);
            Shared[shaderName] = material;
            return material;
        }

        /// <summary>Собственный экземпляр материала (для объектов с уникальными текстурами или параметрами линий).</summary>
        public static Material Create(string shaderName)
        {
            Shader shader = Shader.Find(shaderName);
            if (shader == null || !shader.isSupported)
            {
                Debug.LogWarning("Subroutine City: шейдер " + shaderName + " недоступен, используется замена.");
                shader = Shader.Find("Sprites/Default");
                if (shader == null) shader = Shader.Find("Unlit/Color");
            }
            var material = new Material(shader) { name = shaderName };
            material.hideFlags = HideFlags.DontSave;
            return material;
        }
    }
}
