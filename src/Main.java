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

    SyntaxError(String message, TokenPos pos) {
        this(message, pos.line(), pos.column());
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

    private char peekNext() {
        return pos + 1 >= source.length() ? '\0' : source.charAt(pos + 1);
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
        return Character.isLetterOrDigit(c) || "-._#?/".indexOf(c) >= 0;
    }

    private boolean atCommentOpen() {
        return peek() == '(' && peekNext() == '*';
    }

    private boolean atCommentClose() {
        return peek() == '*' && peekNext() == ')';
    }

    // Comments are (* ... *) and may nest.
    private void skipComment() {
        int l = line;
        int c = column;
        int depth = 0;

        do {
            if (isAtEnd()) {
                throw new SyntaxError("unterminated comment", l, c);
            }

            if (atCommentOpen()) {
                advance();
                advance();
                depth++;
            } else if (atCommentClose()) {
                advance();
                advance();
                depth--;
            } else {
                advance();
            }
        } while (depth > 0);
    }

    private void skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            if (Character.isWhitespace(peek())) {
                advance();
            } else if (atCommentOpen()) {
                skipComment();
            } else {
                return;
            }
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
            skipWhitespaceAndComments();
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

    // A file is exactly one expression followed by end of input.
    Expr parseFile() {
        var expr = parse();
        var t = peek();

        if (t.kind() != TokenKind.EOF) {
            throw new SyntaxError(t, "expected end of input");
        }

        return expr;
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

record Module(
    String name,
    List<Import> imports,
    List<Declaration> declarations,
    List<Directive> directives,
    TokenPos pos
) {}

record Import(String module, TokenPos pos) {}

sealed interface Declaration permits Alias, Struct, Union {
    String name();

    List<String> params();

    TokenPos pos();
}

record Alias(
    String name,
    List<String> params,
    Type target,
    List<Directive> directives,
    TokenPos pos
) implements Declaration {}

record Struct(
    String name,
    List<String> params,
    List<StructField> fields,
    List<Directive> directives,
    TokenPos pos
) implements Declaration {}

record StructField(
    String name,
    Type type,
    List<Directive> directives,
    TokenPos pos
) {}

record Union(
    String name,
    List<String> params,
    List<UnionVariant> variants,
    Type tag,
    List<Directive> directives,
    TokenPos pos
) implements Declaration {}

record UnionVariant(
    String name,
    Type type,
    List<Directive> directives,
    TokenPos pos
) {}

sealed interface Type
    permits RefType, NumberType, BytesType, BitsType, ArrayType, SliceType {}

record RefType(String name, List<Type> args, TokenPos pos) implements Type {}

// A type-level number, e.g. an array length: (array u32 4) or (numarray 4).
record NumberType(int value, TokenPos pos) implements Type {}

record BytesType(int size, TokenPos pos) implements Type {}

record BitsType(int size, TokenPos pos) implements Type {}

record ArrayType(Type element, Type size, TokenPos pos) implements Type {}

record SliceType(Type element, Type size, TokenPos pos) implements Type {}

sealed interface Directive
    permits NativeDirective, PackDirective, EndianDirective {}

record NativeDirective(String arg, TokenPos pos) implements Directive {}

record PackDirective(TokenPos pos) implements Directive {}

record EndianDirective(Endian endian, TokenPos pos) implements Directive {}

enum Endian {
    LITTLE,
    BIG,
}

Module elaborateModule(Expr expr) throws SyntaxError {
    if (!(expr instanceof ListExpr list) || list.items().size() < 3) {
        throw new SyntaxError("invalid module", expr.pos());
    }

    var first = list.items().get(0);
    var second = list.items().get(1);
    var third = list.items().get(2);

    if (!(first instanceof AtomExpr firstAtom)) {
        throw new SyntaxError("invalid module", first.pos());
    }

    if (!firstAtom.name().equals("module")) {
        throw new SyntaxError("invalid module", first.pos());
    }

    if (!(second instanceof AtomExpr name)) {
        throw new SyntaxError("invalid module name", second.pos());
    }

    if (!(third instanceof ListExpr thirdList)) {
        throw new SyntaxError("invalid module", third.pos());
    }

    // Imports have to come before declarations.
    var imports = new ArrayList<Import>();
    var declarations = new ArrayList<Declaration>();

    for (var item : thirdList.items()) {
        if (
            item instanceof ListExpr itemList &&
            !itemList.items().isEmpty() &&
            itemList.items().get(0) instanceof AtomExpr keyword &&
            keyword.name().equals("import")
        ) {
            if (!declarations.isEmpty()) {
                throw new SyntaxError(
                    "import must come before declarations",
                    item.pos()
                );
            }

            imports.add(elaborateImport(itemList));
        } else {
            declarations.add(elaborateDeclaration(item));
        }
    }

    var directives = elaborateDirectives(list, 3);

    return new Module(
        name.name(),
        imports,
        declarations,
        directives,
        list.pos()
    );
}

Import elaborateImport(ListExpr list) throws SyntaxError {
    if (list.items().size() != 2) {
        throw new SyntaxError("invalid import", list.pos());
    }

    var first = list.items().get(0);
    var second = list.items().get(1);

    if (!(first instanceof AtomExpr firstAtom)) {
        throw new SyntaxError("invalid import", first.pos());
    }

    if (!firstAtom.name().equals("import")) {
        throw new SyntaxError("invalid import", first.pos());
    }

    if (!(second instanceof AtomExpr module)) {
        throw new SyntaxError("invalid import", second.pos());
    }

    return new Import(module.name(), list.pos());
}

Declaration elaborateDeclaration(Expr expr) throws SyntaxError {
    if (
        !(expr instanceof ListExpr list) ||
        list.items().isEmpty() ||
        !(list.items().get(0) instanceof AtomExpr keyword)
    ) {
        throw new SyntaxError("invalid declaration", expr.pos());
    }

    return switch (keyword.name()) {
        case "type" -> elaborateAlias(list);
        case "struct" -> elaborateStruct(list);
        case "union" -> elaborateUnion(list);
        default -> throw new SyntaxError("invalid declaration", keyword.pos());
    };
}

record DeclarationHead(String name, List<String> params) {}

// Either a bare name `foo` or a name with params `(foo T N)`.
DeclarationHead elaborateDeclarationHead(Expr expr) throws SyntaxError {
    return switch (expr) {
        case AtomExpr atom -> new DeclarationHead(atom.name(), List.of());
        case ListExpr list -> {
            if (list.items().isEmpty()) {
                throw new SyntaxError("invalid declaration name", list.pos());
            }

            var params = new ArrayList<String>();
            for (var item : list.items()) {
                if (!(item instanceof AtomExpr atom)) {
                    throw new SyntaxError(
                        "invalid declaration name",
                        item.pos()
                    );
                }

                params.add(atom.name());
            }

            var name = params.removeFirst();
            yield new DeclarationHead(name, params);
        }
        default -> throw new SyntaxError(
            "invalid declaration name",
            expr.pos()
        );
    };
}

List<Directive> elaborateDirectives(ListExpr list, int from)
    throws SyntaxError {
    var directives = new ArrayList<Directive>();

    for (int i = from; i < list.items().size(); i++) {
        directives.add(elaborateDirective(list.items().get(i)));
    }

    return directives;
}

Alias elaborateAlias(ListExpr list) throws SyntaxError {
    if (list.items().size() < 3) {
        throw new SyntaxError("invalid type", list.pos());
    }

    var first = list.items().get(0);

    if (!(first instanceof AtomExpr firstAtom)) {
        throw new SyntaxError("invalid type", first.pos());
    }

    if (!firstAtom.name().equals("type")) {
        throw new SyntaxError("invalid type", first.pos());
    }

    var head = elaborateDeclarationHead(list.items().get(1));
    var target = elaborateType(list.items().get(2));
    var directives = elaborateDirectives(list, 3);

    return new Alias(
        head.name(),
        head.params(),
        target,
        directives,
        list.pos()
    );
}

Struct elaborateStruct(ListExpr list) throws SyntaxError {
    if (list.items().size() < 3) {
        throw new SyntaxError("invalid struct", list.pos());
    }

    var first = list.items().get(0);
    var third = list.items().get(2);

    if (!(first instanceof AtomExpr firstAtom)) {
        throw new SyntaxError("invalid struct", first.pos());
    }

    if (!firstAtom.name().equals("struct")) {
        throw new SyntaxError("invalid struct", first.pos());
    }

    var head = elaborateDeclarationHead(list.items().get(1));

    if (!(third instanceof ListExpr thirdList)) {
        throw new SyntaxError("invalid struct", third.pos());
    }

    var fields = new ArrayList<StructField>();
    for (var item : thirdList.items()) {
        if (!(item instanceof ListExpr fieldList)) {
            throw new SyntaxError("invalid struct field", item.pos());
        }

        var field = elaborateStructField(fieldList);
        fields.add(field);
    }

    var directives = elaborateDirectives(list, 3);

    return new Struct(
        head.name(),
        head.params(),
        fields,
        directives,
        list.pos()
    );
}

StructField elaborateStructField(ListExpr list) throws SyntaxError {
    if (list.items().size() < 2) {
        throw new SyntaxError("invalid struct field", list.pos());
    }

    var first = list.items().get(0);
    var second = list.items().get(1);

    if (!(first instanceof AtomExpr firstAtom)) {
        throw new SyntaxError("invalid struct field", first.pos());
    }

    var name = firstAtom.name();
    var type = elaborateType(second);
    var directives = elaborateDirectives(list, 2);

    return new StructField(name, type, directives, list.pos());
}

Union elaborateUnion(ListExpr list) throws SyntaxError {
    if (list.items().size() < 3) {
        throw new SyntaxError("invalid union", list.pos());
    }

    var first = list.items().get(0);
    var third = list.items().get(2);

    if (!(first instanceof AtomExpr firstAtom)) {
        throw new SyntaxError("invalid union", first.pos());
    }

    if (!firstAtom.name().equals("union")) {
        throw new SyntaxError("invalid union", first.pos());
    }

    var head = elaborateDeclarationHead(list.items().get(1));

    if (!(third instanceof ListExpr thirdList)) {
        throw new SyntaxError("invalid union", third.pos());
    }

    var variants = new ArrayList<UnionVariant>();
    for (var item : thirdList.items()) {
        if (!(item instanceof ListExpr variantList)) {
            throw new SyntaxError("invalid union variant", item.pos());
        }

        var variant = elaborateUnionVariant(variantList);
        variants.add(variant);
    }

    var directives = elaborateDirectives(list, 3);

    // The tag type is always u8 for now, but we may want to make it configurable in the future.
    // This also means that the prelude has to define the u8 type.
    return new Union(
        head.name(),
        head.params(),
        variants,
        new RefType("u8", List.of(), list.pos()),
        directives,
        list.pos()
    );
}

UnionVariant elaborateUnionVariant(ListExpr list) throws SyntaxError {
    if (list.items().size() < 2) {
        throw new SyntaxError("invalid union variant", list.pos());
    }

    var first = list.items().get(0);
    var second = list.items().get(1);

    if (!(first instanceof AtomExpr firstAtom)) {
        throw new SyntaxError("invalid union variant", first.pos());
    }

    var name = firstAtom.name();
    var type = elaborateType(second);
    var directives = elaborateDirectives(list, 2);

    return new UnionVariant(name, type, directives, list.pos());
}

Type elaborateType(Expr expr) throws SyntaxError {
    return switch (expr) {
        case AtomExpr atom -> new RefType(atom.name(), List.of(), atom.pos());
        case NumberExpr number -> new NumberType(number.value(), number.pos());
        case ListExpr list -> {
            if (list.items().size() < 2) {
                throw new SyntaxError("invalid type", list.pos());
            }

            var first = list.items().get(0);
            var second = list.items().get(1);

            if (!(first instanceof AtomExpr type)) {
                throw new SyntaxError("invalid type", first.pos());
            }

            yield switch (type.name()) {
                case "bytes" -> {
                    if (!(second instanceof NumberExpr arg)) {
                        throw new SyntaxError("invalid type", second.pos());
                    }

                    yield new BytesType(arg.value(), list.pos());
                }
                case "bits" -> {
                    if (!(second instanceof NumberExpr arg)) {
                        throw new SyntaxError("invalid type", second.pos());
                    }

                    yield new BitsType(arg.value(), list.pos());
                }
                case "array" -> {
                    if (list.items().size() != 3) {
                        throw new SyntaxError("invalid type", list.pos());
                    }

                    var element = elaborateType(list.items().get(1));
                    var size = elaborateType(list.items().get(2));

                    yield new ArrayType(element, size, list.pos());
                }
                case "slice" -> {
                    if (list.items().size() != 3) {
                        throw new SyntaxError("invalid type", list.pos());
                    }

                    var element = elaborateType(list.items().get(1));
                    var size = elaborateType(list.items().get(2));

                    yield new SliceType(element, size, list.pos());
                }
                default -> {
                    var args = new ArrayList<Type>();

                    for (int i = 1; i < list.items().size(); i++) {
                        args.add(elaborateType(list.items().get(i)));
                    }

                    yield new RefType(type.name(), args, list.pos());
                }
            };
        }
        default -> throw new SyntaxError("invalid type", expr.pos());
    };
}

Directive elaborateDirective(Expr expr) throws SyntaxError {
    return switch (expr) {
        case AtomExpr atom -> switch (atom.name()) {
            case "#pack" -> new PackDirective(atom.pos());
            default -> throw new SyntaxError("invalid directive", atom.pos());
        };
        case ListExpr list -> {
            if (list.items().size() < 2) {
                throw new SyntaxError("invalid directive", list.pos());
            }

            var first = list.items().get(0);
            var second = list.items().get(1);

            if (!(first instanceof AtomExpr directive)) {
                throw new SyntaxError("invalid directive", first.pos());
            }

            yield switch (directive.name()) {
                case "#native" -> {
                    if (!(second instanceof AtomExpr arg)) {
                        throw new SyntaxError(
                            "invalid directive",
                            second.pos()
                        );
                    }

                    yield new NativeDirective(arg.name(), list.pos());
                }
                case "#endian" -> {
                    if (!(second instanceof AtomExpr arg)) {
                        throw new SyntaxError(
                            "invalid directive",
                            second.pos()
                        );
                    }

                    yield switch (arg.name()) {
                        case "little" -> new EndianDirective(
                            Endian.LITTLE,
                            list.pos()
                        );
                        case "big" -> new EndianDirective(
                            Endian.BIG,
                            list.pos()
                        );
                        default -> throw new SyntaxError(
                            "invalid directive",
                            arg.pos()
                        );
                    };
                }
                default -> throw new SyntaxError(
                    "invalid directive",
                    directive.pos()
                );
            };
        }
        default -> throw new SyntaxError("invalid directive", expr.pos());
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
        var sexpr = new Parser(tokens).parseFile();
        System.out.println(exprToString(sexpr));
    } catch (SyntaxError e) {
        System.err.println(e.getMessage());
        System.exit(1);
    }
}
