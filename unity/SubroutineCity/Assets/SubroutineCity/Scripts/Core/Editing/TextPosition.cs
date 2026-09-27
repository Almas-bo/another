using System;

namespace SubroutineCity.Core.Editing
{
    /// <summary>Позиция в документе: строка и столбец с нуля.</summary>
    public struct TextPosition : IEquatable<TextPosition>, IComparable<TextPosition>
    {
        public int Line;
        public int Column;

        public TextPosition(int line, int column)
        {
            Line = line;
            Column = column;
        }

        public int CompareTo(TextPosition other)
        {
            return Line != other.Line ? Line.CompareTo(other.Line) : Column.CompareTo(other.Column);
        }

        public bool Equals(TextPosition other) => Line == other.Line && Column == other.Column;
        public override bool Equals(object obj) => obj is TextPosition other && Equals(other);
        public override int GetHashCode() => (Line * 397) ^ Column;
        public override string ToString() => (Line + 1) + ":" + (Column + 1);

        public static bool operator ==(TextPosition a, TextPosition b) => a.Equals(b);
        public static bool operator !=(TextPosition a, TextPosition b) => !a.Equals(b);
        public static bool operator <(TextPosition a, TextPosition b) => a.CompareTo(b) < 0;
        public static bool operator >(TextPosition a, TextPosition b) => a.CompareTo(b) > 0;
        public static bool operator <=(TextPosition a, TextPosition b) => a.CompareTo(b) <= 0;
        public static bool operator >=(TextPosition a, TextPosition b) => a.CompareTo(b) >= 0;

        public static TextPosition Min(TextPosition a, TextPosition b) => a <= b ? a : b;
        public static TextPosition Max(TextPosition a, TextPosition b) => a >= b ? a : b;
    }
}
