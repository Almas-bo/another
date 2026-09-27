// Постобработка (Built-in RP, OnRenderImage): bloom по схеме «порог → понижение → повышение»,
// виньетка и хроматическая аберрация + шум при сбоях (_Glitch из CityPostFx).
Shader "Hidden/SubroutineCity/PostFx"
{
    Properties
    {
        _MainTex ("Кадр", 2D) = "white" {}
    }
    CGINCLUDE
    #include "UnityCG.cginc"
    #include "SubroutineCityCommon.cginc"

    sampler2D _MainTex;
    float4 _MainTex_TexelSize;
    sampler2D _BloomTex;
    float _Threshold;
    float _SoftKnee;
    float _BloomIntensity;
    float _Vignette;
    float _Glitch;

    struct v2f
    {
        float4 pos : SV_POSITION;
        float2 uv : TEXCOORD0;
    };

    v2f vert(appdata_img v)
    {
        v2f o;
        o.pos = UnityObjectToClipPos(v.vertex);
        o.uv = v.texcoord;
        return o;
    }

    half3 sampleBox(float2 uv, float delta)
    {
        float4 o = _MainTex_TexelSize.xyxy * float2(-delta, delta).xxyy;
        half3 s = tex2D(_MainTex, uv + o.xy).rgb + tex2D(_MainTex, uv + o.zy).rgb
                + tex2D(_MainTex, uv + o.xw).rgb + tex2D(_MainTex, uv + o.zw).rgb;
        return s * 0.25;
    }

    half3 prefilter(half3 c)
    {
        half brightness = max(c.r, max(c.g, c.b));
        half knee = _Threshold * _SoftKnee;
        half soft = brightness - _Threshold + knee;
        soft = clamp(soft, 0, 2 * knee);
        soft = soft * soft / (4 * knee + 0.00001);
        half contribution = max(soft, brightness - _Threshold) / max(brightness, 0.00001);
        return c * contribution;
    }
    ENDCG

    SubShader
    {
        Cull Off ZWrite Off ZTest Always

        // 0: порог + понижение
        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            half4 frag(v2f i) : SV_Target { return half4(prefilter(sampleBox(i.uv, 1)), 1); }
            ENDCG
        }
        // 1: понижение
        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            half4 frag(v2f i) : SV_Target { return half4(sampleBox(i.uv, 1), 1); }
            ENDCG
        }
        // 2: повышение (аддитивно поверх уровня ниже)
        Pass
        {
            Blend One One
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            half4 frag(v2f i) : SV_Target { return half4(sampleBox(i.uv, 0.5), 1); }
            ENDCG
        }
        // 3: композит — bloom, аберрация, виньетка, шум сбоя
        Pass
        {
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            half4 frag(v2f i) : SV_Target
            {
                float2 uv = i.uv;
                float t = _Time.y;
                float row = floor(uv.y * 90.0);
                float tear = step(1.0 - 0.08 * _Glitch, sc_hash11(row + floor(t * 20.0)));
                uv.x += (sc_hash11(row * 1.7 + floor(t * 20.0)) - 0.5) * 0.04 * tear * _Glitch;
                float2 shift = (uv - 0.5) * (0.0015 + 0.012 * _Glitch);
                half3 color;
                color.r = tex2D(_MainTex, uv + shift).r;
                color.g = tex2D(_MainTex, uv).g;
                color.b = tex2D(_MainTex, uv - shift).b;
                color += tex2D(_BloomTex, uv).rgb * _BloomIntensity;
                float2 c = uv - 0.5;
                color *= 1.0 - dot(c, c) * _Vignette;
                color += (sc_hash21(uv * 800.0 + t) - 0.5) * 0.06 * _Glitch;
                return half4(color, 1);
            }
            ENDCG
        }
    }
    FallBack Off
}
