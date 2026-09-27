// Линии данных и «цепи» deadlock для LineRenderer (uv.x растёт вдоль линии при textureMode = Tile).
// Бегущие штрихи показывают направление ожидания: поток → владелец монитора.
Shader "SubroutineCity/EnergyLine"
{
    Properties
    {
        _Color ("Цвет", Color) = (0.65, 0.42, 1, 1)
        _Speed ("Скорость", Float) = 2
        _Dash ("Частота штрихов", Float) = 1
        _Intensity ("Яркость", Float) = 2
        _Stall ("Остановка потока", Range(0, 1)) = 0
    }
    SubShader
    {
        Tags { "Queue" = "Transparent" "RenderType" = "Transparent" "IgnoreProjector" = "True" }
        Blend SrcAlpha One
        ZWrite Off
        Cull Off

        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "UnityCG.cginc"
            #include "SubroutineCityCommon.cginc"

            fixed4 _Color;
            float _Speed;
            float _Dash;
            float _Intensity;
            float _Stall;

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
                float t = _Time.y * (1.0 - _Stall);
                float dash = frac(i.uv.x * _Dash - t * _Speed);
                float head = smoothstep(0.0, 0.1, dash) * (1.0 - smoothstep(0.35, 0.55, dash));
                float core = 1.0 - abs(i.uv.y - 0.5) * 2.0;
                core = pow(saturate(core), 1.5);
                // При остановке потока цепь мерцает — движения нет, но напряжение есть.
                float stallFlicker = lerp(1.0, 0.6 + 0.4 * sc_hash11(floor(_Time.y * 12.0)), _Stall);
                float alpha = saturate((0.25 + head * 0.9) * core * stallFlicker) * i.color.a;
                return fixed4(_Color.rgb * i.color.rgb * _Intensity, alpha);
            }
            ENDCG
        }
    }
    FallBack Off
}
