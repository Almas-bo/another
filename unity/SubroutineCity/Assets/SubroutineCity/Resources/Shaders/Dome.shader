// Купол городского файрвола. Спокойный режим — едва заметные шестиугольники; _Alert = 1 при
// POLICY_VIOLATION: красные вспышки ячеек и волны от точек «удара» (попыток выхода из песочницы).
Shader "SubroutineCity/Dome"
{
    Properties
    {
        _Color ("Цвет", Color) = (0.13, 0.88, 1, 1)
        _AlertColor ("Цвет тревоги", Color) = (1, 0.23, 0.36, 1)
        _Alert ("Тревога", Range(0, 1)) = 0
        _Opacity ("Непрозрачность", Range(0, 1)) = 0.35
        _HexScale ("Размер ячеек", Float) = 24
    }
    SubShader
    {
        Tags { "Queue" = "Transparent+10" "RenderType" = "Transparent" "IgnoreProjector" = "True" }
        Blend SrcAlpha One
        ZWrite Off
        Cull Off

        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #pragma target 3.0
            #include "UnityCG.cginc"
            #include "SubroutineCityCommon.cginc"

            fixed4 _Color;
            fixed4 _AlertColor;
            float _Alert;
            float _Opacity;
            float _HexScale;

            struct appdata
            {
                float4 vertex : POSITION;
                float3 normal : NORMAL;
                float2 uv : TEXCOORD0;
            };

            struct v2f
            {
                float4 pos : SV_POSITION;
                float2 uv : TEXCOORD0;
                float3 worldPos : TEXCOORD1;
                float3 worldNormal : TEXCOORD2;
            };

            v2f vert(appdata v)
            {
                v2f o;
                o.pos = UnityObjectToClipPos(v.vertex);
                o.uv = v.uv;
                o.worldPos = mul(unity_ObjectToWorld, v.vertex).xyz;
                o.worldNormal = UnityObjectToWorldNormal(v.normal);
                return o;
            }

            // Расстояние до края шестиугольной ячейки и её id.
            float4 hexCoords(float2 p)
            {
                const float2 r = float2(1.0, 1.7320508);
                const float2 h = r * 0.5;
                float2 a = fmod(abs(p), r) - h;
                float2 b = fmod(abs(p - h), r) - h;
                float2 gv = dot(a, a) < dot(b, b) ? a : b;
                float2 id = p - gv;
                float2 q = abs(gv);
                float edge = 0.5 - max(dot(q, normalize(float2(1.0, 1.7320508))), q.x);
                return float4(gv, id);
            }

            fixed4 frag(v2f i) : SV_Target
            {
                float t = _Time.y;
                float3 n = normalize(i.worldNormal);
                float3 viewDir = normalize(_WorldSpaceCameraPos - i.worldPos);
                float fresnel = pow(1.0 - saturate(abs(dot(n, viewDir))), 3.0);

                float2 p = float2(i.uv.x * _HexScale * 2.0, i.uv.y * _HexScale * 0.6);
                float4 hc = hexCoords(p);
                float2 q = abs(hc.xy);
                float edgeDist = 0.5 - max(dot(q, normalize(float2(1.0, 1.7320508))), q.x);
                float edge = 1.0 - smoothstep(0.0, 0.06, edgeDist);

                float cellRand = sc_hash21(hc.zw);
                float cellFlash = step(0.82, frac(cellRand * 7.0 + t * 1.3)) * _Alert;
                float wave = pow(0.5 + 0.5 * sin(i.worldPos.y * 0.6 - t * 5.0), 8.0) * _Alert;

                float3 color = lerp(_Color.rgb, _AlertColor.rgb, _Alert);
                float intensity = edge * (0.35 + fresnel) + fresnel * 0.4 + cellFlash * 0.6 + wave * 0.5;
                float alpha = _Opacity * intensity * lerp(0.35, 1.0, _Alert);
                return fixed4(color * (1.0 + _Alert), saturate(alpha));
            }
            ENDCG
        }
    }
    FallBack Off
}
