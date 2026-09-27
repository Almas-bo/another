#!/usr/bin/env python3
"""
Синтаксическая и типовая проверка HLSL-части шейдеров Subroutine City вне Unity.

Каждый блок CGPROGRAM (вместе с CGINCLUDE и локальными .cginc) компилируется glslangValidator
(HLSL-фронтенд → SPIR-V) для вершинной и фрагментной стадий. Вместо UnityCG.cginc подставляются
заглушки с теми же сигнатурами. ShaderLab-обёртку (Properties, Blend, Tags) проверяет только сам Unity.

Запуск: python3 unity/Verify/shaders/check_shaders.py
"""
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2] / "SubroutineCity/Assets/SubroutineCity/Resources/Shaders"

UNITY_STUBS = r"""
#define fixed half
#define fixed2 half2
#define fixed3 half3
#define fixed4 half4
float4 _Time;
float3 _WorldSpaceCameraPos;
float4x4 unity_ObjectToWorld;
float4x4 unity_WorldToObject;
float4x4 unity_MatrixVP;
float4 UnityObjectToClipPos(float4 v) { return mul(unity_MatrixVP, mul(unity_ObjectToWorld, float4(v.xyz, 1.0))); }
float4 UnityObjectToClipPos(float3 v) { return UnityObjectToClipPos(float4(v, 1.0)); }
float3 UnityObjectToWorldNormal(float3 n) { return normalize(mul(n, (float3x3)unity_WorldToObject)); }
struct appdata_img { float4 vertex : POSITION; float2 texcoord : TEXCOORD0; };
"""


def blocks(text, keyword):
    return re.findall(keyword + r"(.*?)ENDCG", text, re.S)


def resolve_includes(source, directory):
    def replace(match):
        name = match.group(1)
        if name == "UnityCG.cginc":
            return ""
        return resolve_includes((directory / name).read_text(encoding="utf-8"), directory)
    return re.sub(r'#include\s+"([^"]+)"', replace, source)


def main():
    failures = 0
    checked = 0
    for shader in sorted(ROOT.glob("*.shader")):
        text = shader.read_text(encoding="utf-8")
        include = "\n".join(blocks(text, "CGINCLUDE"))
        for index, program in enumerate(blocks(text, "CGPROGRAM")):
            vertex = re.search(r"#pragma\s+vertex\s+(\w+)", program).group(1)
            fragment = re.search(r"#pragma\s+fragment\s+(\w+)", program).group(1)
            body = re.sub(r"#pragma[^\n]*", "", include + "\n" + program)
            source = UNITY_STUBS + resolve_includes(body, ROOT)
            with tempfile.NamedTemporaryFile("w", suffix=".hlsl", delete=False, encoding="utf-8") as handle:
                handle.write(source)
                path = handle.name
            for stage, entry in (("vert", vertex), ("frag", fragment)):
                checked += 1
                result = subprocess.run(
                    ["glslangValidator", "-D", "-V", "-S", stage, "-e", entry, "-o", "/dev/null", path],
                    capture_output=True, text=True)
                label = f"{shader.name} [программа {index}] {stage}:{entry}"
                if result.returncode != 0:
                    failures += 1
                    print(f"ОШИБКА  {label}\n{result.stdout}{result.stderr}")
                else:
                    print(f"ok      {label}")
    print(f"\nПроверено стадий: {checked}, ошибок: {failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
