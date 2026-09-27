#ifndef SUBROUTINE_CITY_COMMON_INCLUDED
#define SUBROUTINE_CITY_COMMON_INCLUDED

// Общие функции шейдеров Subroutine City (хэши без текстур — одинаково на всех платформах).

float sc_hash11(float p)
{
    p = frac(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return frac(p);
}

float sc_hash21(float2 p)
{
    float3 p3 = frac(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

float sc_noise21(float2 p)
{
    float2 i = floor(p);
    float2 f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = sc_hash21(i);
    float b = sc_hash21(i + float2(1, 0));
    float c = sc_hash21(i + float2(0, 1));
    float d = sc_hash21(i + float2(1, 1));
    return lerp(lerp(a, b, u.x), lerp(c, d, u.x), u.y);
}

// Пульсация 0..1 с частотой hz (hz = 0 → константа 1).
float sc_pulse(float t, float hz)
{
    return hz <= 0.0001 ? 1.0 : 0.5 + 0.5 * sin(t * hz * 6.2831853);
}

#endif
