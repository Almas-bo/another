using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Постобработка для Built-in Render Pipeline: bloom неона, виньетка, хроматическая аберрация и шум при сбоях.
    /// В URP/HDRP OnRenderImage не вызывается — город работает без этих эффектов.
    /// </summary>
    [RequireComponent(typeof(Camera))]
    public sealed class CityPostFx : MonoBehaviour
    {
        private const int Iterations = 5;
        private const int PassPrefilter = 0;
        private const int PassDown = 1;
        private const int PassUp = 2;
        private const int PassComposite = 3;

        public float Threshold = 0.8f;
        public float SoftKnee = 0.6f;
        public float Intensity = 1.1f;
        public float Vignette = 1.1f;

        private readonly RenderTexture[] _levels = new RenderTexture[Iterations];
        private Material _material;
        private float _glitch;

        private static readonly int ThresholdId = Shader.PropertyToID("_Threshold");
        private static readonly int SoftKneeId = Shader.PropertyToID("_SoftKnee");
        private static readonly int BloomIntensityId = Shader.PropertyToID("_BloomIntensity");
        private static readonly int VignetteId = Shader.PropertyToID("_Vignette");
        private static readonly int GlitchId = Shader.PropertyToID("_Glitch");
        private static readonly int BloomTexId = Shader.PropertyToID("_BloomTex");

        /// <summary>Кратковременный сбой изображения (0..1), затухает сам.</summary>
        public void Kick(float amount)
        {
            _glitch = Mathf.Max(_glitch, Mathf.Clamp01(amount));
        }

        private void Update()
        {
            _glitch = Mathf.MoveTowards(_glitch, 0f, Time.unscaledDeltaTime * 0.9f);
        }

        private void OnRenderImage(RenderTexture source, RenderTexture destination)
        {
            if (_material == null)
            {
                Shader shader = Shader.Find(CityMaterials.PostFx);
                if (shader == null || !shader.isSupported)
                {
                    Graphics.Blit(source, destination);
                    enabled = false;
                    return;
                }
                _material = new Material(shader) { hideFlags = HideFlags.DontSave };
            }
            _material.SetFloat(ThresholdId, Threshold);
            _material.SetFloat(SoftKneeId, SoftKnee);
            _material.SetFloat(BloomIntensityId, Intensity);
            _material.SetFloat(VignetteId, Vignette);
            _material.SetFloat(GlitchId, _glitch);

            int width = source.width / 2;
            int height = source.height / 2;
            RenderTextureFormat format = source.format;
            RenderTexture current = _levels[0] = RenderTexture.GetTemporary(width, height, 0, format);
            Graphics.Blit(source, current, _material, PassPrefilter);
            int count = 1;
            for (; count < Iterations; count++)
            {
                width /= 2;
                height /= 2;
                if (width < 2 || height < 2) break;
                _levels[count] = RenderTexture.GetTemporary(width, height, 0, format);
                Graphics.Blit(current, _levels[count], _material, PassDown);
                current = _levels[count];
            }
            for (int i = count - 2; i >= 0; i--)
            {
                Graphics.Blit(current, _levels[i], _material, PassUp);
                RenderTexture.ReleaseTemporary(current);
                _levels[i + 1] = null;
                current = _levels[i];
            }
            _material.SetTexture(BloomTexId, current);
            Graphics.Blit(source, destination, _material, PassComposite);
            RenderTexture.ReleaseTemporary(current);
            _levels[0] = null;
        }

        private void OnDestroy()
        {
            if (_material != null) Destroy(_material);
        }
    }
}
