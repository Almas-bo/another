using System.Collections.Generic;
using SubroutineCity.Core.Editing;
using SubroutineCity.Core.Localization;
using SubroutineCity.Core.Protocol;
using UnityEngine;

namespace SubroutineCity.UI
{
    /// <summary>
    /// Отрисовка и ввод встроенной IDE поверх <see cref="CodeDocument"/> (вся логика правки — в Core и покрыта тестами).
    /// Ввод — через Event.current: работает и со старой, и с новой системой ввода Unity.
    /// </summary>
    public sealed class CodeEditorView
    {
        private const float GutterWidth = 58f;
        private const float LeftPadding = 8f;
        private const int XCacheLimit = 4000;

        private readonly List<List<Token>> _tokens = new List<List<Token>>();
        private readonly Dictionary<string, float[]> _xCache = new Dictionary<string, float[]>();
        private readonly List<string> _suggestions = new List<string>();
        private CompletionEngine _completion;
        private int _tokensVersion = -1;
        private Vector2 _scroll;
        private float _lineHeight = 18f;
        private float _charWidth = 8f;
        private bool _monospace = true;
        private bool _metricsReady;
        private bool _dragging;
        private bool _completionOpen;
        private int _suggestionIndex;
        private float _blinkStart;
        private bool _revealCaret;
        private int _scrollTarget = -1;
        private Rect _viewRect;
        private CompilationDiagnostic _hovered;
        private Vector2 _hoverPoint;

        public CodeDocument Document { get; private set; }
        public IList<CompilationDiagnostic> Diagnostics { get; set; } = new List<CompilationDiagnostic>();

        /// <summary>Точки останова: номера строк с 1.</summary>
        public HashSet<int> Breakpoints { get; } = new HashSet<int>();

        /// <summary>Текущая строка отладчика (с 1) или -1.</summary>
        public int ExecutionLine { get; set; } = -1;

        /// <summary>Сколько раз исполнялась строка в трассе (тепловая полоса в гаттере).</summary>
        public Dictionary<int, int> LineHits { get; set; }

        public bool Focused { get; set; }
        public bool ReadOnly { get; set; }

        public void SetDocument(CodeDocument document, CompletionEngine completion)
        {
            Document = document;
            _completion = completion;
            _tokensVersion = -1;
            _scroll = Vector2.zero;
            _completionOpen = false;
            Breakpoints.Clear();
            ExecutionLine = -1;
            LineHits = null;
        }

        /// <summary>Перейти к строке (с 1): курсор в начало строки, прокрутка к ней.</summary>
        public void RevealLine(int line)
        {
            if (Document == null || line < 1) return;
            Document.SetCaret(new TextPosition(line - 1, 0), false);
            _revealCaret = true;
        }

        /// <summary>Прокрутить к строке (с 1), не трогая курсор и выделение (отладчик).</summary>
        public void ScrollToLine(int line)
        {
            if (Document == null || line < 1) return;
            _scrollTarget = line;
        }

        public void ToggleBreakpoint(int line)
        {
            if (!Breakpoints.Remove(line)) Breakpoints.Add(line);
        }

        public void Draw(Rect rect, Theme theme)
        {
            if (Document == null) return;
            EnsureMetrics(theme);
            Relex();
            Event e = Event.current;

            if (e.type == EventType.MouseDown && !rect.Contains(e.mousePosition) && !PopupRect().Contains(e.mousePosition))
            {
                Focused = false;
                _completionOpen = false;
            }
            if (Focused && e.type == EventType.KeyDown && HandleKey(e))
            {
                e.Use();
                _blinkStart = Time.realtimeSinceStartup;
                _revealCaret = true;
            }

            GUI.Box(rect, GUIContent.none, theme.EditorBackground);
            var gutter = new Rect(rect.x, rect.y, GutterWidth, rect.height);
            _viewRect = new Rect(rect.x + GutterWidth, rect.y, rect.width - GutterWidth, rect.height);
            theme.FillRect(new Rect(gutter.xMax - 1, gutter.y, 1, gutter.height), new Color(0.13f, 0.88f, 1f, 0.15f));

            float contentWidth = Mathf.Max(_viewRect.width - 16f, MaxLineWidth() + 80f);
            float contentHeight = Document.LineCount * _lineHeight + _viewRect.height * 0.5f;
            if (_revealCaret)
            {
                RevealCaret();
                _revealCaret = false;
            }
            if (_scrollTarget > 0)
            {
                float top = (_scrollTarget - 1) * _lineHeight;
                if (top < _scroll.y || top > _scroll.y + _viewRect.height - _lineHeight * 2f)
                    _scroll.y = Mathf.Max(0f, top - _viewRect.height * 0.35f);
                _scrollTarget = -1;
            }

            _hovered = null;
            _scroll = GUI.BeginScrollView(_viewRect, _scroll, new Rect(0, 0, contentWidth, contentHeight));
            HandleMouse(e, theme);
            DrawContent(theme, contentWidth);
            GUI.EndScrollView();

            DrawGutter(gutter, theme, e);
            DrawCompletion(theme, e);
            DrawTooltip(theme);
        }

        // ------------------------------------------------------------------ отрисовка

        private void DrawContent(Theme theme, float contentWidth)
        {
            int first = Mathf.Max(0, Mathf.FloorToInt(_scroll.y / _lineHeight));
            int last = Mathf.Min(Document.LineCount - 1, first + Mathf.CeilToInt(_viewRect.height / _lineHeight) + 1);
            TextPosition selStart = Document.SelectionStart;
            TextPosition selEnd = Document.SelectionEnd;

            for (int line = first; line <= last; line++)
            {
                float y = line * _lineHeight;
                string text = Document.Line(line);
                if (line + 1 == ExecutionLine)
                    theme.FillRect(new Rect(0, y, contentWidth, _lineHeight), new Color(1f, 0.69f, 0.13f, 0.22f));
                else if (Focused && line == Document.Caret.Line && !Document.HasSelection)
                    theme.FillRect(new Rect(0, y, contentWidth, _lineHeight), new Color(0.13f, 0.88f, 1f, 0.05f));

                if (Document.HasSelection && line >= selStart.Line && line <= selEnd.Line)
                {
                    int from = line == selStart.Line ? selStart.Column : 0;
                    int to = line == selEnd.Line ? selEnd.Column : text.Length;
                    float x0 = XOf(text, from);
                    float x1 = XOf(text, to) + (line < selEnd.Line ? _charWidth * 0.6f : 0f);
                    theme.FillRect(new Rect(LeftPadding + x0, y, Mathf.Max(2f, x1 - x0), _lineHeight), new Color(0.13f, 0.55f, 1f, 0.35f));
                }

                List<Token> tokens = line < _tokens.Count ? _tokens[line] : null;
                if (tokens != null)
                {
                    foreach (Token token in tokens)
                    {
                        if (token.Kind == TokenKind.Whitespace) continue;
                        float x0 = XOf(text, token.Start);
                        float x1 = XOf(text, token.End);
                        GUI.Label(new Rect(LeftPadding + x0, y, x1 - x0 + 4f, _lineHeight),
                            text.Substring(token.Start, token.Length), theme.StyleFor(token.Kind));
                    }
                }
            }

            DrawDiagnostics(theme, first, last);

            if (Focused && ((Time.realtimeSinceStartup - _blinkStart) % 1.0f) < 0.6f)
            {
                TextPosition caret = Document.Caret;
                float x = XOf(Document.Line(caret.Line), caret.Column);
                theme.FillRect(new Rect(LeftPadding + x, caret.Line * _lineHeight + 1, 2f, _lineHeight - 2), new Color(0.13f, 0.88f, 1f));
            }
        }

        private void DrawDiagnostics(Theme theme, int first, int last)
        {
            Vector2 mouse = Event.current.mousePosition;
            foreach (CompilationDiagnostic diagnostic in Diagnostics)
            {
                if (diagnostic.Kind != DiagnosticKind.ERROR && diagnostic.Kind != DiagnosticKind.WARNING) continue;
                if (!Range(diagnostic, out TextPosition from, out TextPosition to)) continue;
                Color color = diagnostic.Kind == DiagnosticKind.ERROR ? new Color(1f, 0.25f, 0.35f) : new Color(1f, 0.75f, 0.2f);
                for (int line = Mathf.Max(first, from.Line); line <= Mathf.Min(last, to.Line); line++)
                {
                    string text = Document.Line(line);
                    int c0 = line == from.Line ? from.Column : 0;
                    int c1 = line == to.Line ? to.Column : text.Length;
                    float x0 = LeftPadding + XOf(text, c0);
                    float x1 = LeftPadding + Mathf.Max(XOf(text, c1), XOf(text, c0) + _charWidth);
                    var squiggle = new Rect(x0, (line + 1) * _lineHeight - 3f, x1 - x0, 3f);
                    Color old = GUI.color;
                    GUI.color = color;
                    GUI.DrawTextureWithTexCoords(squiggle, theme.Squiggle, new Rect(0, 0, squiggle.width / 6f, 1f));
                    GUI.color = old;
                    var hover = new Rect(x0, line * _lineHeight, x1 - x0, _lineHeight);
                    if (hover.Contains(mouse))
                    {
                        _hovered = diagnostic;
                        _hoverPoint = GUIUtility.GUIToScreenPoint(mouse);
                    }
                }
            }
        }

        private void DrawGutter(Rect gutter, Theme theme, Event e)
        {
            GUI.BeginGroup(gutter);
            int first = Mathf.Max(0, Mathf.FloorToInt(_scroll.y / _lineHeight));
            int last = Mathf.Min(Document.LineCount - 1, first + Mathf.CeilToInt(gutter.height / _lineHeight) + 1);
            var errorLines = new Dictionary<int, DiagnosticKind>();
            foreach (var diagnostic in Diagnostics)
            {
                if (diagnostic.Line < 1) continue;
                int line = (int)diagnostic.Line;
                if (!errorLines.ContainsKey(line) || diagnostic.Kind == DiagnosticKind.ERROR) errorLines[line] = diagnostic.Kind;
            }
            int maxHits = 1;
            if (LineHits != null)
                foreach (int hits in LineHits.Values) maxHits = Mathf.Max(maxHits, hits);

            for (int i = first; i <= last; i++)
            {
                int line = i + 1;
                float y = i * _lineHeight - _scroll.y;
                if (LineHits != null && LineHits.TryGetValue(line, out int count))
                {
                    float w = 4f + 12f * Mathf.Log(1 + count) / Mathf.Log(1 + maxHits);
                    theme.FillRect(new Rect(gutter.width - 3f - w, y + 3f, w, _lineHeight - 6f), new Color(1f, 0.55f, 0.1f, 0.35f));
                }
                GUI.Label(new Rect(0, y, gutter.width - 22f, _lineHeight), line.ToString(), theme.LineNumber);
                if (Breakpoints.Contains(line))
                    theme.FillRect(new Rect(gutter.width - 17f, y + _lineHeight / 2f - 5f, 10f, 10f), new Color(1f, 0.23f, 0.36f));
                if (errorLines.TryGetValue(line, out DiagnosticKind kind))
                    theme.FillRect(new Rect(2f, y + 2f, 3f, _lineHeight - 4f),
                        kind == DiagnosticKind.ERROR ? new Color(1f, 0.25f, 0.35f) : new Color(1f, 0.75f, 0.2f));
                if (line == ExecutionLine)
                    theme.FillRect(new Rect(gutter.width - 6f, y + 2f, 4f, _lineHeight - 4f), new Color(1f, 0.69f, 0.13f));
            }
            if (e.type == EventType.MouseDown && e.button == 0 && new Rect(0, 0, gutter.width, gutter.height).Contains(e.mousePosition))
            {
                int line = Mathf.FloorToInt((e.mousePosition.y + _scroll.y) / _lineHeight) + 1;
                if (line >= 1 && line <= Document.LineCount) ToggleBreakpoint(line);
                e.Use();
            }
            GUI.EndGroup();
        }

        private Rect PopupRect()
        {
            if (!_completionOpen || Document == null) return Rect.zero;
            TextPosition caret = Document.Caret;
            float x = _viewRect.x + LeftPadding + XOf(Document.Line(caret.Line), caret.Column) - _scroll.x;
            float y = _viewRect.y + (caret.Line + 1) * _lineHeight - _scroll.y + 2f;
            return new Rect(x, y, 280f, _suggestions.Count * 22f + 8f);
        }

        private void DrawCompletion(Theme theme, Event e)
        {
            if (!_completionOpen || _suggestions.Count == 0) return;
            Rect popup = PopupRect();
            GUI.Box(popup, GUIContent.none, theme.PanelFlat);
            for (int i = 0; i < _suggestions.Count; i++)
            {
                var item = new Rect(popup.x + 4, popup.y + 4 + i * 22f, popup.width - 8, 22f);
                if (i == _suggestionIndex) theme.FillRect(item, new Color(0.13f, 0.88f, 1f, 0.22f));
                GUI.Label(item, _suggestions[i], theme.CodeText);
                if (e.type == EventType.MouseDown && item.Contains(e.mousePosition))
                {
                    _suggestionIndex = i;
                    AcceptCompletion();
                    e.Use();
                }
            }
        }

        private void DrawTooltip(Theme theme)
        {
            if (_hovered == null) return;
            string original = _hovered.Message.Split('\n')[0];
            string text = "<b>" + Ru.ExplainDiagnostic(_hovered) + "</b>\n<color=#7C8FB0>javac: " + Escape(original) + "</color>";
            Vector2 point = GUIUtility.ScreenToGUIPoint(_hoverPoint);
            float width = 420f;
            float height = theme.Tooltip.CalcHeight(new GUIContent(text), width);
            GUI.Box(new Rect(point.x + 12f, point.y + 18f, width, height), text, theme.Tooltip);
        }

        // ------------------------------------------------------------------ ввод

        private void HandleMouse(Event e, Theme theme)
        {
            Vector2 local = e.mousePosition;
            var contentArea = new Rect(_scroll.x, _scroll.y, _viewRect.width, _viewRect.height);
            if (e.type == EventType.MouseDown && e.button == 0 && contentArea.Contains(local))
            {
                Focused = true;
                GUIUtility.keyboardControl = 0;
                _completionOpen = false;
                TextPosition position = PositionAt(local);
                if (e.clickCount == 2) Document.SelectWordAt(position);
                else Document.SetCaret(position, e.shift);
                _dragging = e.clickCount == 1;
                _blinkStart = Time.realtimeSinceStartup;
                e.Use();
            }
            else if (e.type == EventType.MouseDrag && _dragging)
            {
                Document.SetCaret(PositionAt(local), true);
                e.Use();
            }
            else if (e.type == EventType.MouseUp && _dragging)
            {
                _dragging = false;
                e.Use();
            }
        }

        private bool HandleKey(Event e)
        {
            if (Document == null) return false;
            bool ctrl = e.control || e.command;
            bool shift = e.shift;

            if (_completionOpen)
            {
                switch (e.keyCode)
                {
                    case KeyCode.UpArrow:
                        _suggestionIndex = (_suggestionIndex + _suggestions.Count - 1) % _suggestions.Count;
                        return true;
                    case KeyCode.DownArrow:
                        _suggestionIndex = (_suggestionIndex + 1) % _suggestions.Count;
                        return true;
                    case KeyCode.Return:
                    case KeyCode.KeypadEnter:
                    case KeyCode.Tab:
                        AcceptCompletion();
                        return true;
                    case KeyCode.Escape:
                        _completionOpen = false;
                        return true;
                }
            }

            int page = Mathf.Max(1, Mathf.FloorToInt(_viewRect.height / _lineHeight) - 1);
            switch (e.keyCode)
            {
                case KeyCode.LeftArrow: Document.MoveLeft(shift, ctrl); CloseCompletion(); return true;
                case KeyCode.RightArrow: Document.MoveRight(shift, ctrl); CloseCompletion(); return true;
                case KeyCode.UpArrow: Document.MoveVertical(-1, shift); return true;
                case KeyCode.DownArrow: Document.MoveVertical(1, shift); return true;
                case KeyCode.PageUp: Document.MoveVertical(-page, shift); return true;
                case KeyCode.PageDown: Document.MoveVertical(page, shift); return true;
                case KeyCode.Home:
                    if (ctrl) Document.MoveDocumentStart(shift);
                    else Document.MoveHome(shift);
                    return true;
                case KeyCode.End:
                    if (ctrl) Document.MoveDocumentEnd(shift);
                    else Document.MoveEnd(shift);
                    return true;
                case KeyCode.Escape:
                    CloseCompletion();
                    return true;
            }

            if (ctrl && !e.alt)
            {
                switch (e.keyCode)
                {
                    case KeyCode.A: Document.SelectAll(); return true;
                    case KeyCode.C:
                        if (Document.HasSelection) GUIUtility.systemCopyBuffer = Document.SelectedText;
                        return true;
                    case KeyCode.X:
                        if (!ReadOnly && Document.HasSelection)
                        {
                            GUIUtility.systemCopyBuffer = Document.SelectedText;
                            Document.Backspace();
                        }
                        return true;
                    case KeyCode.V:
                        if (!ReadOnly) Document.InsertText(GUIUtility.systemCopyBuffer);
                        return true;
                    case KeyCode.Z:
                        if (!ReadOnly)
                        {
                            if (shift) Document.Redo();
                            else Document.Undo();
                        }
                        return true;
                    case KeyCode.Y:
                        if (!ReadOnly) Document.Redo();
                        return true;
                    case KeyCode.Slash:
                        if (!ReadOnly) Document.ToggleComment();
                        return true;
                    case KeyCode.Space:
                        OpenCompletion(force: true);
                        return true;
                }
            }

            if (ReadOnly) return e.character != '\0';
            switch (e.keyCode)
            {
                case KeyCode.Backspace: Document.Backspace(); UpdateCompletionAfterEdit(); return true;
                case KeyCode.Delete: Document.Delete(); CloseCompletion(); return true;
                case KeyCode.Return:
                case KeyCode.KeypadEnter: Document.NewLine(); CloseCompletion(); return true;
                case KeyCode.Tab:
                    if (shift) Document.Outdent();
                    else Document.Indent();
                    return true;
            }

            char c = e.character;
            bool altGr = e.alt && e.control;
            if (c != '\0' && !char.IsControl(c) && (!ctrl || altGr))
            {
                Document.Type(c);
                UpdateCompletionAfterEdit();
                return true;
            }
            // Символы Enter/Tab приходят отдельным событием — они уже обработаны по keyCode.
            return c == '\n' || c == '\t' || c == '\r';
        }

        private void UpdateCompletionAfterEdit()
        {
            string prefix = Document.WordBeforeCaret();
            if (prefix.Length >= 2) OpenCompletion(force: false);
            else CloseCompletion();
        }

        private void OpenCompletion(bool force)
        {
            if (_completion == null) return;
            _suggestions.Clear();
            _suggestions.AddRange(_completion.Suggest(Document, 8));
            _completionOpen = _suggestions.Count > 0 && (force || Document.WordBeforeCaret().Length >= 2);
            _suggestionIndex = 0;
        }

        private void AcceptCompletion()
        {
            if (_suggestionIndex >= 0 && _suggestionIndex < _suggestions.Count)
                Document.ReplaceWordBeforeCaret(_suggestions[_suggestionIndex]);
            CloseCompletion();
        }

        private void CloseCompletion()
        {
            _completionOpen = false;
        }

        // ------------------------------------------------------------------ геометрия

        private void EnsureMetrics(Theme theme)
        {
            if (_metricsReady) return;
            GUIStyle style = theme.CodeText;
            _measureStyle = new GUIStyle(style) { padding = new RectOffset(0, 0, 0, 0) };
            float wide = style.CalcSize(new GUIContent("MMMMMMMMMM")).x;
            float narrow = style.CalcSize(new GUIContent("iiiiiiiiii")).x;
            float cyrillic = style.CalcSize(new GUIContent("жжжжжжжжжж")).x;
            _charWidth = wide / 10f;
            _monospace = Mathf.Abs(wide - narrow) < 1f && Mathf.Abs(wide - cyrillic) < 1f;
            _lineHeight = Mathf.Ceil(Mathf.Max(style.lineHeight, style.CalcSize(new GUIContent("Жg")).y) + 3f);
            _metricsReady = true;
        }

        /// <summary>Горизонтальная позиция столбца. Моноширинный шрифт — умножение; иначе — измерение префиксов с кэшем.</summary>
        private float XOf(string line, int column)
        {
            column = Mathf.Clamp(column, 0, line.Length);
            if (_monospace) return column * _charWidth;
            if (!_xCache.TryGetValue(line, out float[] xs))
            {
                if (_xCache.Count > XCacheLimit) _xCache.Clear();
                xs = new float[line.Length + 1];
                for (int i = 1; i <= line.Length; i++)
                    xs[i] = CodeStyleWidth(line.Substring(0, i));
                _xCache[line] = xs;
            }
            return xs[column];
        }

        private GUIStyle _measureStyle;

        /// <summary>Ширина префикса строки тем же шрифтом, которым рисуется код (пробелы — неразрывные, чтобы не схлопывались).</summary>
        private float CodeStyleWidth(string text)
        {
            return _measureStyle.CalcSize(new GUIContent(text.Replace(' ', '\u00A0'))).x;
        }

        private TextPosition PositionAt(Vector2 local)
        {
            int line = Mathf.Clamp(Mathf.FloorToInt(local.y / _lineHeight), 0, Document.LineCount - 1);
            string text = Document.Line(line);
            float x = local.x - LeftPadding;
            int column = 0;
            while (column < text.Length && XOf(text, column + 1) - (XOf(text, column + 1) - XOf(text, column)) / 2f < x) column++;
            return new TextPosition(line, column);
        }

        private float MaxLineWidth()
        {
            int longest = 0;
            for (int i = 0; i < Document.LineCount; i++) longest = Mathf.Max(longest, Document.Line(i).Length);
            return longest * _charWidth;
        }

        private void RevealCaret()
        {
            TextPosition caret = Document.Caret;
            float top = caret.Line * _lineHeight;
            float bottom = top + _lineHeight;
            if (top < _scroll.y) _scroll.y = top;
            else if (bottom > _scroll.y + _viewRect.height - 16f) _scroll.y = bottom - _viewRect.height + 16f;
            float x = LeftPadding + XOf(Document.Line(caret.Line), caret.Column);
            if (x < _scroll.x + 20f) _scroll.x = Mathf.Max(0f, x - 40f);
            else if (x > _scroll.x + _viewRect.width - 40f) _scroll.x = x - _viewRect.width + 80f;
        }

        private void Relex()
        {
            if (_tokensVersion == Document.Version) return;
            LexState state = LexState.Normal;
            for (int i = 0; i < Document.LineCount; i++)
            {
                if (i >= _tokens.Count) _tokens.Add(new List<Token>());
                state = JavaLexer.TokenizeLine(Document.Line(i), state, _tokens[i]);
            }
            if (_tokens.Count > Document.LineCount) _tokens.RemoveRange(Document.LineCount, _tokens.Count - Document.LineCount);
            _tokensVersion = Document.Version;
        }

        /// <summary>Диапазон диагностики в документе: по смещениям javac или по строке/столбцу.</summary>
        private bool Range(CompilationDiagnostic diagnostic, out TextPosition from, out TextPosition to)
        {
            if (diagnostic.StartPosition >= 0 && diagnostic.EndPosition > diagnostic.StartPosition)
            {
                from = Document.PositionOf((int)diagnostic.StartPosition);
                to = Document.PositionOf((int)diagnostic.EndPosition);
                return true;
            }
            if (diagnostic.Line >= 1 && diagnostic.Line <= Document.LineCount)
            {
                int line = (int)diagnostic.Line - 1;
                int column = Mathf.Clamp((int)diagnostic.Column - 1, 0, Document.Line(line).Length);
                string text = Document.Line(line);
                int end = column;
                while (end < text.Length && JavaLexer.IsIdentifierPart(text[end])) end++;
                from = new TextPosition(line, column);
                to = new TextPosition(line, Mathf.Max(end, column + 1));
                return true;
            }
            from = to = default;
            return false;
        }

        private static string Escape(string text) => text.Replace("<", "‹").Replace(">", "›");
    }
}
