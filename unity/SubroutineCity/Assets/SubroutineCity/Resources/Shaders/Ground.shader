// Земля города: сетка кварталов + тепловая карта памяти (текстура _HeatTex в мировых координатах).
// _Overflow — волна перегрева при MEMORY_LIMIT_EXCEEDED; _Power — питание района (0 при ошибке компиляции);
// _CorePulse — кольцевые импульсы от ядра JVM к районам.
Shader "SubroutineCity/Ground"
{
    Properties
    {
        _BaseColor ("База", Color) = (0.012, 0.02, 0.04, 1)
        _GridColor ("Сетка", Color) = (0.13, 0.55, 0.9, 1)
        _HeatTex ("Тепловая карта", 2D) = "black" {}
        _HeatRect ("Область карты (xMin, zMin, размер, -)", Vector) = (-120, -120, 240, 0)
        _Overflow ("Переполнение", Range(0, 1)) = 0
        _OverflowCenter ("Центр переполнения (x, z)", Vector) = (0, 0, 0, 0)
        _Power ("Питание", Range(0, 1)) = 1
        _CorePulse ("Импульсы ядра", Range(0, 1)) = 1
        _FadeRadius ("Радиус затухания", Float) = 150
    }
    SubShader
    {
        Tags { "Queue" = "Geometry" "RenderType" = "Opaque" }
        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #pragma target 3.0
            #include "UnityCG.cginc"
            #include "SubroutineCityCommon.cginc"

            fixed4 _BaseColor;
            fixed4 _GridColor;
            sampler2D _HeatTex;
            float4 _HeatRect;
            float _Overflow;
            float4 _OverflowCenter;
            float _Power;
            float _CorePulse;
            float _FadeRadius;

            struct appdata
            {
                float4 vertex : POSITION;
            };

            struct v2f
            {
                float4 pos : SV_POSITION;
                float3 worldPos : TEXCOORD0;
            };

            v2f vert(appdata v)
            {
                v2f o;
                o.pos = UnityObjectToClipPos(v.vertex);
                o.worldPos = mul(unity_ObjectToWorld, v.vertex).xyz;
                return o;
            }

            float gridLine(float2 p, float spacing, float width)
            {
                float2 g = abs(frac(p / spacing - 0.5) - 0.5) * spacing;
                float2 w = fwidth(p) * width;
                float2 l = 1.0 - smoothstep(float2(0, 0), w, g);
                return max(l.x, l.y);
            }

            // Тепловая шкала: тёмно-синий → бирюзовый → жёлтый → оранжевый → белый.
            float3 heatRamp(float h)
            {
                float3 c0 = float3(0.0, 0.05, 0.12);
                float3 c1 = float3(0.0, 0.6, 0.7);
                float3 c2 = float3(1.0, 0.85, 0.1);
                float3 c3 = float3(1.0, 0.35, 0.05);
                float3 c4 = float3(1.0, 0.95, 0.9);
                if (h < 0.25) return lerp(c0, c1, h / 0.25);
                if (h < 0.5) return lerp(c1, c2, (h - 0.25) / 0.25);
                if (h < 0.8) return lerp(c2, c3, (h - 0.5) / 0.3);
                return lerp(c3, c4, (h - 0.8) / 0.2);
            }

            fixed4 frag(v2f i) : SV_Target
            {
                float2 p = i.worldPos.xz;
                float t = _Time.y;
                float dist = length(p);

                float minor = gridLine(p, 3.0, 1.0) * 0.18;
                float major = gridLine(p, 18.0, 1.6) * 0.55;

                float2 heatUv = (p - _HeatRect.xy) / _HeatRect.z;
                float heat = tex2D(_HeatTex, heatUv).r;
                float inside = step(0.0, heatUv.x) * step(heatUv.x, 1.0) * step(0.0, heatUv.y) * step(heatUv.y, 1.0);
                heat *= inside;

                // Кольца от ядра: данные текут в районы.
                float rings = pow(0.5 + 0.5 * sin(dist * 0.35 - t * 2.2), 24.0) * _CorePulse * smoothstep(8.0, 20.0, dist);

                // Волна переполнения памяти.
                float od = length(p - _OverflowCenter.xy);
                float wave = pow(0.5 + 0.5 * sin(od * 0.8 - t * 6.0), 6.0) * _Overflow * smoothstep(60.0, 0.0, od);

                float flicker = lerp(0.35 + 0.65 * step(0.5, sc_hash11(floor(t * 8.0))), 1.0, _Power);
                float3 color = _BaseColor.rgb;
                color += _GridColor.rgb * (minor + major) * flicker;
                color += heatRamp(heat) * heat * 1.4;
                color += _GridColor.rgb * rings * 0.35 * _Power;
                color += float3(1.0, 0.4, 0.05) * wave * 1.5;
                color *= lerp(0.25, 1.0, _Power);

                float fade = 1.0 - smoothstep(_FadeRadius * 0.6, _FadeRadius, dist);
                return fixed4(color * fade + _BaseColor.rgb * (1.0 - fade), 1.0);
            }
            ENDCG
        }
    }
    FallBack Off
}
