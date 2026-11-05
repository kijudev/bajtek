enum Kind {
    PARENL,
    PARENR,
    SYMBOL,
    NUMBER,
    EOF,
}

record Token(Kind kind, String text, int line, int column) {}

final class SyntaxError extends RuntimeException {

    SyntaxError(String message, int line, int column) {
        super(line + ":" + column + ": " + message);
    }

    SyntaxError(Token token, String message) {
        this(message, token.line(), token.column());
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
        return Character.isLetterOrDigit(c) || "-_+*/<>=!:.".indexOf(c) >= 0;
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
                out.add(new Token(Kind.EOF, "", l, c));
                return out;
            }

            char ch = advance();

            Kind kind = switch (ch) {
                case '(' -> Kind.PARENL;
                case ')' -> Kind.PARENR;
                default -> {
                    if (Character.isDigit(ch)) {
                        while (Character.isDigit(peek())) {
                            advance();
                        }

                        yield Kind.NUMBER;
                    }

                    if (isSymbolicChar(ch)) {
                        while (isSymbolicChar(peek())) {
                            advance();
                        }

                        yield Kind.SYMBOL;
                    }

                    throw new SyntaxError("unexpected character", l, c);
                }
            };

            out.add(new Token(kind, source.substring(start, pos), l, c));
        }
    }
}

sealed interface Sexpr permits Atom, Number, Slist {}

record Atom(String name) implements Sexpr {}

record Number(int value) implements Sexpr {}

record Slist(List<Sexpr> items) implements Sexpr {
    Slist {
        items = List.copyOf(items);
    }
}

String sexprToString(Sexpr sexpr) {
    return switch (sexpr) {
        case Atom a -> a.name();
        case Number n -> Integer.toString(n.value());
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
            case NUMBER -> new Number(Integer.parseInt(t.text()));
            case SYMBOL -> new Atom(t.text());
            case PARENL -> {
                var items = new ArrayList<Sexpr>();

                while (peek().kind() != Kind.PARENR) {
                    items.add(parse());
                }

                advance();
                yield new Slist(items);
            }
            case PARENR -> throw new SyntaxError(t, "unexpected ')'");
            case EOF -> throw new SyntaxError(t, "unexpected end of input");
        };
    }
}

void main() {
    IO.println("Hello, World!");
}
