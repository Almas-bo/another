using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Поток данных (частицы) от ядра к району. Плотность — нагрузка CPU последнего запуска;
    /// TIMEOUT/DEADLOCK останавливает частицы на месте («замерший трафик»), шторм потоков — хаотичный выброс.
    /// </summary>
    public sealed class TrafficFlow : MonoBehaviour
    {
        private ParticleSystem _particles;
        private Vector3 _direction;

        public void Build(Vector3 from, Vector3 to, Color color)
        {
            _direction = (to - from).normalized;
            float distance = Vector3.Distance(from, to);
            transform.position = from + Vector3.up * 0.6f;
            transform.rotation = Quaternion.LookRotation(_direction, Vector3.up);

            _particles = gameObject.AddComponent<ParticleSystem>();
            _particles.Stop(true, ParticleSystemStopBehavior.StopEmittingAndClear);
            var main = _particles.main;
            main.loop = true;
            main.playOnAwake = false;
            main.startSpeed = 18f;
            main.startLifetime = distance / 18f;
            main.startSize = 0.7f;
            main.startColor = color;
            main.maxParticles = 600;
            main.simulationSpace = ParticleSystemSimulationSpace.World;

            var emission = _particles.emission;
            emission.rateOverTime = 6f;

            var shape = _particles.shape;
            shape.shapeType = ParticleSystemShapeType.Box;
            shape.scale = new Vector3(1.2f, 0.1f, 0.1f);

            var renderer = GetComponent<ParticleSystemRenderer>();
            renderer.sharedMaterial = CityMaterials.SharedMaterial(CityMaterials.Particle);
            renderer.renderMode = ParticleSystemRenderMode.Billboard;
            renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _particles.Play();
        }

        public void SetState(Color color, float rate, bool frozen, bool storm)
        {
            if (_particles == null) return;
            var main = _particles.main;
            main.startColor = color;
            main.simulationSpeed = frozen ? 0f : 1f;
            var emission = _particles.emission;
            emission.rateOverTime = frozen ? 0f : rate;
            var noise = _particles.noise;
            noise.enabled = storm;
            noise.strength = storm ? 6f : 0f;
            noise.frequency = 0.6f;
            if (storm) _particles.Emit(120);
        }
    }
}
