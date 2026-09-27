// Небо мегаполиса: градиент от смога у горизонта к тёмному зениту, редкие «спутники».
Shader "SubroutineCity/Sky"
{
    Properties
    {
        _Horizon ("Горизонт", Color) = (0.07, 0.03, 0.12, 1)
        _Zenith ("Зенит", Color) = (0.005, 0.01, 0.03, 1)
        _Glow ("Зарево", Color) = (0.1, 0.35, 0.55, 1)
    }
    SubShader
    {
        Tags { "Queue" = "Background" "RenderType" = "Background" "PreviewType" = "Skybox" }
        Cull Front
        ZWrite Off

        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "UnityCG.cginc"
            #include "SubroutineCityCommon.cginc"

            fixed4 _Horizon;
            fixed4 _Zenith;
            fixed4 _Glow;

            struct appdata
            {
                float4 vertex : POSITION;
            };

            struct v2f
            {
                float4 pos : SV_POSITION;
                float3 dir : TEXCOORD0;
            };

            v2f vert(appdata v)
            {
                v2f o;
                o.pos = UnityObjectToClipPos(v.vertex);
                o.dir = normalize(v.vertex.xyz);
                return o;
            }

            fixed4 frag(v2f i) : SV_Target
            {
                float3 d = normalize(i.dir);
                float h = saturate(d.y);
                float3 color = lerp(_Horizon.rgb, _Zenith.rgb, pow(h, 0.45));
                color += _Glow.rgb * pow(1.0 - h, 10.0) * 0.8;
                float2 cell = floor(float2(atan2(d.z, d.x) * 180.0, d.y * 260.0));
                float star = step(0.9975, sc_hash21(cell)) * smoothstep(0.1, 0.5, h);
                star *= 0.5 + 0.5 * sin(_Time.y * 2.0 + sc_hash21(cell + 3.0) * 30.0);
                color += star * float3(0.6, 0.8, 1.0);
                return fixed4(color, 1.0);
            }
            ENDCG
        }
    }
    FallBack Off
}
