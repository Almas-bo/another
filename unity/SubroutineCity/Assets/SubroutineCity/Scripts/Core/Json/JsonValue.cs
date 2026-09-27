using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace SubroutineCity.Core.Json
{
    /// <summary>
    /// Минимальный JSON без зависимостей (JsonUtility Unity не умеет словари, null и полиморфизм).
    /// Модель: Dictionary&lt;string, object&gt;, List&lt;object&gt;, string, long, double, bool, null.
    /// </summary>
    public static class JsonValue
    {
        public const int MaxDepth = 64;

        public static object Parse(string text)
        {
            if (text == null) throw new ArgumentNullException(nameof(text));
            var parser = new Parser(text);
            parser.SkipWhitespace();
            object value = parser.ReadValue(0);
            parser.SkipWhitespace();
            if (!parser.AtEnd) throw parser.Error("лишние символы после значения");
            return value;
        }

        public static JsonObject ParseObject(string text)
        {
            if (!(Parse(text) is Dictionary<string, object> map))
                throw new JsonException("Ожидался JSON-объект");
            return new JsonObject(map);
        }

        public static string Write(object value)
        {
            var builder = new StringBuilder(128);
            Write(value, builder, 0);
            return builder.ToString();
        }

        private static void Write(object value, StringBuilder output, int depth)
        {
            if (depth > MaxDepth) throw new JsonException("Слишком глубокая структура");
            switch (value)
            {
                case null:
                    output.Append("null");
                    break;
                case string s:
                    Quote(s, output);
                    break;
                case bool b:
                    output.Append(b ? "true" : "false");
                    break;
                case int i:
                    output.Append(i.ToString(CultureInfo.InvariantCulture));
                    break;
                case long l:
                    output.Append(l.ToString(CultureInfo.InvariantCulture));
                    break;
                case double d:
                    output.Append(double.IsNaN(d) || double.IsInfinity(d) ? "null" : d.ToString("R", CultureInfo.InvariantCulture));
                    break;
                case float f:
                    output.Append(float.IsNaN(f) || float.IsInfinity(f) ? "null" : f.ToString("R", CultureInfo.InvariantCulture));
                    break;
                case JsonObject jo:
                    Write(jo.Raw, output, depth);
                    break;
                case IDictionary<string, object> map:
                    output.Append('{');
                    bool first = true;
                    foreach (var pair in map)
                    {
                        if (!first) output.Append(',');
                        first = false;
                        Quote(pair.Key, output);
                        output.Append(':');
                        Write(pair.Value, output, depth + 1);
                    }
                    output.Append('}');
                    break;
                case System.Collections.IEnumerable list:
                    output.Append('[');
                    bool firstItem = true;
                    foreach (object item in list)
                    {
                        if (!firstItem) output.Append(',');
                        firstItem = false;
                        Write(item, output, depth + 1);
                    }
                    output.Append(']');
                    break;
                default:
                    throw new JsonException("Тип не поддерживается JSON: " + value.GetType().FullName);
            }
        }

        private static void Quote(string s, StringBuilder output)
        {
            output.Append('"');
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': output.Append("\\\""); break;
                    case '\\': output.Append("\\\\"); break;
                    case '\n': output.Append("\\n"); break;
                    case '\r': output.Append("\\r"); break;
                    case '\t': output.Append("\\t"); break;
                    case '\b': output.Append("\\b"); break;
                    case '\f': output.Append("\\f"); break;
                    default:
                        if (c < 0x20 || c == '\u2028' || c == '\u2029')
                            output.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                        else
                            output.Append(c);
                        break;
                }
            }
            output.Append('"');
        }

        private sealed class Parser
        {
            private readonly string _text;
            private int _pos;

            public Parser(string text) { _text = text; }

            public bool AtEnd => _pos >= _text.Length;

            public object ReadValue(int depth)
            {
                if (depth > MaxDepth) throw Error("слишком глубокая вложенность");
                if (AtEnd) throw Error("неожиданный конец");
                char c = _text[_pos];
                switch (c)
                {
                    case '{': return ReadObject(depth);
                    case '[': return ReadArray(depth);
                    case '"': return ReadString();
                    case 't': return Literal("true", true);
                    case 'f': return Literal("false", false);
                    case 'n': return Literal("null", null);
                    default:
                        if (c == '-' || (c >= '0' && c <= '9')) return ReadNumber();
                        throw Error("неожиданный символ '" + c + "'");
                }
            }

            private Dictionary<string, object> ReadObject(int depth)
            {
                var map = new Dictionary<string, object>();
                _pos++;
                SkipWhitespace();
                if (Peek() == '}') { _pos++; return map; }
                while (true)
                {
                    SkipWhitespace();
                    if (Peek() != '"') throw Error("ожидался ключ-строка");
                    string key = ReadString();
                    SkipWhitespace();
                    Expect(':');
                    SkipWhitespace();
                    if (map.ContainsKey(key)) throw Error("повторяющийся ключ '" + key + "'");
                    map[key] = ReadValue(depth + 1);
                    SkipWhitespace();
                    char c = Next();
                    if (c == '}') return map;
                    if (c != ',') throw Error("ожидалась ',' или '}'");
                }
            }

            private List<object> ReadArray(int depth)
            {
                var list = new List<object>();
                _pos++;
                SkipWhitespace();
                if (Peek() == ']') { _pos++; return list; }
                while (true)
                {
                    SkipWhitespace();
                    list.Add(ReadValue(depth + 1));
                    SkipWhitespace();
                    char c = Next();
                    if (c == ']') return list;
                    if (c != ',') throw Error("ожидалась ',' или ']'");
                }
            }

            private string ReadString()
            {
                Expect('"');
                var output = new StringBuilder();
                while (true)
                {
                    if (AtEnd) throw Error("незакрытая строка");
                    char c = _text[_pos++];
                    if (c == '"') return output.ToString();
                    if (c < 0x20) throw Error("управляющий символ в строке");
                    if (c != '\\') { output.Append(c); continue; }
                    char escape = Next();
                    switch (escape)
                    {
                        case '"': output.Append('"'); break;
                        case '\\': output.Append('\\'); break;
                        case '/': output.Append('/'); break;
                        case 'b': output.Append('\b'); break;
                        case 'f': output.Append('\f'); break;
                        case 'n': output.Append('\n'); break;
                        case 'r': output.Append('\r'); break;
                        case 't': output.Append('\t'); break;
                        case 'u':
                            if (_pos + 4 > _text.Length) throw Error("обрезанная \\u-последовательность");
                            if (!int.TryParse(_text.Substring(_pos, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out int code))
                                throw Error("некорректная \\u-последовательность");
                            output.Append((char)code);
                            _pos += 4;
                            break;
                        default:
                            throw Error("неизвестная escape-последовательность \\" + escape);
                    }
                }
            }

            private object ReadNumber()
            {
                int start = _pos;
                if (Peek() == '-') _pos++;
                Digits();
                bool fractional = false;
                if (!AtEnd && _text[_pos] == '.') { fractional = true; _pos++; Digits(); }
                if (!AtEnd && (_text[_pos] == 'e' || _text[_pos] == 'E'))
                {
                    fractional = true;
                    _pos++;
                    if (!AtEnd && (_text[_pos] == '+' || _text[_pos] == '-')) _pos++;
                    Digits();
                }
                string literal = _text.Substring(start, _pos - start);
                if (!fractional && long.TryParse(literal, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out long whole))
                    return whole;
                return double.Parse(literal, NumberStyles.Float, CultureInfo.InvariantCulture);
            }

            private void Digits()
            {
                int start = _pos;
                while (!AtEnd && _text[_pos] >= '0' && _text[_pos] <= '9') _pos++;
                if (_pos == start) throw Error("ожидалась цифра");
            }

            private object Literal(string word, object value)
            {
                if (string.CompareOrdinal(_text, _pos, word, 0, word.Length) != 0) throw Error("ожидалось " + word);
                _pos += word.Length;
                return value;
            }

            public void SkipWhitespace()
            {
                while (!AtEnd)
                {
                    char c = _text[_pos];
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') _pos++;
                    else return;
                }
            }

            private char Peek()
            {
                if (AtEnd) throw Error("неожиданный конец");
                return _text[_pos];
            }

            private char Next()
            {
                char c = Peek();
                _pos++;
                return c;
            }

            private void Expect(char c)
            {
                if (Next() != c) throw Error("ожидался '" + c + "'");
            }

            public JsonException Error(string message)
            {
                return new JsonException("Некорректный JSON (позиция " + _pos + "): " + message);
            }
        }
    }

    public sealed class JsonException : Exception
    {
        public JsonException(string message) : base(message) { }
    }

    /// <summary>Типобезопасный доступ к JSON-объекту с явными значениями по умолчанию для отсутствующих полей.</summary>
    public sealed class JsonObject
    {
        public JsonObject(Dictionary<string, object> raw)
        {
            Raw = raw ?? throw new ArgumentNullException(nameof(raw));
        }

        public Dictionary<string, object> Raw { get; }

        public bool Has(string key) => Raw.TryGetValue(key, out object value) && value != null;

        public string String(string key, string fallback = null)
        {
            return Raw.TryGetValue(key, out object value) && value is string s ? s : fallback;
        }

        public long Long(string key, long fallback = 0)
        {
            if (!Raw.TryGetValue(key, out object value)) return fallback;
            switch (value)
            {
                case long l: return l;
                case double d: return (long)d;
                default: return fallback;
            }
        }

        public int Int(string key, int fallback = 0)
        {
            long value = Long(key, fallback);
            return value > int.MaxValue ? int.MaxValue : value < int.MinValue ? int.MinValue : (int)value;
        }

        public double Double(string key, double fallback = 0)
        {
            if (!Raw.TryGetValue(key, out object value)) return fallback;
            switch (value)
            {
                case long l: return l;
                case double d: return d;
                default: return fallback;
            }
        }

        public bool Bool(string key, bool fallback = false)
        {
            return Raw.TryGetValue(key, out object value) && value is bool b ? b : fallback;
        }

        public JsonObject Object(string key)
        {
            return Raw.TryGetValue(key, out object value) && value is Dictionary<string, object> map ? new JsonObject(map) : null;
        }

        public List<JsonObject> Objects(string key)
        {
            var result = new List<JsonObject>();
            if (Raw.TryGetValue(key, out object value) && value is List<object> list)
            {
                foreach (object item in list)
                    if (item is Dictionary<string, object> map) result.Add(new JsonObject(map));
            }
            return result;
        }

        public List<string> Strings(string key)
        {
            var result = new List<string>();
            if (Raw.TryGetValue(key, out object value) && value is List<object> list)
            {
                foreach (object item in list)
                    if (item is string s) result.Add(s);
            }
            return result;
        }

        public TEnum Enum<TEnum>(string key, TEnum fallback) where TEnum : struct
        {
            string text = String(key);
            return text != null && System.Enum.TryParse(text, false, out TEnum parsed) && System.Enum.IsDefined(typeof(TEnum), parsed)
                ? parsed
                : fallback;
        }
    }
}
