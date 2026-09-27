using System.Collections.Generic;

namespace SubroutineCity.Core.Editing
{
    public enum TokenKind
    {
        Whitespace,
        Keyword,
        PrimitiveType,
        TypeName,
        Identifier,
        MethodCall,
        Number,
        String,
        Char,
        Comment,
        DocComment,
        Annotation,
        Operator,
        Punctuation,
        Literal,
        Unknown
    }

    public struct Token
    {
        public int Start;
        public int Length;
        public TokenKind Kind;

        public Token(int start, int length, TokenKind kind)
        {
            Start = start;
            Length = length;
            Kind = kind;
        }

        public int End => Start + Length;
    }

    /// <summary>Состояние лексера на границе строк: многострочные конструкции продолжаются на следующей строке.</summary>
    public enum LexState
    {
        Normal,
        BlockComment,
        DocComment,
        TextBlock
    }

    /// <summary>
    /// Построчный лексер Java 21 для подсветки синтаксиса. Не строит AST — только токены; этого достаточно для
    /// подсветки, автодополнения и навигации. Инкрементален: строка пересчитывается по состоянию на её начале.
    /// </summary>
    public static class JavaLexer
    {
        private static readonly HashSet<string> Keywords = new HashSet<string>
        {
            "abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default", "do", "else",
            "enum", "extends", "final", "finally", "for", "goto", "if", "implements", "import", "instanceof",
            "interface", "native", "new", "package", "private", "protected", "public", "return", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "volatile", "while",
            "var", "record", "yield", "sealed", "permits", "non-sealed", "when"
        };

        private static readonly HashSet<string> Primitives = new HashSet<string>
        {
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"
        };

        private static readonly HashSet<string> Literals = new HashSet<string> { "true", "false", "null" };

        public static IReadOnlyCollection<string> AllKeywords => Keywords;
        public static IReadOnlyCollection<string> AllPrimitives => Primitives;

        public static bool IsKeyword(string word) => Keywords.Contains(word) || Primitives.Contains(word) || Literals.Contains(word);

        /// <summary>Разбивает строку на токены; возвращает состояние для следующей строки.</summary>
        public static LexState TokenizeLine(string line, LexState state, List<Token> output)
        {
            output.Clear();
            int i = 0;
            int n = line.Length;

            if (state == LexState.BlockComment || state == LexState.DocComment)
            {
                int close = line.IndexOf("*/", System.StringComparison.Ordinal);
                var kind = state == LexState.DocComment ? TokenKind.DocComment : TokenKind.Comment;
                if (close < 0)
                {
                    if (n > 0) output.Add(new Token(0, n, kind));
                    return state;
                }
                output.Add(new Token(0, close + 2, kind));
                i = close + 2;
                state = LexState.Normal;
            }
            else if (state == LexState.TextBlock)
            {
                int close = FindTextBlockEnd(line, 0);
                if (close < 0)
                {
                    if (n > 0) output.Add(new Token(0, n, TokenKind.String));
                    return state;
                }
                output.Add(new Token(0, close, TokenKind.String));
                i = close;
                state = LexState.Normal;
            }

            while (i < n)
            {
                char c = line[i];
                int start = i;

                if (char.IsWhiteSpace(c))
                {
                    while (i < n && char.IsWhiteSpace(line[i])) i++;
                    output.Add(new Token(start, i - start, TokenKind.Whitespace));
                    continue;
                }

                if (c == '/' && i + 1 < n && line[i + 1] == '/')
                {
                    output.Add(new Token(start, n - start, TokenKind.Comment));
                    return LexState.Normal;
                }

                if (c == '/' && i + 1 < n && line[i + 1] == '*')
                {
                    bool doc = i + 2 < n && line[i + 2] == '*' && !(i + 3 < n && line[i + 3] == '/');
                    int close = line.IndexOf("*/", i + 2, System.StringComparison.Ordinal);
                    var kind = doc ? TokenKind.DocComment : TokenKind.Comment;
                    if (close < 0)
                    {
                        output.Add(new Token(start, n - start, kind));
                        return doc ? LexState.DocComment : LexState.BlockComment;
                    }
                    i = close + 2;
                    output.Add(new Token(start, i - start, kind));
                    continue;
                }

                if (c == '"')
                {
                    if (i + 2 < n && line[i + 1] == '"' && line[i + 2] == '"')
                    {
                        int close = FindTextBlockEnd(line, i + 3);
                        if (close < 0)
                        {
                            output.Add(new Token(start, n - start, TokenKind.String));
                            return LexState.TextBlock;
                        }
                        i = close;
                        output.Add(new Token(start, i - start, TokenKind.String));
                        continue;
                    }
                    i = SkipQuoted(line, i, '"');
                    output.Add(new Token(start, i - start, TokenKind.String));
                    continue;
                }

                if (c == '\'')
                {
                    i = SkipQuoted(line, i, '\'');
                    output.Add(new Token(start, i - start, TokenKind.Char));
                    continue;
                }

                if (char.IsDigit(c) || (c == '.' && i + 1 < n && char.IsDigit(line[i + 1])))
                {
                    i++;
                    while (i < n && (char.IsLetterOrDigit(line[i]) || line[i] == '_' || line[i] == '.'
                                     || ((line[i] == '+' || line[i] == '-') && (line[i - 1] == 'e' || line[i - 1] == 'E')
                                         && !IsHexLiteral(line, start))))
                        i++;
                    output.Add(new Token(start, i - start, TokenKind.Number));
                    continue;
                }

                if (c == '@' && i + 1 < n && IsIdentifierStart(line[i + 1]))
                {
                    i++;
                    while (i < n && (IsIdentifierPart(line[i]) || line[i] == '.')) i++;
                    output.Add(new Token(start, i - start, TokenKind.Annotation));
                    continue;
                }

                if (IsIdentifierStart(c))
                {
                    while (i < n && IsIdentifierPart(line[i])) i++;
                    string word = line.Substring(start, i - start);
                    output.Add(new Token(start, i - start, Classify(word, line, i)));
                    continue;
                }

                if ("(){}[];,.".IndexOf(c) >= 0)
                {
                    output.Add(new Token(start, 1, TokenKind.Punctuation));
                    i++;
                    continue;
                }

                if ("=+-*/%<>!&|^~?:".IndexOf(c) >= 0)
                {
                    while (i < n && "=+-*/%<>!&|^~?:".IndexOf(line[i]) >= 0
                           && !(line[i] == '/' && i + 1 < n && (line[i + 1] == '/' || line[i + 1] == '*')))
                        i++;
                    if (i == start) i++;
                    output.Add(new Token(start, i - start, TokenKind.Operator));
                    continue;
                }

                output.Add(new Token(start, 1, TokenKind.Unknown));
                i++;
            }
            return LexState.Normal;
        }

        private static TokenKind Classify(string word, string line, int end)
        {
            if (Keywords.Contains(word)) return TokenKind.Keyword;
            if (Primitives.Contains(word)) return TokenKind.PrimitiveType;
            if (Literals.Contains(word)) return TokenKind.Literal;
            int next = end;
            while (next < line.Length && line[next] == ' ') next++;
            if (next < line.Length && line[next] == '(') return TokenKind.MethodCall;
            if (char.IsUpper(word[0])) return TokenKind.TypeName;
            return TokenKind.Identifier;
        }

        private static bool IsHexLiteral(string line, int start)
        {
            return start + 1 < line.Length && line[start] == '0' && (line[start + 1] == 'x' || line[start + 1] == 'X');
        }

        private static int SkipQuoted(string line, int i, char quote)
        {
            i++;
            while (i < line.Length)
            {
                if (line[i] == '\\') { i += 2; continue; }
                if (line[i] == quote) return i + 1;
                i++;
            }
            return line.Length;
        }

        /// <summary>Позиция сразу после закрывающих """ или -1.</summary>
        private static int FindTextBlockEnd(string line, int from)
        {
            for (int i = from; i + 2 < line.Length; i++)
            {
                if (line[i] == '\\') { i++; continue; }
                if (line[i] == '"' && line[i + 1] == '"' && line[i + 2] == '"') return i + 3;
            }
            return -1;
        }

        public static bool IsIdentifierStart(char c) => char.IsLetter(c) || c == '_' || c == '$';

        public static bool IsIdentifierPart(char c) => char.IsLetterOrDigit(c) || c == '_' || c == '$';
    }
}
