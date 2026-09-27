using System;
using System.Collections.Generic;
using System.Text;

namespace SubroutineCity.Core.Editing
{
    /// <summary>
    /// Модель редактора кода: строки, курсор, выделение, отмена/повтор, автоотступы.
    /// Не зависит от Unity — вся логика редактирования покрыта модульными тестами, а слой Unity только рисует
    /// документ и переводит события клавиатуры в вызовы методов.
    /// Табуляция всегда превращается в пробелы: столбцы javac и редактора совпадают.
    /// </summary>
    public sealed class CodeDocument
    {
        public const int IndentSize = 4;
        public const int MaxUndo = 200;

        private enum EditKind { None, Typing, Other }

        private struct Snapshot
        {
            public string Text;
            public TextPosition Caret;
            public TextPosition Anchor;
        }

        private readonly List<string> _lines = new List<string> { "" };
        private readonly LinkedList<Snapshot> _undo = new LinkedList<Snapshot>();
        private readonly Stack<Snapshot> _redo = new Stack<Snapshot>();
        private EditKind _lastEdit = EditKind.None;
        private char _lastTyped;
        private int _preferredColumn = -1;

        public CodeDocument(string text = "")
        {
            SetTextInternal(text ?? "");
        }

        /// <summary>Увеличивается при каждом изменении текста (для кэшей подсветки и отложенной проверки).</summary>
        public int Version { get; private set; }

        public TextPosition Caret { get; private set; }

        /// <summary>Второй конец выделения; выделения нет, если совпадает с курсором.</summary>
        public TextPosition Anchor { get; private set; }

        public int LineCount => _lines.Count;

        public string Line(int index) => _lines[index];

        public bool HasSelection => Caret != Anchor;

        public TextPosition SelectionStart => TextPosition.Min(Caret, Anchor);

        public TextPosition SelectionEnd => TextPosition.Max(Caret, Anchor);

        public bool CanUndo => _undo.Count > 0;

        public bool CanRedo => _redo.Count > 0;

        public string Text
        {
            get => string.Join("\n", _lines);
            set
            {
                SetTextInternal(value ?? "");
                _undo.Clear();
                _redo.Clear();
                _lastEdit = EditKind.None;
            }
        }

        public string SelectedText => HasSelection ? GetRange(SelectionStart, SelectionEnd) : "";

        // ------------------------------------------------------------------ правка

        /// <summary>Ввод одного печатного символа.</summary>
        public void Type(char c)
        {
            if (c == '\n' || c == '\r')
            {
                NewLine();
                return;
            }
            if (c == '\t')
            {
                Indent();
                return;
            }
            if (char.IsControl(c)) return;

            // одно слово = одна операция отмены: пробел или знак начинает новую группу
            bool coalesce = _lastEdit == EditKind.Typing && !HasSelection && JavaLexer.IsIdentifierPart(c)
                            && JavaLexer.IsIdentifierPart(_lastTyped);
            if (!coalesce) PushUndo();
            DeleteSelectionInternal();

            string line = _lines[Caret.Line];
            // '}' в начале строки из одних пробелов — сдвинуть на уровень влево
            if (c == '}' && line.Substring(0, Caret.Column).Trim().Length == 0 && Caret.Column >= IndentSize
                && line.Substring(Caret.Column).Trim().Length == 0)
            {
                int remove = Math.Min(IndentSize, Caret.Column);
                _lines[Caret.Line] = line.Remove(Caret.Column - remove, remove);
                Caret = new TextPosition(Caret.Line, Caret.Column - remove);
                line = _lines[Caret.Line];
            }

            _lines[Caret.Line] = line.Insert(Caret.Column, c.ToString());
            Caret = new TextPosition(Caret.Line, Caret.Column + 1);
            Anchor = Caret;
            _lastEdit = EditKind.Typing;
            _lastTyped = c;
            Touched();
        }

        /// <summary>Вставка текста (буфер обмена, автодополнение). Переводы строк нормализуются, табы — в пробелы.</summary>
        public void InsertText(string text)
        {
            if (string.IsNullOrEmpty(text) && !HasSelection) return;
            PushUndo();
            DeleteSelectionInternal();
            InsertInternal(Normalize(text ?? ""));
            _lastEdit = EditKind.Other;
            Touched();
        }

        /// <summary>Enter с автоотступом; между «{» и «}» раскрывает блок.</summary>
        public void NewLine()
        {
            PushUndo();
            DeleteSelectionInternal();
            string line = _lines[Caret.Line];
            string before = line.Substring(0, Caret.Column);
            string after = line.Substring(Caret.Column);
            string indent = LeadingWhitespace(line);
            if (indent.Length > Caret.Column) indent = indent.Substring(0, Caret.Column);
            string trimmedBefore = before.TrimEnd();
            bool opensBlock = trimmedBefore.EndsWith("{", StringComparison.Ordinal)
                              || trimmedBefore.EndsWith("(", StringComparison.Ordinal)
                              || trimmedBefore.EndsWith("[", StringComparison.Ordinal);
            string innerIndent = opensBlock ? indent + new string(' ', IndentSize) : indent;
            string trimmedAfter = after.TrimStart();

            _lines[Caret.Line] = before.TrimEnd();
            if (opensBlock && trimmedAfter.Length > 0 && "}])".IndexOf(trimmedAfter[0]) >= 0)
            {
                _lines.Insert(Caret.Line + 1, innerIndent);
                _lines.Insert(Caret.Line + 2, indent + trimmedAfter);
            }
            else
            {
                _lines.Insert(Caret.Line + 1, innerIndent + trimmedAfter);
            }
            Caret = new TextPosition(Caret.Line + 1, innerIndent.Length);
            Anchor = Caret;
            _lastEdit = EditKind.Other;
            Touched();
        }

        public void Backspace()
        {
            if (HasSelection)
            {
                PushUndo();
                DeleteSelectionInternal();
                _lastEdit = EditKind.Other;
                Touched();
                return;
            }
            if (Caret.Column == 0 && Caret.Line == 0) return;
            PushUndo();
            if (Caret.Column == 0)
            {
                int previous = Caret.Line - 1;
                int column = _lines[previous].Length;
                _lines[previous] += _lines[Caret.Line];
                _lines.RemoveAt(Caret.Line);
                Caret = new TextPosition(previous, column);
            }
            else
            {
                string line = _lines[Caret.Line];
                int remove = 1;
                // в ведущих пробелах удаляем до предыдущей позиции табуляции
                if (line.Substring(0, Caret.Column).Trim().Length == 0)
                {
                    remove = Caret.Column % IndentSize == 0 ? IndentSize : Caret.Column % IndentSize;
                    remove = Math.Min(remove, Caret.Column);
                }
                _lines[Caret.Line] = line.Remove(Caret.Column - remove, remove);
                Caret = new TextPosition(Caret.Line, Caret.Column - remove);
            }
            Anchor = Caret;
            _lastEdit = EditKind.Other;
            Touched();
        }

        public void Delete()
        {
            if (HasSelection)
            {
                Backspace();
                return;
            }
            string line = _lines[Caret.Line];
            if (Caret.Column >= line.Length && Caret.Line >= _lines.Count - 1) return;
            PushUndo();
            if (Caret.Column >= line.Length)
            {
                _lines[Caret.Line] = line + _lines[Caret.Line + 1];
                _lines.RemoveAt(Caret.Line + 1);
            }
            else
            {
                _lines[Caret.Line] = line.Remove(Caret.Column, 1);
            }
            Anchor = Caret;
            _lastEdit = EditKind.Other;
            Touched();
        }

        /// <summary>Tab: отступ выделенных строк или пробелы до следующей позиции табуляции.</summary>
        public void Indent()
        {
            PushUndo();
            if (HasSelection && SelectionStart.Line != SelectionEnd.Line)
            {
                ForEachSelectedLine(i => _lines[i] = new string(' ', IndentSize) + _lines[i]);
                Caret = new TextPosition(Caret.Line, Caret.Column + IndentSize);
                Anchor = new TextPosition(Anchor.Line, Anchor.Column + IndentSize);
            }
            else
            {
                DeleteSelectionInternal();
                int spaces = IndentSize - Caret.Column % IndentSize;
                InsertInternal(new string(' ', spaces));
            }
            _lastEdit = EditKind.Other;
            Touched();
        }

        /// <summary>Shift+Tab: убрать уровень отступа у текущей или выделенных строк.</summary>
        public void Outdent()
        {
            PushUndo();
            int caretShift = 0;
            int anchorShift = 0;
            TextPosition caret = Caret;
            TextPosition anchor = Anchor;
            ForEachSelectedLine(i =>
            {
                string line = _lines[i];
                int remove = 0;
                while (remove < IndentSize && remove < line.Length && line[remove] == ' ') remove++;
                _lines[i] = line.Substring(remove);
                if (i == caret.Line) caretShift = remove;
                if (i == anchor.Line) anchorShift = remove;
            });
            Caret = new TextPosition(caret.Line, Math.Max(0, caret.Column - caretShift));
            Anchor = new TextPosition(anchor.Line, Math.Max(0, anchor.Column - anchorShift));
            _lastEdit = EditKind.Other;
            Touched();
        }

        /// <summary>Ctrl+/: закомментировать или раскомментировать строки.</summary>
        public void ToggleComment()
        {
            PushUndo();
            bool allCommented = true;
            ForEachSelectedLine(i =>
            {
                if (_lines[i].Trim().Length > 0 && !_lines[i].TrimStart().StartsWith("//", StringComparison.Ordinal))
                    allCommented = false;
            });
            int minIndent = int.MaxValue;
            ForEachSelectedLine(i =>
            {
                if (_lines[i].Trim().Length > 0) minIndent = Math.Min(minIndent, LeadingWhitespace(_lines[i]).Length);
            });
            if (minIndent == int.MaxValue) minIndent = 0;
            int delta = 0;
            ForEachSelectedLine(i =>
            {
                string line = _lines[i];
                if (allCommented)
                {
                    int at = line.IndexOf("//", StringComparison.Ordinal);
                    if (at < 0) return;
                    int remove = at + 2 < line.Length && line[at + 2] == ' ' ? 3 : 2;
                    _lines[i] = line.Remove(at, remove);
                    delta = -remove;
                }
                else if (line.Trim().Length > 0)
                {
                    _lines[i] = line.Insert(Math.Min(minIndent, line.Length), "// ");
                    delta = 3;
                }
            });
            Caret = new TextPosition(Caret.Line, Clamp(Caret.Column + delta, 0, _lines[Caret.Line].Length));
            Anchor = new TextPosition(Anchor.Line, Clamp(Anchor.Column + delta, 0, _lines[Anchor.Line].Length));
            _lastEdit = EditKind.Other;
            Touched();
        }

        public bool Undo()
        {
            if (_undo.Count == 0) return false;
            _redo.Push(Capture());
            Snapshot snapshot = _undo.Last.Value;
            _undo.RemoveLast();
            Restore(snapshot);
            _lastEdit = EditKind.None;
            return true;
        }

        public bool Redo()
        {
            if (_redo.Count == 0) return false;
            _undo.AddLast(Capture());
            Restore(_redo.Pop());
            _lastEdit = EditKind.None;
            return true;
        }

        // ------------------------------------------------------------------ навигация

        public void SetCaret(TextPosition position, bool extend)
        {
            Caret = ClampPosition(position);
            if (!extend) Anchor = Caret;
            _preferredColumn = -1;
            _lastEdit = EditKind.None;
        }

        public void SelectAll()
        {
            Anchor = new TextPosition(0, 0);
            Caret = new TextPosition(_lines.Count - 1, _lines[_lines.Count - 1].Length);
            _lastEdit = EditKind.None;
        }

        /// <summary>Двойной щелчок: выделить слово под позицией.</summary>
        public void SelectWordAt(TextPosition position)
        {
            position = ClampPosition(position);
            string line = _lines[position.Line];
            int start = position.Column;
            int end = position.Column;
            while (start > 0 && JavaLexer.IsIdentifierPart(line[start - 1])) start--;
            while (end < line.Length && JavaLexer.IsIdentifierPart(line[end])) end++;
            Anchor = new TextPosition(position.Line, start);
            Caret = new TextPosition(position.Line, end);
            _lastEdit = EditKind.None;
        }

        public void MoveLeft(bool extend, bool word)
        {
            if (HasSelection && !extend)
            {
                SetCaret(SelectionStart, false);
                return;
            }
            TextPosition p = Caret;
            if (p.Column > 0)
            {
                int column = p.Column - 1;
                if (word)
                {
                    string line = _lines[p.Line];
                    while (column > 0 && char.IsWhiteSpace(line[column])) column--;
                    bool identifier = JavaLexer.IsIdentifierPart(line[column]);
                    while (column > 0 && JavaLexer.IsIdentifierPart(line[column - 1]) == identifier
                           && !char.IsWhiteSpace(line[column - 1]))
                        column--;
                }
                p = new TextPosition(p.Line, column);
            }
            else if (p.Line > 0)
            {
                p = new TextPosition(p.Line - 1, _lines[p.Line - 1].Length);
            }
            SetCaret(p, extend);
        }

        public void MoveRight(bool extend, bool word)
        {
            if (HasSelection && !extend)
            {
                SetCaret(SelectionEnd, false);
                return;
            }
            TextPosition p = Caret;
            string line = _lines[p.Line];
            if (p.Column < line.Length)
            {
                int column = p.Column + 1;
                if (word)
                {
                    bool identifier = JavaLexer.IsIdentifierPart(line[p.Column]);
                    while (column < line.Length && JavaLexer.IsIdentifierPart(line[column]) == identifier
                           && !char.IsWhiteSpace(line[column]))
                        column++;
                    while (column < line.Length && char.IsWhiteSpace(line[column])) column++;
                }
                p = new TextPosition(p.Line, column);
            }
            else if (p.Line < _lines.Count - 1)
            {
                p = new TextPosition(p.Line + 1, 0);
            }
            SetCaret(p, extend);
        }

        public void MoveVertical(int deltaLines, bool extend)
        {
            int preferred = _preferredColumn >= 0 ? _preferredColumn : Caret.Column;
            int line = Clamp(Caret.Line + deltaLines, 0, _lines.Count - 1);
            var target = new TextPosition(line, Math.Min(preferred, _lines[line].Length));
            SetCaret(target, extend);
            _preferredColumn = preferred;
        }

        /// <summary>Home: сначала к первому непробельному символу, повторно — к началу строки.</summary>
        public void MoveHome(bool extend)
        {
            int indent = LeadingWhitespace(_lines[Caret.Line]).Length;
            SetCaret(new TextPosition(Caret.Line, Caret.Column == indent ? 0 : indent), extend);
        }

        public void MoveEnd(bool extend)
        {
            SetCaret(new TextPosition(Caret.Line, _lines[Caret.Line].Length), extend);
        }

        public void MoveDocumentStart(bool extend) => SetCaret(new TextPosition(0, 0), extend);

        public void MoveDocumentEnd(bool extend) =>
            SetCaret(new TextPosition(_lines.Count - 1, _lines[_lines.Count - 1].Length), extend);

        // ------------------------------------------------------------------ автодополнение

        /// <summary>Идентификатор, который набирается перед курсором ("" — если курсор не после идентификатора).</summary>
        public string WordBeforeCaret()
        {
            string line = _lines[Caret.Line];
            int start = Caret.Column;
            while (start > 0 && JavaLexer.IsIdentifierPart(line[start - 1])) start--;
            return line.Substring(start, Caret.Column - start);
        }

        /// <summary>Заменяет набираемый идентификатор на выбранный вариант.</summary>
        public void ReplaceWordBeforeCaret(string replacement)
        {
            string prefix = WordBeforeCaret();
            PushUndo();
            string line = _lines[Caret.Line];
            _lines[Caret.Line] = line.Remove(Caret.Column - prefix.Length, prefix.Length)
                .Insert(Caret.Column - prefix.Length, replacement);
            Caret = new TextPosition(Caret.Line, Caret.Column - prefix.Length + replacement.Length);
            Anchor = Caret;
            _lastEdit = EditKind.Other;
            Touched();
        }

        // ------------------------------------------------------------------ смещения (позиции javac)

        /// <summary>Смещение символа от начала текста (переводы строк — один символ, как у javac после нормализации).</summary>
        public int OffsetOf(TextPosition position)
        {
            position = ClampPosition(position);
            int offset = 0;
            for (int i = 0; i < position.Line; i++) offset += _lines[i].Length + 1;
            return offset + position.Column;
        }

        public TextPosition PositionOf(int offset)
        {
            if (offset <= 0) return new TextPosition(0, 0);
            for (int i = 0; i < _lines.Count; i++)
            {
                if (offset <= _lines[i].Length) return new TextPosition(i, offset);
                offset -= _lines[i].Length + 1;
            }
            return new TextPosition(_lines.Count - 1, _lines[_lines.Count - 1].Length);
        }

        public string GetRange(TextPosition from, TextPosition to)
        {
            from = ClampPosition(from);
            to = ClampPosition(to);
            if (from > to)
            {
                var swap = from;
                from = to;
                to = swap;
            }
            if (from.Line == to.Line) return _lines[from.Line].Substring(from.Column, to.Column - from.Column);
            var builder = new StringBuilder();
            builder.Append(_lines[from.Line].Substring(from.Column));
            for (int i = from.Line + 1; i < to.Line; i++) builder.Append('\n').Append(_lines[i]);
            builder.Append('\n').Append(_lines[to.Line].Substring(0, to.Column));
            return builder.ToString();
        }

        public TextPosition ClampPosition(TextPosition p)
        {
            int line = Clamp(p.Line, 0, _lines.Count - 1);
            return new TextPosition(line, Clamp(p.Column, 0, _lines[line].Length));
        }

        // ------------------------------------------------------------------ внутреннее

        private void SetTextInternal(string text)
        {
            _lines.Clear();
            _lines.AddRange(Normalize(text).Split('\n'));
            Caret = new TextPosition(0, 0);
            Anchor = Caret;
            _preferredColumn = -1;
            Touched();
        }

        private static string Normalize(string text)
        {
            return text.Replace("\r\n", "\n").Replace('\r', '\n').Replace("\t", new string(' ', IndentSize));
        }

        private void InsertInternal(string text)
        {
            string[] parts = text.Split('\n');
            string line = _lines[Caret.Line];
            string before = line.Substring(0, Caret.Column);
            string after = line.Substring(Caret.Column);
            if (parts.Length == 1)
            {
                _lines[Caret.Line] = before + text + after;
                Caret = new TextPosition(Caret.Line, Caret.Column + text.Length);
            }
            else
            {
                _lines[Caret.Line] = before + parts[0];
                for (int i = 1; i < parts.Length; i++) _lines.Insert(Caret.Line + i, parts[i]);
                int lastLine = Caret.Line + parts.Length - 1;
                int column = _lines[lastLine].Length;
                _lines[lastLine] += after;
                Caret = new TextPosition(lastLine, column);
            }
            Anchor = Caret;
        }

        private void DeleteSelectionInternal()
        {
            if (!HasSelection) return;
            TextPosition start = SelectionStart;
            TextPosition end = SelectionEnd;
            string head = _lines[start.Line].Substring(0, start.Column);
            string tail = _lines[end.Line].Substring(end.Column);
            _lines.RemoveRange(start.Line + 1, end.Line - start.Line);
            _lines[start.Line] = head + tail;
            Caret = start;
            Anchor = start;
        }

        private void ForEachSelectedLine(Action<int> action)
        {
            int first = SelectionStart.Line;
            int last = SelectionEnd.Line;
            if (HasSelection && last > first && SelectionEnd.Column == 0) last--;
            for (int i = first; i <= last; i++) action(i);
        }

        private void PushUndo()
        {
            _undo.AddLast(Capture());
            while (_undo.Count > MaxUndo) _undo.RemoveFirst();
            _redo.Clear();
        }

        private Snapshot Capture() => new Snapshot { Text = Text, Caret = Caret, Anchor = Anchor };

        private void Restore(Snapshot snapshot)
        {
            _lines.Clear();
            _lines.AddRange(snapshot.Text.Split('\n'));
            Caret = ClampPosition(snapshot.Caret);
            Anchor = ClampPosition(snapshot.Anchor);
            Touched();
        }

        private void Touched()
        {
            Version++;
            _preferredColumn = -1;
        }

        private static string LeadingWhitespace(string line)
        {
            int i = 0;
            while (i < line.Length && line[i] == ' ') i++;
            return line.Substring(0, i);
        }

        private static int Clamp(int value, int min, int max) => value < min ? min : value > max ? max : value;
    }
}
