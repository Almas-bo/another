using System.Collections.Generic;
using System.Linq;
using NUnit.Framework;
using SubroutineCity.Core.Editing;

namespace SubroutineCity.Core.Tests
{
    public class LexerTests
    {
        private static List<(TokenKind kind, string text)> Lex(string line, LexState state = LexState.Normal)
        {
            var tokens = new List<Token>();
            JavaLexer.TokenizeLine(line, state, tokens);
            return tokens.Where(t => t.Kind != TokenKind.Whitespace).Select(t => (t.Kind, line.Substring(t.Start, t.Length))).ToList();
        }

        [Test]
        public void ClassifiesCommonTokens()
        {
            var tokens = Lex("public static long totalLoad(int[] a) { return 0x1FL + 2.5e-3; } // конец");
            Assert.That(tokens, Does.Contain((TokenKind.Keyword, "public")));
            Assert.That(tokens, Does.Contain((TokenKind.PrimitiveType, "long")));
            Assert.That(tokens, Does.Contain((TokenKind.MethodCall, "totalLoad")));
            Assert.That(tokens, Does.Contain((TokenKind.Number, "0x1FL")));
            Assert.That(tokens, Does.Contain((TokenKind.Number, "2.5e-3")));
            Assert.That(tokens.Last(), Is.EqualTo((TokenKind.Comment, "// конец")));
        }

        [Test]
        public void StringsCharsAnnotationsAndTypes()
        {
            var tokens = Lex("@Override String s = \"a\\\"b\"; char c = '\\''; List<Integer> xs;");
            Assert.That(tokens[0], Is.EqualTo((TokenKind.Annotation, "@Override")));
            Assert.That(tokens, Does.Contain((TokenKind.String, "\"a\\\"b\"")));
            Assert.That(tokens, Does.Contain((TokenKind.Char, "'\\''")));
            Assert.That(tokens, Does.Contain((TokenKind.TypeName, "Integer")));
        }

        [Test]
        public void MultiLineConstructsCarryState()
        {
            var tokens = new List<Token>();
            Assert.That(JavaLexer.TokenizeLine("int x; /** документация", LexState.Normal, tokens), Is.EqualTo(LexState.DocComment));
            Assert.That(JavaLexer.TokenizeLine(" * продолжение", LexState.DocComment, tokens), Is.EqualTo(LexState.DocComment));
            Assert.That(JavaLexer.TokenizeLine(" */ int y;", LexState.DocComment, tokens), Is.EqualTo(LexState.Normal));
            Assert.That(tokens[0].Kind, Is.EqualTo(TokenKind.DocComment));
            Assert.That(JavaLexer.TokenizeLine("String t = \"\"\"", LexState.Normal, tokens), Is.EqualTo(LexState.TextBlock));
            Assert.That(JavaLexer.TokenizeLine("   текст \"в\" блоке", LexState.TextBlock, tokens), Is.EqualTo(LexState.TextBlock));
            Assert.That(JavaLexer.TokenizeLine("   \"\"\";", LexState.TextBlock, tokens), Is.EqualTo(LexState.Normal));
        }

        [Test]
        public void TokensCoverTheWholeLine()
        {
            string line = "for (int i = 0; i < n; i++) { total += a[i] >>> 1; } /* x */";
            var tokens = new List<Token>();
            JavaLexer.TokenizeLine(line, LexState.Normal, tokens);
            int position = 0;
            foreach (var token in tokens)
            {
                Assert.That(token.Start, Is.EqualTo(position));
                position = token.End;
            }
            Assert.That(position, Is.EqualTo(line.Length));
        }
    }

    public class CodeDocumentTests
    {
        [Test]
        public void TypingAndUndoCoalescesWords()
        {
            var doc = new CodeDocument();
            foreach (char c in "long total") doc.Type(c);
            Assert.That(doc.Text, Is.EqualTo("long total"));
            doc.Undo();
            Assert.That(doc.Text, Is.EqualTo("long "));
            doc.Undo();
            Assert.That(doc.Text, Is.EqualTo("long"));
            doc.Redo();
            Assert.That(doc.Text, Is.EqualTo("long "));
        }

        [Test]
        public void EnterBetweenBracesExpandsBlock()
        {
            var doc = new CodeDocument("    void f() {}");
            doc.SetCaret(new TextPosition(0, 14), false);
            doc.NewLine();
            Assert.That(doc.Text, Is.EqualTo("    void f() {\n        \n    }"));
            Assert.That(doc.Caret, Is.EqualTo(new TextPosition(1, 8)));
        }

        [Test]
        public void ClosingBraceDedents()
        {
            var doc = new CodeDocument("class A {\n        ");
            doc.SetCaret(new TextPosition(1, 8), false);
            doc.Type('}');
            Assert.That(doc.Line(1), Is.EqualTo("    }"));
        }

        [Test]
        public void BackspaceRemovesIndentLevel()
        {
            var doc = new CodeDocument("        x");
            doc.SetCaret(new TextPosition(0, 8), false);
            doc.Backspace();
            Assert.That(doc.Line(0), Is.EqualTo("    x"));
            doc.MoveHome(false);
            Assert.That(doc.Caret.Column, Is.EqualTo(0), "Home в позиции отступа — к началу строки");
            doc.MoveHome(false);
            Assert.That(doc.Caret.Column, Is.EqualTo(4), "Повторный Home — к первому непробельному символу");
        }

        [Test]
        public void PasteNormalizesTabsAndNewlines()
        {
            var doc = new CodeDocument();
            doc.InsertText("a\r\n\tb\rc");
            Assert.That(doc.Text, Is.EqualTo("a\n    b\nc"));
            Assert.That(doc.Caret, Is.EqualTo(new TextPosition(2, 1)));
        }

        [Test]
        public void SelectionReplaceAndMultilineIndent()
        {
            var doc = new CodeDocument("one\ntwo\nthree");
            doc.SetCaret(new TextPosition(0, 1), false);
            doc.SetCaret(new TextPosition(2, 2), true);
            Assert.That(doc.SelectedText, Is.EqualTo("ne\ntwo\nth"));
            doc.Indent();
            Assert.That(doc.Text, Is.EqualTo("    one\n    two\n    three"));
            doc.Outdent();
            Assert.That(doc.Text, Is.EqualTo("one\ntwo\nthree"));
            doc.SelectAll();
            doc.Type('x');
            Assert.That(doc.Text, Is.EqualTo("x"));
        }

        [Test]
        public void ToggleCommentRoundTrips()
        {
            var doc = new CodeDocument("    int a;\n    int b;");
            doc.SelectAll();
            doc.ToggleComment();
            Assert.That(doc.Text, Is.EqualTo("    // int a;\n    // int b;"));
            doc.ToggleComment();
            Assert.That(doc.Text, Is.EqualTo("    int a;\n    int b;"));
        }

        [Test]
        public void OffsetsMatchJavacPositions()
        {
            var doc = new CodeDocument("ab\ncde\nf");
            Assert.That(doc.OffsetOf(new TextPosition(1, 2)), Is.EqualTo(5));
            Assert.That(doc.PositionOf(5), Is.EqualTo(new TextPosition(1, 2)));
            Assert.That(doc.PositionOf(7), Is.EqualTo(new TextPosition(2, 0)));
            Assert.That(doc.PositionOf(999), Is.EqualTo(new TextPosition(2, 1)));
        }

        [Test]
        public void WordNavigationAndVerticalColumnMemory()
        {
            var doc = new CodeDocument("total += loads[i];\nx\nlonger line here");
            doc.SetCaret(new TextPosition(0, 0), false);
            doc.MoveRight(false, true);
            Assert.That(doc.Caret.Column, Is.EqualTo(6));
            doc.SetCaret(new TextPosition(0, 12), false);
            doc.MoveVertical(1, false);
            Assert.That(doc.Caret, Is.EqualTo(new TextPosition(1, 1)));
            doc.MoveVertical(1, false);
            Assert.That(doc.Caret, Is.EqualTo(new TextPosition(2, 12)));
        }

        [Test]
        public void VersionChangesOnlyOnEdits()
        {
            var doc = new CodeDocument("abc");
            int version = doc.Version;
            doc.MoveRight(false, false);
            Assert.That(doc.Version, Is.EqualTo(version));
            doc.Type('x');
            Assert.That(doc.Version, Is.GreaterThan(version));
        }
    }

    public class CompletionTests
    {
        [Test]
        public void SuggestsFromDocumentApiAndContract()
        {
            var engine = new CompletionEngine("public interface TrafficCounter { void register(int sector); }");
            var doc = new CodeDocument("long sectorTotal = 0;\nsec");
            doc.MoveDocumentEnd(false);
            var suggestions = engine.Suggest(doc);
            Assert.That(suggestions[0], Is.EqualTo("sectorTotal"));
            Assert.That(suggestions, Does.Contain("sector"));

            doc.InsertText("\nAtomicL");
            Assert.That(engine.Suggest(doc), Does.Contain("AtomicLong"));
            doc.ReplaceWordBeforeCaret("AtomicLongArray");
            Assert.That(doc.Line(doc.LineCount - 1), Is.EqualTo("AtomicLongArray"));
        }

        [Test]
        public void NoSuggestionsWithoutPrefix()
        {
            var doc = new CodeDocument("x ");
            doc.MoveDocumentEnd(false);
            Assert.That(new CompletionEngine().Suggest(doc), Is.Empty);
        }
    }
}
