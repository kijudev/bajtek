import java.io.IOException;
import java.nio.charset.StandardCharsets;

// >---------------------------------------------------------------------- Lexer

enum TokenKind {
    PARENL,
    PARENR,
    SYMBOL,
    NUMBER,
    STRING,
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

    private String lexString(int l, int c) {
        var sb = new StringBuilder();

        while (true) {
            if (isAtEnd() || peek() == '\n') {
                throw new SyntaxError("unterminated string", l, c);
            }

            char ch = advance();

            if (ch == '"') {
                return sb.toString();
            }

            if (ch != '\\') {
                sb.append(ch);
                continue;
            }

            int el = line;
            int ec = column - 1;

            if (isAtEnd()) {
                throw new SyntaxError("unterminated string", l, c);
            }

            sb.append(
                switch (advance()) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> throw new SyntaxError(
                        "unknown escape sequence",
                        el,
                        ec
                    );
                }
            );
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

            if (ch == '"') {
                out.add(
                    new Token(
                        TokenKind.STRING,
                        lexString(l, c),
                        new TokenPos(l, c)
                    )
                );
                continue;
            }

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

sealed interface Expr permits AtomExpr, NumberExpr, StringExpr, ListExpr {
    TokenPos pos();
}

record AtomExpr(String name, TokenPos pos) implements Expr {}

record NumberExpr(int value, TokenPos pos) implements Expr {}

record StringExpr(String value, TokenPos pos) implements Expr {}

record ListExpr(List<Expr> items, TokenPos pos) implements Expr {
    ListExpr {
        items = List.copyOf(items);
    }
}

String exprToString(Expr expr) {
    return switch (expr) {
        case AtomExpr a -> "'" + a.name();
        case NumberExpr n -> "$" + Integer.toString(n.value());
        case StringExpr s -> "\"" +
            s
                .value()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t") +
            "\"";
        case ListExpr s -> "(" +
            s
                .items()
                .stream()
                .map(x -> exprToString(x))
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

    Expr parse() {
        var t = advance();

        return switch (t.kind()) {
            case NUMBER -> new NumberExpr(Integer.parseInt(t.text()), t.pos());
            case SYMBOL -> new AtomExpr(t.text(), t.pos());
            case STRING -> new StringExpr(t.text(), t.pos());
            case PARENL -> {
                var items = new ArrayList<Expr>();

                while (peek().kind() != TokenKind.PARENR) {
                    items.add(parse());
                }

                advance();
                yield new ListExpr(items, t.pos());
            }
            case PARENR -> throw new SyntaxError(t, "unexpected ')'");
            case EOF -> throw new SyntaxError(t, "unexpected end of input");
        };
    }
}

// >------------------------------------------------------------------ Typed AST

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
        System.out.println(exprToString(sexpr));
    } catch (SyntaxError e) {
        System.err.println(e.getMessage());
        System.exit(1);
    }
}
