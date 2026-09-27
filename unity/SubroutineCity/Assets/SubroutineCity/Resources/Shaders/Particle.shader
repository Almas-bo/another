// Частицы трафика и данных: мягкий аддитивный диск, цвет из ParticleSystem (vertex color).
Shader "SubroutineCity/Particle"
{
    Properties
    {
        _Intensity ("Яркость", Float) = 1.5
    }
    SubShader
    {
        Tags { "Queue" = "Transparent" "RenderType" = "Transparent" "IgnoreProjector" = "True" "PreviewType" = "Plane" }
        Blend SrcAlpha One
        ZWrite Off
        Cull Off

        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "UnityCG.cginc"

            float _Intensity;

            struct appdata
            {
                float4 vertex : POSITION;
                float2 uv : TEXCOORD0;
                fixed4 color : COLOR;
            };

            struct v2f
            {
                float4 pos : SV_POSITION;
                float2 uv : TEXCOORD0;
                fixed4 color : COLOR;
            };

            v2f vert(appdata v)
            {
                v2f o;
                o.pos = UnityObjectToClipPos(v.vertex);
                o.uv = v.uv;
                o.color = v.color;
                return o;
            }

            fixed4 frag(v2f i) : SV_Target
            {
                float d = length(i.uv - 0.5) * 2.0;
                float disc = saturate(1.0 - d);
                disc = disc * disc * (3.0 - 2.0 * disc);
                return fixed4(i.color.rgb * _Intensity, disc * i.color.a);
            }
            ENDCG
        }
    }
    FallBack Off
}
