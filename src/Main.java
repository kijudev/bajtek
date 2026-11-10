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

sealed interface Expr permits AtomExpr, NumberExpr, ListExpr {
    TokenPos pos();
}

record AtomExpr(String name, TokenPos pos) implements Expr {}

record NumberExpr(int value, TokenPos pos) implements Expr {}

record ListExpr(List<Expr> items, TokenPos pos) implements Expr {
    ListExpr {
        items = List.copyOf(items);
    }
}

String exprToString(Expr expr) {
    return switch (expr) {
        case AtomExpr a -> "'" + a.name();
        case NumberExpr n -> "$" + Integer.toString(n.value());
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

record ModuleNode(
    String name,
    List<ImportNode> imports,
    List<DeclarationNode> declarations,
    List<Directive> directives,
    TokenPos pos
) {}

record ImportNode(String module, TokenPos pos) {}

record DeclarationNode(
    String name,
    List<String> params,
    KindNode body,
    List<Directive> directives,
    TokenPos pos
) {}

sealed interface KindNode permits AliasKind, StructKind, UnionKind {}

record AliasKind(Type target) implements KindNode {}

record StructKind(List<FieldType> fields) implements KindNode {}

record UnionKind(List<VariantType> variants) implements KindNode {}

record FieldType(
    String name,
    Type type,
    List<Directive> directives,
    TokenPos pos
) {}

record VariantType(
    String name,
    List<FieldType> fields,
    List<Directive> directives,
    TokenPos pos
) {}

sealed interface Type
    permits
        RefType,
        S8Type,
        S16Type,
        S32Type,
        S64Type,
        U8Type,
        U16Type,
        U32Type,
        U64Type,
        StrType,
        CharType,
        BoolType,
        ListType,
        ArrayType {}

record RefType(String name, List<Type> args, TokenPos pos) implements Type {}

record S8Type(TokenPos pos) implements Type {}

record S16Type(TokenPos pos) implements Type {}

record S32Type(TokenPos pos) implements Type {}

record S64Type(TokenPos pos) implements Type {}

record U8Type(TokenPos pos) implements Type {}

record U16Type(TokenPos pos) implements Type {}

record U32Type(TokenPos pos) implements Type {}

record U64Type(TokenPos pos) implements Type {}

record StrType(TokenPos pos) implements Type {}

record CharType(TokenPos pos) implements Type {}

record BoolType(TokenPos pos) implements Type {}

record ListType(Type items, TokenPos pos) implements Type {}

record ArrayType(Type items, int size, TokenPos pos) implements Type {}

sealed interface Directive permits IntrinsicDirective, PackDirective {}

record IntrinsicDirective(String tag, TokenPos pos) implements Directive {}

record PackDirective(TokenPos pos) implements Directive {}

// Module elaborateModule(Sexpr sexpr) {}

Type elaborateAtomType(AtomExpr atom) {
    return switch (atom.name()) {
        case "s8" -> new S8Type(atom.pos());
        case "s16" -> new S16Type(atom.pos());
        case "s32" -> new S32Type(atom.pos());
        case "s64" -> new S64Type(atom.pos());
        case "u8" -> new U8Type(atom.pos());
        case "u16" -> new U16Type(atom.pos());
        case "u32" -> new U32Type(atom.pos());
        case "u64" -> new U64Type(atom.pos());
        case "str" -> new StrType(atom.pos());
        case "char" -> new CharType(atom.pos());
        case "bool" -> new BoolType(atom.pos());
        default -> new RefType(atom.name(), List.of(), atom.pos());
    };
}

Directive elaborateDirective(AtomExpr atom) {
    return switch (atom.name()) {
        case "#intrinsic" -> new IntrinsicDirective(atom.name(), atom.pos());
        case "#pack" -> new PackDirective(atom.pos());
        default -> throw new SyntaxError(
            "unknown directive",
            atom.pos().line(),
            atom.pos().column()
        );
    };
}

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
