using System.Collections.Generic;
using SubroutineCity.Core.City;
using SubroutineCity.Core.Editing;
using UnityEngine;

namespace SubroutineCity.UI
{
    /// <summary>
    /// Визуальная тема IMGUI: стили и текстуры создаются в коде — никаких ассетов, работает в любом проекте.
    /// Кириллица: системные динамические шрифты (Arial/Segoe/DejaVu); моноширинный — первый найденный из списка.
    /// </summary>
    public sealed class Theme
    {
        public static readonly string[] MonospaceFonts =
            { "JetBrains Mono", "Cascadia Mono", "Consolas", "DejaVu Sans Mono", "Menlo", "Liberation Mono", "Courier New" };

        public const int CodeFontSize = 15;

        public readonly Dictionary<TokenKind, GUIStyle> Tokens = new Dictionary<TokenKind, GUIStyle>();

        public GUIStyle Panel;
        public GUIStyle PanelFlat;
        public GUIStyle Title;
        public GUIStyle Heading;
        public GUIStyle Subheading;
        public GUIStyle Body;
        public GUIStyle BodyDim;
        public GUIStyle Small;
        public GUIStyle SmallDim;
        public GUIStyle Mono;
        public GUIStyle MonoSmall;
        /// <summary>Моноширинный без rich text — для кода и вывода программы (там могут встретиться «&lt;b&gt;» и т.п.).</summary>
        public GUIStyle MonoPlain;
        public GUIStyle MonoSmallPlain;
        public GUIStyle Button;
        public GUIStyle ButtonPrimary;
        public GUIStyle ButtonGhost;
        public GUIStyle Tab;
        public GUIStyle TabActive;
        public GUIStyle ListItem;
        public GUIStyle ListItemSelected;
        public GUIStyle Tooltip;
        public GUIStyle TextField;
        public GUIStyle Badge;
        public GUIStyle CodeText;
        public GUIStyle LineNumber;
        public GUIStyle EditorBackground;
        public GUIStyle Link;

        public Texture2D White;
        public Texture2D Squiggle;
        public Font CodeFont;

        public bool Ready { get; private set; }

        /// <summary>Создаётся лениво внутри OnGUI (раньше GUI.skin недоступен).</summary>
        public void EnsureBuilt()
        {
            if (Ready) return;
            White = Solid(Color.white);
            Squiggle = BuildSquiggle();
            CodeFont = Font.CreateDynamicFontFromOSFont(MonospaceFonts, CodeFontSize);
            Font uiFont = GUI.skin.font;

            Color text = Palette.Text.ToColor();
            Color dim = Palette.TextDim.ToColor();
            Color cyan = Palette.Cyan.ToColor();

            Panel = Box(Frame(new Color(0.03f, 0.06f, 0.11f, 0.94f), new Color(0.13f, 0.88f, 1f, 0.35f)), 14);
            PanelFlat = Box(Solid(new Color(0.02f, 0.04f, 0.08f, 0.96f)), 10);
            EditorBackground = Box(Frame(new Color(0.015f, 0.025f, 0.05f, 0.97f), new Color(0.13f, 0.88f, 1f, 0.25f)), 0);

            Title = Label(uiFont, 34, cyan, FontStyle.Bold);
            Heading = Label(uiFont, 22, text, FontStyle.Bold);
            Subheading = Label(uiFont, 17, cyan, FontStyle.Bold);
            Body = Label(uiFont, 16, text, FontStyle.Normal);
            Body.wordWrap = true;
            BodyDim = Label(uiFont, 15, dim, FontStyle.Normal);
            BodyDim.wordWrap = true;
            Small = Label(uiFont, 13, text, FontStyle.Normal);
            Small.wordWrap = true;
            SmallDim = Label(uiFont, 13, dim, FontStyle.Normal);
            SmallDim.wordWrap = true;
            Link = Label(uiFont, 14, cyan, FontStyle.Normal);
            Mono = Label(CodeFont, 14, text, FontStyle.Normal);
            Mono.wordWrap = true;
            MonoSmall = Label(CodeFont, 13, text, FontStyle.Normal);
            MonoSmall.wordWrap = true;
            MonoPlain = new GUIStyle(Mono) { richText = false };
            MonoSmallPlain = new GUIStyle(MonoSmall) { richText = false };

            Button = ButtonStyle(uiFont, Frame(new Color(0.06f, 0.1f, 0.18f, 1f), new Color(0.13f, 0.88f, 1f, 0.5f)),
                Frame(new Color(0.09f, 0.17f, 0.28f, 1f), cyan), text);
            ButtonPrimary = ButtonStyle(uiFont, Frame(new Color(0.02f, 0.45f, 0.55f, 1f), cyan),
                Frame(new Color(0.05f, 0.62f, 0.74f, 1f), Color.white), Color.white);
            ButtonPrimary.fontStyle = FontStyle.Bold;
            ButtonGhost = ButtonStyle(uiFont, Solid(new Color(0, 0, 0, 0)), Solid(new Color(0.13f, 0.88f, 1f, 0.12f)), dim);

            Tab = ButtonStyle(uiFont, Solid(new Color(0.03f, 0.05f, 0.09f, 1f)), Solid(new Color(0.06f, 0.1f, 0.16f, 1f)), dim);
            TabActive = ButtonStyle(uiFont, Frame(new Color(0.06f, 0.12f, 0.2f, 1f), cyan, bottomOnly: true),
                Frame(new Color(0.06f, 0.12f, 0.2f, 1f), cyan, bottomOnly: true), cyan);
            TabActive.fontStyle = FontStyle.Bold;

            ListItem = ButtonStyle(uiFont, Solid(new Color(0, 0, 0, 0)), Solid(new Color(0.13f, 0.88f, 1f, 0.08f)), text);
            ListItem.alignment = TextAnchor.MiddleLeft;
            ListItem.richText = true;
            ListItemSelected = ButtonStyle(uiFont, Frame(new Color(0.13f, 0.88f, 1f, 0.14f), cyan), Frame(new Color(0.13f, 0.88f, 1f, 0.2f), cyan), Color.white);
            ListItemSelected.alignment = TextAnchor.MiddleLeft;
            ListItemSelected.richText = true;

            Tooltip = Box(Frame(new Color(0.05f, 0.03f, 0.08f, 0.97f), new Color(1f, 0.23f, 0.36f, 0.8f)), 10);
            Tooltip.font = uiFont;
            Tooltip.fontSize = 14;
            Tooltip.normal.textColor = text;
            Tooltip.wordWrap = true;
            Tooltip.richText = true;
            Tooltip.alignment = TextAnchor.UpperLeft;

            TextField = new GUIStyle(GUI.skin.textField) { font = uiFont, fontSize = 15, padding = new RectOffset(8, 8, 6, 6) };
            TextField.normal.background = Frame(new Color(0.02f, 0.04f, 0.08f, 1f), new Color(0.13f, 0.88f, 1f, 0.4f));
            TextField.focused.background = Frame(new Color(0.03f, 0.06f, 0.11f, 1f), cyan);
            TextField.normal.textColor = TextField.focused.textColor = text;

            Badge = Label(uiFont, 13, Color.white, FontStyle.Bold);
            Badge.alignment = TextAnchor.MiddleCenter;
            Badge.padding = new RectOffset(8, 8, 2, 2);
            Badge.normal.background = White;

            CodeText = Label(CodeFont, CodeFontSize, text, FontStyle.Normal);
            CodeText.richText = false;
            CodeText.clipping = TextClipping.Overflow;
            CodeText.padding = new RectOffset(0, 0, 0, 0);
            LineNumber = Label(CodeFont, CodeFontSize - 1, new Color(0.3f, 0.4f, 0.55f), FontStyle.Normal);
            LineNumber.alignment = TextAnchor.UpperRight;
            LineNumber.padding = new RectOffset(0, 0, 0, 0);

            TokenStyle(TokenKind.Keyword, 0xFF6AC1);
            TokenStyle(TokenKind.PrimitiveType, 0xFF9F43);
            TokenStyle(TokenKind.TypeName, 0x5CE1E6);
            TokenStyle(TokenKind.Identifier, 0xD6E4FF);
            TokenStyle(TokenKind.MethodCall, 0x82AAFF);
            TokenStyle(TokenKind.Number, 0xFFD166);
            TokenStyle(TokenKind.String, 0xA5E844);
            TokenStyle(TokenKind.Char, 0xA5E844);
            TokenStyle(TokenKind.Comment, 0x5C6F91);
            TokenStyle(TokenKind.DocComment, 0x7188B4);
            TokenStyle(TokenKind.Annotation, 0xC792EA);
            TokenStyle(TokenKind.Operator, 0x89DDFF);
            TokenStyle(TokenKind.Punctuation, 0x8A9BBD);
            TokenStyle(TokenKind.Literal, 0xFF9F43);
            TokenStyle(TokenKind.Unknown, 0xFF5370);
            TokenStyle(TokenKind.Whitespace, 0xD6E4FF);
            Ready = true;
        }

        public GUIStyle StyleFor(TokenKind kind) => Tokens.TryGetValue(kind, out GUIStyle style) ? style : CodeText;

        /// <summary>Цветная плашка-статус.</summary>
        public void DrawBadge(Rect rect, string text, Color color)
        {
            Color old = GUI.color;
            GUI.color = new Color(color.r, color.g, color.b, 0.9f);
            GUI.DrawTexture(rect, White);
            GUI.color = old;
            Badge.normal.textColor = color.grayscale > 0.6f ? new Color(0.02f, 0.03f, 0.06f) : Color.white;
            GUI.Label(rect, text, Badge);
        }

        public void FillRect(Rect rect, Color color)
        {
            Color old = GUI.color;
            GUI.color = color;
            GUI.DrawTexture(rect, White);
            GUI.color = old;
        }

        private void TokenStyle(TokenKind kind, int rgb)
        {
            var style = new GUIStyle(CodeText);
            style.normal.textColor = Rgba.Hex(rgb).ToColor();
            if (kind == TokenKind.Keyword) style.fontStyle = FontStyle.Bold;
            if (kind == TokenKind.Comment || kind == TokenKind.DocComment) style.fontStyle = FontStyle.Italic;
            Tokens[kind] = style;
        }

        private static GUIStyle Label(Font font, int size, Color color, FontStyle fontStyle)
        {
            var style = new GUIStyle { font = font, fontSize = size, fontStyle = fontStyle, richText = true };
            style.normal.textColor = color;
            style.padding = new RectOffset(2, 2, 2, 2);
            return style;
        }

        private static GUIStyle Box(Texture2D background, int padding)
        {
            var style = new GUIStyle { border = new RectOffset(2, 2, 2, 2), padding = new RectOffset(padding, padding, padding, padding) };
            style.normal.background = background;
            return style;
        }

        private static GUIStyle ButtonStyle(Font font, Texture2D normal, Texture2D hover, Color text)
        {
            var style = new GUIStyle
            {
                font = font,
                fontSize = 15,
                alignment = TextAnchor.MiddleCenter,
                border = new RectOffset(2, 2, 2, 2),
                padding = new RectOffset(12, 12, 6, 6),
                richText = true
            };
            style.normal.background = normal;
            style.hover.background = hover;
            style.active.background = hover;
            style.normal.textColor = text;
            style.hover.textColor = Color.white;
            style.active.textColor = Color.white;
            return style;
        }

        private static Texture2D Solid(Color color)
        {
            var texture = new Texture2D(1, 1, TextureFormat.RGBA32, false) { hideFlags = HideFlags.DontSave };
            texture.SetPixel(0, 0, color);
            texture.Apply();
            return texture;
        }

        /// <summary>Текстура 8×8 с рамкой в 1 пиксель — растягивается как 9-slice (border = 2).</summary>
        private static Texture2D Frame(Color fill, Color border, bool bottomOnly = false)
        {
            const int size = 8;
            var texture = new Texture2D(size, size, TextureFormat.RGBA32, false)
            {
                hideFlags = HideFlags.DontSave,
                filterMode = FilterMode.Point,
                wrapMode = TextureWrapMode.Clamp
            };
            for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
            {
                bool edge = bottomOnly ? y == 0 : (x == 0 || y == 0 || x == size - 1 || y == size - 1);
                texture.SetPixel(x, y, edge ? border : fill);
            }
            texture.Apply();
            return texture;
        }

        private static Texture2D BuildSquiggle()
        {
            var texture = new Texture2D(6, 3, TextureFormat.RGBA32, false)
            {
                hideFlags = HideFlags.DontSave,
                filterMode = FilterMode.Point,
                wrapMode = TextureWrapMode.Repeat
            };
            var clear = new Color(0, 0, 0, 0);
            for (int y = 0; y < 3; y++)
            for (int x = 0; x < 6; x++)
                texture.SetPixel(x, y, clear);
            int[] wave = { 0, 1, 2, 2, 1, 0 };
            for (int x = 0; x < 6; x++) texture.SetPixel(x, wave[x], Color.white);
            texture.Apply();
            return texture;
        }
    }
}
