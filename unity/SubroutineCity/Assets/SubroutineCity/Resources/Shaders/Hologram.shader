// Здание-тест. Все параметры задаются из BuildingView через MaterialPropertyBlock (один материал на весь город).
// Режимы (см. Core/City/VisualState.cs): Passed — ровное зелёное свечение; Failed/Crashed — глитч (разрывы
// сканлайнов и смещение вершин); Frozen — иней и остановленные окна; Locked — фиолетовая блокировка;
// Ghost — каркас по барицентрическим координатам (UV2), без геометрического шейдера (работает и на Metal).
Shader "SubroutineCity/Hologram"
{
    Properties
    {
        _Color ("Цвет состояния", Color) = (0.13, 0.88, 1, 1)
        _DistrictColor ("Цвет района", Color) = (0.13, 0.88, 1, 1)
        _Opacity ("Непрозрачность", Range(0, 1)) = 0.6
        _Glitch ("Глитч", Range(0, 1)) = 0
        _Pulse ("Пульсация, Гц", Range(0, 10)) = 0
        _Freeze ("Заморозка", Range(0, 1)) = 0
        _Wire ("Каркас", Range(0, 1)) = 0
        _Scan ("Сканирование", Range(0, 1)) = 0
        _Rise ("Постройка", Range(0, 1)) = 1
        _Selected ("Выбрано", Range(0, 1)) = 0
        _Height ("Высота объекта", Float) = 10
        _TimeOffset ("Сдвиг фазы", Float) = 0
    }
    SubShader
    {
        Tags { "Queue" = "Transparent" "RenderType" = "Transparent" "IgnoreProjector" = "True" }
        Blend SrcAlpha One
        ZWrite Off
        Cull Back

        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #pragma target 3.0
            #include "UnityCG.cginc"
            #include "SubroutineCityCommon.cginc"

            fixed4 _Color;
            fixed4 _DistrictColor;
            float _Opacity;
            float _Glitch;
            float _Pulse;
            float _Freeze;
            float _Wire;
            float _Scan;
            float _Rise;
            float _Selected;
            float _Height;
            float _TimeOffset;

            struct appdata
            {
                float4 vertex : POSITION;
                float3 normal : NORMAL;
                float2 uv : TEXCOORD0;      // x — периметр (0..1), y — высота в метрах
                float3 bary : TEXCOORD1;    // барицентрические координаты вершины треугольника
            };

            struct v2f
            {
                float4 pos : SV_POSITION;
                float2 uv : TEXCOORD0;
                float3 bary : TEXCOORD1;
                float3 worldPos : TEXCOORD2;
                float3 worldNormal : TEXCOORD3;
                float heightFrac : TEXCOORD4;
            };

            // Время анимации останавливается при заморозке.
            float animTime()
            {
                return (_Time.y + _TimeOffset) * (1.0 - 0.95 * _Freeze);
            }

            v2f vert(appdata v)
            {
                v2f o;
                float t = animTime();
                float heightFrac = saturate(v.vertex.y / max(_Height, 0.001));
                // Глитч: горизонтальные «срывы» — полосы корпуса сдвигаются рывками.
                float band = floor(heightFrac * 14.0 + floor(t * 9.0) * 3.0);
                float tear = step(1.0 - 0.35 * _Glitch, sc_hash11(band));
                float4 vertex = v.vertex;
                vertex.x += (sc_hash11(band + 7.0) - 0.5) * 1.6 * tear * _Glitch;
                vertex.z += (sc_hash11(band + 13.0) - 0.5) * 0.8 * tear * _Glitch;
                o.pos = UnityObjectToClipPos(vertex);
                o.uv = v.uv;
                o.bary = v.bary;
                o.worldPos = mul(unity_ObjectToWorld, vertex).xyz;
                o.worldNormal = UnityObjectToWorldNormal(v.normal);
                o.heightFrac = heightFrac;
                return o;
            }

            fixed4 frag(v2f i) : SV_Target
            {
                clip(_Rise - i.heightFrac);
                float t = animTime();
                float3 n = normalize(i.worldNormal);
                float3 viewDir = normalize(_WorldSpaceCameraPos - i.worldPos);
                float fresnel = pow(1.0 - saturate(abs(dot(n, viewDir))), 2.5);

                // Окна: сетка по периметру и высоте, мерцание по ячейкам.
                float2 grid = float2(i.uv.x * 10.0, i.uv.y * 0.9);
                float2 cell = floor(grid);
                float2 f = frac(grid);
                float window = step(0.18, f.x) * step(f.x, 0.82) * step(0.25, f.y) * step(f.y, 0.7);
                float lit = step(0.35, sc_hash21(cell + floor(t * 0.4 + sc_hash21(cell) * 5.0)));
                window *= lit * (1.0 - step(0.5, abs(n.y)));  // крыша без окон

                // Сканлайны и восходящая полоса сканирования.
                float scanlines = 0.5 + 0.5 * sin(i.worldPos.y * 38.0 - t * 5.0);
                float sweep = smoothstep(0.1, 0.0, abs(frac(t * 0.35) - i.heightFrac)) * _Scan;

                // Каркас из барицентрических координат (толщина в пикселях постоянна).
                float3 d = fwidth(i.bary);
                float3 a3 = smoothstep(float3(0, 0, 0), d * 1.5, i.bary);
                float edge = 1.0 - min(a3.x, min(a3.y, a3.z));

                // Разрывы глитча: яркие горизонтальные полосы с цветовым сдвигом.
                float tearLine = step(0.965, sc_hash11(floor(i.worldPos.y * 22.0) + floor(t * 14.0))) * _Glitch;

                // Иней при заморозке.
                float frost = sc_noise21(i.worldPos.xz * 3.0 + i.worldPos.y * 2.0) * _Freeze;

                float pulse = lerp(0.7, 1.0, sc_pulse(t, _Pulse));
                float3 baseColor = lerp(_DistrictColor.rgb, _Color.rgb, 0.8);
                float3 color = baseColor * (0.12 + window * 0.9 * scanlines + fresnel * 1.4) * pulse;
                color += _Color.rgb * sweep * 1.5;
                color += float3(1.0, 0.2, 0.35) * tearLine * 1.8;
                color = lerp(color, float3(0.85, 0.95, 1.0) * 0.8, frost * 0.6);
                color += baseColor * edge * (0.6 + _Wire * 1.4);
                color += float3(1, 1, 1) * _Selected * (fresnel * 0.9 + edge * 0.8);

                float alpha = _Opacity * (0.3 + fresnel * 0.8 + window * 0.35) * (1.0 - _Wire * 0.85);
                alpha += edge * lerp(0.25, 1.0, _Wire) + tearLine * 0.6 + sweep * 0.4 + _Selected * 0.15;
                return fixed4(color, saturate(alpha));
            }
            ENDCG
        }
    }
    FallBack Off
}
