using UnityEngine;

namespace SubroutineCity.City
{
    /// <summary>
    /// Орбитальная камера: вращение, приближение, плавный перелёт к району или зданию, автоповорот в меню.
    /// Ввод приходит из IMGUI (GameUI) — камера не зависит от системы ввода проекта (старой или новой).
    /// </summary>
    public sealed class CameraRig : MonoBehaviour
    {
        public float MinDistance = 18f;
        public float MaxDistance = 260f;

        private Vector3 _target;
        private Vector3 _focus;
        private float _yaw = 30f;
        private float _pitch = 38f;
        private float _distance = 190f;
        private float _targetDistance = 190f;
        private float _targetYaw = 30f;
        private float _targetPitch = 38f;
        private float _idle;

        public Camera Camera { get; private set; }
        public bool AutoRotate { get; set; } = true;

        /// <summary>Доля экрана слева, занятая интерфейсом: вьюпорт камеры сдвигается вправо.</summary>
        public float LeftInset { get; set; }

        public void Init(Camera camera)
        {
            Camera = camera;
            Camera.clearFlags = CameraClearFlags.SolidColor;
            Camera.backgroundColor = new Color(0.01f, 0.015f, 0.03f);
            Camera.nearClipPlane = 0.3f;
            Camera.farClipPlane = 2000f;
            Camera.fieldOfView = 45f;
            Camera.allowHDR = true;
            _target = _focus = Vector3.zero;
        }

        public void Focus(Vector3 point, float distance, float? yaw = null, float? pitch = null)
        {
            _target = point;
            _targetDistance = Mathf.Clamp(distance, MinDistance, MaxDistance);
            if (yaw.HasValue) _targetYaw = yaw.Value;
            if (pitch.HasValue) _targetPitch = pitch.Value;
            _idle = 0f;
        }

        public void Orbit(float deltaX, float deltaY)
        {
            _targetYaw += deltaX * 0.25f;
            _targetPitch = Mathf.Clamp(_targetPitch - deltaY * 0.2f, 8f, 85f);
            _idle = 0f;
        }

        public void Zoom(float delta)
        {
            _targetDistance = Mathf.Clamp(_targetDistance * (1f + delta * 0.08f), MinDistance, MaxDistance);
            _idle = 0f;
        }

        private void LateUpdate()
        {
            if (Camera == null) return;
            float dt = Time.unscaledDeltaTime;
            _idle += dt;
            if (AutoRotate && _idle > 4f) _targetYaw += dt * 3f;

            float k = 1f - Mathf.Exp(-3.5f * dt);
            _focus = Vector3.Lerp(_focus, _target, k);
            _yaw = Mathf.LerpAngle(_yaw, _targetYaw, k);
            _pitch = Mathf.Lerp(_pitch, _targetPitch, k);
            _distance = Mathf.Lerp(_distance, _targetDistance, k);

            Quaternion rotation = Quaternion.Euler(_pitch, _yaw, 0f);
            Camera.transform.position = _focus + rotation * new Vector3(0, 0, -_distance);
            Camera.transform.rotation = rotation;
            float inset = Mathf.Clamp01(LeftInset);
            Rect current = Camera.rect;
            float x = Mathf.Lerp(current.x, inset, Mathf.Clamp01(k * 2f));
            if (Mathf.Abs(x - inset) < 0.001f) x = inset;
            if (!Mathf.Approximately(current.x, x)) Camera.rect = new Rect(x, 0f, 1f - x, 1f);
        }
    }
}
