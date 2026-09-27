using SubroutineCity.Core.City;
using UnityEngine;

namespace SubroutineCity
{
    /// <summary>Мост между типами Core (без UnityEngine) и типами Unity.</summary>
    public static class UnityConvert
    {
        public static Color ToColor(this Rgba c) => new Color(c.R, c.G, c.B, c.A);

        public static Color WithAlpha(this Rgba c, float a) => new Color(c.R, c.G, c.B, a);

        public static Vector3 ToWorld(this Vec2 v, float y = 0f) => new Vector3(v.X, y, v.Z);

        public static string Hex(this Rgba c)
        {
            return ColorUtility.ToHtmlStringRGB(c.ToColor());
        }
    }
}
