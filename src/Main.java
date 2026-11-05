import java.io.IOException;
import java.nio.charset.StandardCharsets;

// >---------------------------------------------------------------------- Lexer

enum TokenKind {
    PARENL,
    PARENR,
    SYMBOL,
    NUMBER,
    EOF,
}

record TokenPos(int line, int column) {}

record Token(TokenKind kind, String text, TokenPos pos) {}

final class SyntaxError extends RuntimeException {

    SyntaxError(String message, int line, int column) {
        super(line + ":" + column + ": " + message);
    }

    SyntaxError(Token token, String message) {
        this(message, token.pos().line(), token.pos().column());
    }
}

final class Lexer {

    private final String source;
    private int pos = 0,
        line = 1,
        column = 1;

    Lexer(String source) {
        this.source = source;
    }

    private boolean isAtEnd() {
        return pos >= source.length();
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(pos);
    }

    private char advance() {
        char c = peek();
        pos++;

        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }

        return c;
    }

    private static boolean isSymbolicChar(char c) {
        return Character.isLetterOrDigit(c) || "-._#?".indexOf(c) >= 0;
    }

    private void skipWhitespace() {
        while (!isAtEnd() && Character.isWhitespace(peek())) {
            advance();
        }
    }

    List<Token> lex() {
        var out = new ArrayList<Token>();

        while (true) {
            skipWhitespace();
            int l = line;
            int c = column;
            int start = pos;

            if (isAtEnd()) {
                out.add(new Token(TokenKind.EOF, "", new TokenPos(l, c)));
                return out;
            }

            char ch = advance();

            TokenKind kind = switch (ch) {
                case '(' -> TokenKind.PARENL;
                case ')' -> TokenKind.PARENR;
                default -> {
                    if (Character.isDigit(ch)) {
                        while (Character.isDigit(peek())) {
                            advance();
                        }

                        yield TokenKind.NUMBER;
                    }

                    if (isSymbolicChar(ch)) {
                        while (isSymbolicChar(peek())) {
                            advance();
                        }

                        yield TokenKind.SYMBOL;
                    }

                    throw new SyntaxError("unexpected character", l, c);
                }
            };

            out.add(
                new Token(
                    kind,
                    source.substring(start, pos),
                    new TokenPos(l, c)
                )
            );
        }
    }
}

// >-------------------------------------------------------- S-expression Parser

sealed interface Sexpr permits Atom, Number, Slist {
    TokenPos pos();
}

record Atom(String name, TokenPos pos) implements Sexpr {}

record Number(int value, TokenPos pos) implements Sexpr {}

record Slist(List<Sexpr> items, TokenPos pos) implements Sexpr {
    Slist {
        items = List.copyOf(items);
    }
}

String sexprToString(Sexpr sexpr) {
    return switch (sexpr) {
        case Atom a -> "'" + a.name();
        case Number n -> "$" + Integer.toString(n.value());
        case Slist s -> "(" +
            s
                .items()
                .stream()
                .map(x -> sexprToString(x))
                .collect(Collectors.joining(" ", "(", ")"));
    };
}

final class Parser {

    private final List<Token> tokens;
    private int pos = 0;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token advance() {
        var token = peek();
        pos++;
        return token;
    }

    Sexpr parse() {
        var t = advance();

        return switch (t.kind()) {
            case NUMBER -> new Number(Integer.parseInt(t.text()), t.pos());
            case SYMBOL -> new Atom(t.text(), t.pos());
            case PARENL -> {
                var items = new ArrayList<Sexpr>();

                while (peek().kind() != TokenKind.PARENR) {
                    items.add(parse());
                }

                advance();
                yield new Slist(items, t.pos());
            }
            case PARENR -> throw new SyntaxError(t, "unexpected ')'");
            case EOF -> throw new SyntaxError(t, "unexpected end of input");
        };
    }
}

// >------------------------------------------------------------------ Typed AST

record Module(
    String name,
    List<Import> imports,
    List<TypeDeclaration> declarations,
    List<Directive> directives,
    TokenPos pos
) {}

record Import(String module, TokenPos pos) {}

record TypeDeclaration(
    String name,
    List<String> params,
    DeclarationBody body,
    List<Directive> directives,
    TokenPos pos
) {}

sealed interface DeclarationBody permits Alias, Struct, Union {}

record Alias(TypeExpr target) implements DeclarationBody {}

record Struct(List<Field> fields) implements DeclarationBody {}

record Union(List<Variant> variants) implements DeclarationBody {}

record Field(
    String name,
    TypeExpr type,
    List<Directive> directives,
    TokenPos pos
) {}

record Variant(
    String name,
    List<Field> fields,
    List<Directive> directives,
    TokenPos pos
) {}

sealed interface TypeExpr
    permits
        Ref,
        S8,
        S16,
        S32,
        S64,
        U8,
        U16,
        U32,
        U64,
        Str,
        Char,
        Bool,
        ListGeneric,
        ArrayGeneric {}

record Ref(
    String name,
    List<TypeExpr> args,
    TokenPos pos
) implements TypeExpr {}

record S8(TokenPos pos) implements TypeExpr {}

record S16(TokenPos pos) implements TypeExpr {}

record S32(TokenPos pos) implements TypeExpr {}

record S64(TokenPos pos) implements TypeExpr {}

record U8(TokenPos pos) implements TypeExpr {}

record U16(TokenPos pos) implements TypeExpr {}

record U32(TokenPos pos) implements TypeExpr {}

record U64(TokenPos pos) implements TypeExpr {}

record Str(TokenPos pos) implements TypeExpr {}

record Char(TokenPos pos) implements TypeExpr {}

record Bool(TokenPos pos) implements TypeExpr {}

record ListGeneric(TypeExpr items, TokenPos pos) implements TypeExpr {}

record ArrayGeneric(
    TypeExpr items,
    int size,
    TokenPos pos
) implements TypeExpr {}

sealed interface Directive permits Intrinsic, Pack {}

record Intrinsic(String tag, TokenPos pos) implements Directive {}

record Pack(TokenPos pos) implements Directive {}

// >----------------------------------------------------------------------- Main

void main(String[] args) throws IOException {
    String source;

    if (args.length > 0) {
        source = Files.readString(Paths.get(args[0]));
    } else {
        source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }

    try {
        var tokens = new Lexer(source).lex();
        var sexpr = new Parser(tokens).parse();
        System.out.println(sexprToString(sexpr));
    } catch (SyntaxError e) {
        System.err.println(e.getMessage());
        System.exit(1);
    }
}
