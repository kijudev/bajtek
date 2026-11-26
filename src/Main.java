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

    private final String file;
    private final int line, column;

    private SyntaxError(String file, String message, int line, int column) {
        super(message);
        this.file = file;
        this.line = line;
        this.column = column;
    }

    SyntaxError(String message, int line, int column) {
        this(null, message, line, column);
    }

    SyntaxError(Token token, String message) {
        this(message, token.pos().line(), token.pos().column());
    }

    SyntaxError(String message, TokenPos pos) {
        this(message, pos.line(), pos.column());
    }

    // Attaches the file the error occurred in, unless an inner file already claimed it.
    SyntaxError inFile(String file) {
        return this.file != null
            ? this
            : new SyntaxError(file, super.getMessage(), line, column);
    }

    @Override
    public String getMessage() {
        var location = line + ":" + column + ": " + super.getMessage();
        return file != null ? file + ":" + location : location;
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
        case ListExpr s -> s.items()
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
    String file,
    List<Import> imports,
    List<Declaration> declarations,
    List<Directive> directives,
    TokenPos pos
) {}

record Import(String module, List<Directive> directives, TokenPos pos) {}

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
    permits
        RefType,
        ParamType,
        NumberType,
        BytesType,
        BitsType,
        ArrayType,
        SliceType
{
    TokenPos pos();
}

// After resolution the name is canonical: "module/name".
record RefType(String name, List<Type> args, TokenPos pos) implements Type {}

// A reference to a type parameter of the enclosing declaration, created by resolution.
record ParamType(String name, TokenPos pos) implements Type {}

// A type-level number, e.g. an array length: (array u32 4) or (numarray 4).
record NumberType(int value, TokenPos pos) implements Type {}

// The size is a NumberType or a ParamType of number kind, e.g. (bytes 4) or (bytes N).
record BytesType(Type size, TokenPos pos) implements Type {}

record BitsType(Type size, TokenPos pos) implements Type {}

record ArrayType(Type element, Type size, TokenPos pos) implements Type {}

record SliceType(Type element, Type size, TokenPos pos) implements Type {}

sealed interface Directive
    permits NativeDirective, PackDirective, EndianDirective, OpenDirective {}

record NativeDirective(String arg, TokenPos pos) implements Directive {}

record PackDirective(TokenPos pos) implements Directive {}

record EndianDirective(Endian endian, TokenPos pos) implements Directive {}

// On an import: bring the module's declarations into scope unqualified.
record OpenDirective(TokenPos pos) implements Directive {}

enum Endian {
    LITTLE,
    BIG,
}

Module elaborateModule(Expr expr, String file) throws SyntaxError {
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
        file,
        imports,
        declarations,
        directives,
        list.pos()
    );
}

Import elaborateImport(ListExpr list) throws SyntaxError {
    if (list.items().size() < 2) {
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

    var directives = elaborateDirectives(list, 2);

    return new Import(module.name(), directives, list.pos());
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

List<Directive> elaborateDirectives(
    ListExpr list,
    int from
) throws SyntaxError {
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
    // It is qualified so a local declaration named u8 cannot shadow it; every module implicitly
    // imports the prelude, which has to define the u8 type.
    return new Union(
        head.name(),
        head.params(),
        variants,
        new RefType("prelude/u8", List.of(), list.pos()),
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
                    if (list.items().size() != 2) {
                        throw new SyntaxError("invalid type", list.pos());
                    }

                    yield new BytesType(elaborateType(second), list.pos());
                }
                case "bits" -> {
                    if (list.items().size() != 2) {
                        throw new SyntaxError("invalid type", list.pos());
                    }

                    yield new BitsType(elaborateType(second), list.pos());
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
            case "#open" -> new OpenDirective(atom.pos());
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

// >-------------------------------------------------------------- Module Loader

final class ModuleLoader {

    static final String PRELUDE = "prelude";

    private final Path directory;
    private final Set<String> loaded = new HashSet<>();
    private final List<String> stack = new ArrayList<>();
    private final List<Module> order = new ArrayList<>();

    ModuleLoader(Path directory) {
        this.directory = directory;
    }

    List<Module> loadFile(Path file) throws IOException {
        var fileName = file.getFileName().toString();
        var name = fileName.endsWith(".bajt")
            ? fileName.substring(0, fileName.length() - 5)
            : null;
        load(Files.readString(file), file.toString(), name);
        return order;
    }

    List<Module> loadSource(
        String source,
        String sourceName
    ) throws IOException {
        load(source, sourceName, null);
        return order;
    }

    private Path resolvePath(String name) {
        return directory.resolve(name + ".bajt");
    }

    private void load(
        String source,
        String sourceName,
        String expectedName
    ) throws IOException {
        Module module;

        try {
            module = elaborateModule(
                new Parser(new Lexer(source).lex()).parseFile(),
                sourceName
            );

            if (expectedName != null && !module.name().equals(expectedName)) {
                throw new SyntaxError(
                    "module '" +
                        module.name() +
                        "' must be in file " +
                        module.name() +
                        ".bajt",
                    module.pos()
                );
            }

            module = withPrelude(module);
            stack.add(module.name());

            for (var imp : module.imports()) {
                loadImport(imp);
            }
        } catch (SyntaxError e) {
            throw e.inFile(sourceName);
        }

        stack.removeLast();
        loaded.add(module.name());
        order.add(module);
    }

    // Every module except the prelude itself gets an implicit (import prelude #open). An explicit
    // import of the prelude is left as written.
    private static Module withPrelude(Module module) {
        if (
            module.name().equals(PRELUDE) ||
            module
                .imports()
                .stream()
                .anyMatch(imp -> imp.module().equals(PRELUDE))
        ) {
            return module;
        }

        var prelude = new Import(
            PRELUDE,
            List.of(new OpenDirective(module.pos())),
            module.pos()
        );

        var imports = new ArrayList<Import>();
        imports.add(prelude);
        imports.addAll(module.imports());

        return new Module(
            module.name(),
            module.file(),
            imports,
            module.declarations(),
            module.directives(),
            module.pos()
        );
    }

    private void loadImport(Import imp) throws IOException {
        var name = imp.module();

        if (loaded.contains(name)) {
            return;
        }

        if (stack.contains(name)) {
            var cycle = new ArrayList<>(
                stack.subList(stack.indexOf(name), stack.size())
            );
            cycle.add(name);
            throw new SyntaxError(
                "import cycle: " + String.join(" -> ", cycle),
                imp.pos()
            );
        }

        var file = resolvePath(name);

        if (!Files.isRegularFile(file)) {
            throw new SyntaxError(
                "module '" + name + "' not found (looked for " + file + ")",
                imp.pos()
            );
        }

        load(Files.readString(file), file.toString(), name);
    }
}

// >---------------------------------------------------------- Symbol Resolution

record Program(List<Module> modules, Map<String, Declaration> declarations) {}

record Scope(
    Set<String> imported,
    Map<String, String> names,
    Set<String> params
) {}

final class Resolver {

    static final Set<String> reserved = Set.of(
        "bytes",
        "bits",
        "array",
        "slice"
    );

    private final Map<String, Module> modules = new HashMap<>();
    private final Map<String, Declaration> declarations = new HashMap<>();

    Program resolve(List<Module> input) {
        for (var module : input) {
            try {
                collect(module);
            } catch (SyntaxError e) {
                throw e.inFile(module.file());
            }
        }

        var resolved = new ArrayList<Module>();
        var table = new LinkedHashMap<String, Declaration>();

        for (var module : input) {
            try {
                var result = resolveModule(module);
                resolved.add(result);

                for (var decl : result.declarations()) {
                    table.put(result.name() + "/" + decl.name(), decl);
                }
            } catch (SyntaxError error) {
                throw error.inFile(module.file());
            }
        }

        return new Program(resolved, table);
    }

    private void collect(Module module) {
        modules.put(module.name(), module);

        for (var decl : module.declarations()) {
            checkName(decl.name(), decl.pos());

            var canonical = module.name() + "/" + decl.name();

            if (declarations.putIfAbsent(canonical, decl) != null) {
                throw new SyntaxError(
                    "duplicate declaration '" + decl.name() + "'",
                    decl.pos()
                );
            }
        }
    }

    private void checkName(String name, TokenPos pos) {
        if (reserved.contains(name)) {
            throw new SyntaxError("'" + name + "' is a reserved name", pos);
        }

        if (name.contains("/")) {
            throw new SyntaxError(
                "name '" + name + "' cannot contain '/'",
                pos
            );
        }
    }

    private void bind(
        Map<String, String> names,
        String name,
        String canonical,
        TokenPos pos
    ) {
        var existing = names.putIfAbsent(name, canonical);

        if (existing != null) {
            throw new SyntaxError(
                "'" + canonical + "' conflicts with '" + existing + "'",
                pos
            );
        }
    }

    private Module resolveModule(Module module) {
        var imported = new HashSet<String>();
        var names = new HashMap<String, String>();

        imported.add(module.name());

        for (var decl : module.declarations()) {
            bind(
                names,
                decl.name(),
                module.name() + "/" + decl.name(),
                decl.pos()
            );
        }

        for (var imp : module.imports()) {
            if (!imported.add(imp.module())) {
                throw new SyntaxError(
                    "duplicate import of '" + imp.module() + "'",
                    imp.pos()
                );
            }

            if (
                imp
                    .directives()
                    .stream()
                    .anyMatch(d -> d instanceof OpenDirective)
            ) {
                for (var decl : modules.get(imp.module()).declarations()) {
                    bind(
                        names,
                        decl.name(),
                        imp.module() + "/" + decl.name(),
                        imp.pos()
                    );
                }
            }
        }

        var resolved = new ArrayList<Declaration>();

        for (var decl : module.declarations()) {
            var params = new HashSet<String>();

            for (var param : decl.params()) {
                checkName(param, decl.pos());

                if (names.containsKey(param)) {
                    throw new SyntaxError(
                        "type parameter '" +
                            param +
                            "' conflicts with '" +
                            names.get(param) +
                            "'",
                        decl.pos()
                    );
                }

                if (!params.add(param)) {
                    throw new SyntaxError(
                        "duplicate type parameter '" + param + "'",
                        decl.pos()
                    );
                }
            }

            resolved.add(
                resolveDeclaration(decl, new Scope(imported, names, params))
            );
        }

        return new Module(
            module.name(),
            module.file(),
            module.imports(),
            resolved,
            module.directives(),
            module.pos()
        );
    }

    private Declaration resolveDeclaration(Declaration decl, Scope scope) {
        return switch (decl) {
            case Alias a -> new Alias(
                a.name(),
                a.params(),
                resolveType(a.target(), scope),
                a.directives(),
                a.pos()
            );
            case Struct s -> new Struct(
                s.name(),
                s.params(),
                s
                    .fields()
                    .stream()
                    .map(f ->
                        new StructField(
                            f.name(),
                            resolveType(f.type(), scope),
                            f.directives(),
                            f.pos()
                        )
                    )
                    .toList(),
                s.directives(),
                s.pos()
            );
            case Union u -> new Union(
                u.name(),
                u.params(),
                u
                    .variants()
                    .stream()
                    .map(v ->
                        new UnionVariant(
                            v.name(),
                            resolveType(v.type(), scope),
                            v.directives(),
                            v.pos()
                        )
                    )
                    .toList(),
                resolveType(u.tag(), scope),
                u.directives(),
                u.pos()
            );
        };
    }

    private Type resolveType(Type type, Scope scope) {
        return switch (type) {
            case RefType r -> resolveRef(r, scope);
            case ArrayType a -> new ArrayType(
                resolveType(a.element(), scope),
                resolveType(a.size(), scope),
                a.pos()
            );
            case SliceType s -> new SliceType(
                resolveType(s.element(), scope),
                resolveType(s.size(), scope),
                s.pos()
            );
            case BytesType b -> new BytesType(
                resolveType(b.size(), scope),
                b.pos()
            );
            case BitsType b -> new BitsType(
                resolveType(b.size(), scope),
                b.pos()
            );
            case ParamType p -> p;
            case NumberType n -> n;
        };
    }

    private Type resolveRef(RefType ref, Scope scope) {
        var name = ref.name();
        String canonical;

        if (scope.params().contains(name)) {
            if (!ref.args().isEmpty()) {
                throw new SyntaxError(
                    "type parameter '" + name + "' cannot take arguments",
                    ref.pos()
                );
            }

            return new ParamType(name, ref.pos());
        } else if (name.contains("/")) {
            var module = name.substring(0, name.indexOf('/'));

            if (!scope.imported().contains(module)) {
                throw new SyntaxError(
                    "module '" + module + "' is not imported",
                    ref.pos()
                );
            }

            if (!declarations.containsKey(name)) {
                throw new SyntaxError("unknown type '" + name + "'", ref.pos());
            }

            canonical = name;
        } else {
            canonical = scope.names().get(name);

            if (canonical == null) {
                throw new SyntaxError("unknown type '" + name + "'", ref.pos());
            }
        }

        var decl = declarations.get(canonical);

        if (decl.params().size() != ref.args().size()) {
            throw new SyntaxError(
                "'" +
                    canonical +
                    "' expects " +
                    decl.params().size() +
                    " argument(s), got " +
                    ref.args().size(),
                ref.pos()
            );
        }

        var args = ref
            .args()
            .stream()
            .map(arg -> resolveType(arg, scope))
            .toList();

        return new RefType(canonical, args, ref.pos());
    }
}

// >-------------------------------------------------------------- Kind Checking

final class KindChecker {

    private static final int TYPE = 0,
        NUMBER = 1;

    private final Map<String, Integer> bases = new HashMap<>();
    private int[] parent;

    void check(Program program) {
        int next = 2;

        for (var entry : program.declarations().entrySet()) {
            bases.put(entry.getKey(), next);
            next += entry.getValue().params().size();
        }

        parent = new int[next];

        for (int i = 0; i < next; i++) {
            parent[i] = i;
        }

        for (var module : program.modules()) {
            try {
                for (var decl : module.declarations()) {
                    checkDeclaration(module.name() + "/" + decl.name(), decl);
                }
            } catch (SyntaxError e) {
                throw e.inFile(module.file());
            }
        }
    }

    private void checkDeclaration(String canonical, Declaration decl) {
        var params = new HashMap<String, Integer>();
        int base = bases.get(canonical);

        for (int i = 0; i < decl.params().size(); i++) {
            params.put(decl.params().get(i), base + i);
        }

        switch (decl) {
            case Alias a -> expect(a.target(), TYPE, params);
            case Struct s -> {
                for (var field : s.fields()) {
                    expect(field.type(), TYPE, params);
                }
            }
            case Union u -> {
                for (var variant : u.variants()) {
                    expect(variant.type(), TYPE, params);
                }

                expect(u.tag(), TYPE, params);
            }
        }
    }

    private void expect(Type type, int expected, Map<String, Integer> params) {
        switch (type) {
            case ParamType p -> unify(expected, params.get(p.name()), p);
            case NumberType n -> unify(expected, NUMBER, n);
            case RefType r -> {
                unify(expected, TYPE, r);
                int base = bases.get(r.name());

                for (int i = 0; i < r.args().size(); i++) {
                    expect(r.args().get(i), base + i, params);
                }
            }
            case BytesType b -> {
                unify(expected, TYPE, b);
                expect(b.size(), NUMBER, params);
            }
            case BitsType b -> {
                unify(expected, TYPE, b);
                expect(b.size(), NUMBER, params);
            }
            case ArrayType a -> {
                unify(expected, TYPE, a);
                expect(a.element(), TYPE, params);
                expect(a.size(), NUMBER, params);
            }
            case SliceType s -> {
                unify(expected, TYPE, s);
                expect(s.element(), TYPE, params);
                expect(s.size(), TYPE, params);
            }
        }
    }

    private int find(int node) {
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }

        return node;
    }

    private void unify(int expected, int actual, Type at) {
        int a = find(expected);
        int b = find(actual);

        if (a == b) {
            return;
        }

        if (a <= NUMBER && b <= NUMBER) {
            var got =
                at instanceof ParamType p
                    ? "type parameter '" +
                      p.name() +
                      "', which is " +
                      describe(b)
                    : describe(b);

            throw new SyntaxError(
                "expected " + describe(a) + ", got " + got,
                at.pos()
            );
        }

        if (a <= NUMBER) {
            parent[b] = a;
        } else {
            parent[a] = b;
        }
    }

    private static String describe(int kind) {
        return kind == TYPE ? "a type" : "a number";
    }
}

// >--------------------------------------------------------- Monomorphization

sealed interface MArg permits MType, MNumber {}

sealed interface MType
    extends MArg
    permits MRef, MBytes, MBits, MArray, MSlice {}

record MNumber(int value) implements MArg {}

record Instance(String name, List<MArg> args) {
    Instance {
        args = List.copyOf(args);
    }
}

record MRef(Instance instance) implements MType {}

record MBytes(int size) implements MType {}

record MBits(int size) implements MType {}

record MArray(MType element, int length) implements MType {}

record MSlice(MType element, MType length) implements MType {}

sealed interface MDeclaration permits MAlias, MStruct, MUnion {
    Instance instance();

    TokenPos pos();
}

record MAlias(
    Instance instance,
    MType target,
    List<Directive> directives,
    TokenPos pos
) implements MDeclaration {}

record MStruct(
    Instance instance,
    List<MStructField> fields,
    List<Directive> directives,
    TokenPos pos
) implements MDeclaration {}

record MStructField(
    String name,
    MType type,
    List<Directive> directives,
    TokenPos pos
) {}

record MUnion(
    Instance instance,
    List<MUnionVariant> variants,
    MType tag,
    List<Directive> directives,
    TokenPos pos
) implements MDeclaration {}

record MUnionVariant(
    String name,
    MType type,
    List<Directive> directives,
    TokenPos pos
) {}

record MProgram(List<MDeclaration> declarations) {}

String monoToString(MArg arg) {
    return switch (arg) {
        case MNumber n -> Integer.toString(n.value());
        case MRef r -> instanceToString(r.instance());
        case MBytes b -> "(bytes " + b.size() + ")";
        case MBits b -> "(bits " + b.size() + ")";
        case MArray a -> "(array " +
            monoToString(a.element()) +
            " " +
            a.length() +
            ")";
        case MSlice s -> "(slice " +
            monoToString(s.element()) +
            " " +
            monoToString(s.length()) +
            ")";
    };
}

String instanceToString(Instance instance) {
    if (instance.args().isEmpty()) {
        return instance.name();
    }

    return instance
        .args()
        .stream()
        .map(arg -> monoToString(arg))
        .collect(Collectors.joining(" ", "(" + instance.name() + " ", ")"));
}

final class Monomorphizer {

    static final int MAX_SIZE = 256;

    private final Program program;
    private final Map<String, String> files = new HashMap<>();
    private final Map<Instance, MDeclaration> done = new LinkedHashMap<>();
    private final List<Instance> stack = new ArrayList<>();

    Monomorphizer(Program program) {
        this.program = program;

        for (var module : program.modules()) {
            for (var decl : module.declarations()) {
                files.put(module.name() + "/" + decl.name(), module.file());
            }
        }
    }

    MProgram monomorphize() {
        for (var entry : program.declarations().entrySet()) {
            if (entry.getValue().params().isEmpty()) {
                instantiate(
                    new Instance(entry.getKey(), List.of()),
                    entry.getValue().pos()
                );
            }
        }

        return new MProgram(List.copyOf(done.values()));
    }

    private void instantiate(Instance instance, TokenPos at) {
        if (
            instance
                .args()
                .stream()
                .mapToInt(arg -> size(arg))
                .sum() > MAX_SIZE
        ) {
            throw new SyntaxError(
                "arguments of '" +
                    instance.name() +
                    "' are too large; does it use itself with growing arguments?",
                at
            );
        }

        if (done.containsKey(instance) || stack.contains(instance)) {
            return;
        }

        var decl = program.declarations().get(instance.name());
        var env = new HashMap<String, MArg>();

        for (int i = 0; i < decl.params().size(); i++) {
            env.put(decl.params().get(i), instance.args().get(i));
        }

        stack.add(instance);

        try {
            done.put(instance, instantiateDeclaration(instance, decl, env));
        } catch (SyntaxError e) {
            throw e.inFile(files.get(instance.name()));
        } finally {
            stack.removeLast();
        }
    }

    private static int size(MArg arg) {
        return switch (arg) {
            case MNumber _ -> 1;
            case MBytes _ -> 1;
            case MBits _ -> 1;
            case MArray a -> 1 + size(a.element());
            case MSlice s -> 1 + size(s.element()) + size(s.length());
            case MRef r -> 1 +
                r
                    .instance()
                    .args()
                    .stream()
                    .mapToInt(a -> size(a))
                    .sum();
        };
    }

    private MDeclaration instantiateDeclaration(
        Instance instance,
        Declaration decl,
        Map<String, MArg> env
    ) {
        return switch (decl) {
            case Alias a -> new MAlias(
                instance,
                monoType(a.target(), env),
                a.directives(),
                a.pos()
            );
            case Struct s -> new MStruct(
                instance,
                s
                    .fields()
                    .stream()
                    .map(f ->
                        new MStructField(
                            f.name(),
                            monoType(f.type(), env),
                            f.directives(),
                            f.pos()
                        )
                    )
                    .toList(),
                s.directives(),
                s.pos()
            );
            case Union u -> new MUnion(
                instance,
                u
                    .variants()
                    .stream()
                    .map(v ->
                        new MUnionVariant(
                            v.name(),
                            monoType(v.type(), env),
                            v.directives(),
                            v.pos()
                        )
                    )
                    .toList(),
                monoType(u.tag(), env),
                u.directives(),
                u.pos()
            );
        };
    }

    private MArg monoArg(Type type, Map<String, MArg> env) {
        return switch (type) {
            case NumberType n -> new MNumber(n.value());
            case ParamType p -> env.get(p.name());
            default -> monoType(type, env);
        };
    }

    private MType monoType(Type type, Map<String, MArg> env) {
        return switch (type) {
            case RefType r -> {
                var instance = new Instance(
                    r.name(),
                    r
                        .args()
                        .stream()
                        .map(arg -> monoArg(arg, env))
                        .toList()
                );
                instantiate(instance, r.pos());
                yield new MRef(instance);
            }
            case BytesType b -> new MBytes(monoNumber(b.size(), env));
            case BitsType b -> new MBits(monoNumber(b.size(), env));
            case ArrayType a -> new MArray(
                monoType(a.element(), env),
                monoNumber(a.size(), env)
            );
            case SliceType s -> new MSlice(
                monoType(s.element(), env),
                monoType(s.size(), env)
            );
            case ParamType p -> {
                if (!(env.get(p.name()) instanceof MType t)) {
                    throw new IllegalStateException(
                        "number parameter '" + p.name() + "' used as a type"
                    );
                }

                yield t;
            }
            case NumberType n -> throw new IllegalStateException(
                "number " + n.value() + " used as a type"
            );
        };
    }

    private int monoNumber(Type type, Map<String, MArg> env) {
        if (!(monoArg(type, env) instanceof MNumber n)) {
            throw new IllegalStateException("type used as a number");
        }

        return n.value();
    }
}

// >------------------------------------------------------------ Code Generation

final class CodegenError extends RuntimeException {

    CodegenError(String message) {
        super(message);
    }
}

abstract class CodeWriter<W extends CodeWriter<W>> {

    private final StringBuilder out = new StringBuilder();
    private final String ident_unit;
    private int ident_level = 0;

    CodeWriter(String ident_unit) {
        this.ident_unit = ident_unit;
    }

    @SuppressWarnings("unchecked")
    private W self() {
        return (W) this;
    }

    String code() {
        return out.toString();
    }

    W ident() {
        ident_level++;
        return self();
    }

    W unident() {
        if (ident_level == 0) {
            throw new IllegalStateException("unident() called too many times");
        }

        ident_level--;
        return self();
    }

    W line(String text) {
        if (!text.isEmpty()) {
            for (int i = 0; i < ident_level; i++) {
                out.append(ident_unit);
            }

            out.append(text);
        }

        out.append("\n");
        return self();
    }

    W codeline(String text) {
        return line(text + ";");
    }

    W newline() {
        out.append("\n");
        return self();
    }

    W beginBlock(String header) {
        return line(header + " {").ident();
    }

    W endBlock(String closing) {
        return unident().line(closing);
    }

    W comment(String text) {
        if (text.contains("\n")) {
            return commentBlock(text);
        }

        return line("// " + text);
    }

    W commentBlock(String text) {
        line("/*");
        for (var line : text.split("\n")) {
            line(" * " + line);
        }
        return line(" */");
    }
}

final class NameMangler {

    private final Set<String> keywords;
    private final String language;

    NameMangler(Set<String> keywords, String language) {
        this.keywords = keywords;
        this.language = language;
    }

    String identifier(String name) {
        var id = name.replaceAll("[^A-Za-z0-9_]", "_");
        return keywords.contains(id) ? id + "_" : id;
    }

    // Splits a schema name like `chat-message` or `chatMessage` into its words, which each
    // language joins in its own style.
    static List<String> words(String name) {
        return Arrays.stream(name.split("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])"))
            .filter(w -> !w.isEmpty())
            .toList();
    }

    // The words of an instance name, mangled from its arguments, e.g. `pair u16 text`.
    static List<String> words(Instance instance) {
        var out = new ArrayList<>(
            words(instance.name().substring(instance.name().indexOf('/') + 1))
        );

        for (var arg : instance.args()) {
            argWords(arg, out);
        }

        return out;
    }

    private static void argWords(MArg arg, List<String> out) {
        switch (arg) {
            case MNumber n -> out.add(Integer.toString(n.value()));
            case MRef r -> out.addAll(words(r.instance()));
            case MBytes b -> out.addAll(
                List.of("bytes", Integer.toString(b.size()))
            );
            case MBits b -> out.addAll(
                List.of("bits", Integer.toString(b.size()))
            );
            case MArray a -> {
                out.add("array");
                argWords(a.element(), out);
                out.add(Integer.toString(a.length()));
            }
            case MSlice s -> {
                out.add("slice");
                argWords(s.element(), out);
            }
        }
    }

    List<String> memberNames(
        Instance owner,
        List<String> members,
        Set<String> reserved
    ) {
        var out = new ArrayList<String>();

        for (var member : members) {
            var id = identifier(member);

            while (reserved.contains(id)) {
                id = id + "_";
            }

            if (out.contains(id)) {
                throw new CodegenError(
                    "two members of '" +
                        instanceToString(owner) +
                        "' both become " +
                        language +
                        " name " +
                        id
                );
            }

            out.add(id);
        }

        return out;
    }
}

String moduleOf(Instance instance) {
    return instance.name().substring(0, instance.name().indexOf('/'));
}

String nativeKind(MAlias alias) {
    return alias
        .directives()
        .stream()
        .filter(d -> d instanceof NativeDirective)
        .map(d -> ((NativeDirective) d).arg())
        .findFirst()
        .orElse(null);
}

int nativeSize(MAlias alias) {
    if (!(alias.target() instanceof MBytes bytes)) {
        throw new CodegenError(
            "'" +
                instanceToString(alias.instance()) +
                "' is #native, so it has to be an alias of (bytes N)"
        );
    }

    return bytes.size();
}

CodegenError noNativeType(MAlias alias, String language) {
    return new CodegenError(
        "no " +
            language +
            " type for (#native " +
            nativeKind(alias) +
            ") of " +
            nativeSize(alias) +
            " byte(s) in '" +
            instanceToString(alias.instance()) +
            "'"
    );
}

// >-------------------------------------------------------- C++ Code Generation

final class CppCodeWriter extends CodeWriter<CppCodeWriter> {

    CppCodeWriter(String ident_unit) {
        super(ident_unit);
    }

    CppCodeWriter include(String name) {
        return line("#include <" + name + ">");
    }

    CppCodeWriter forwardStruct(String name) {
        return codeline("struct " + name);
    }

    CppCodeWriter beginStruct(String name) {
        return beginBlock("struct " + name);
    }

    CppCodeWriter endStruct() {
        return endBlock("};");
    }

    CppCodeWriter structField(String type, String name) {
        return codeline(type + " " + name);
    }

    CppCodeWriter beginClass(String name) {
        return beginBlock("class " + name);
    }

    CppCodeWriter endClass() {
        return endBlock("};");
    }

    CppCodeWriter classField(String type, String name) {
        return codeline(type + " " + name);
    }

    CppCodeWriter beginFunction(
        String returnType,
        String name,
        List<String> params
    ) {
        return beginBlock(
            returnType + " " + name + "(" + String.join(", ", params) + ")"
        );
    }

    CppCodeWriter endFunction() {
        return endBlock("}");
    }

    CppCodeWriter beginEnumClass(String name, String type) {
        return beginBlock("enum class " + name + " : " + type);
    }

    CppCodeWriter endEnumClass() {
        return endBlock("};");
    }

    CppCodeWriter enumClassVariant(String name, String value) {
        return line(name + " = " + value + ",");
    }

    CppCodeWriter alias(String name, String target) {
        return codeline("using " + name + " = " + target);
    }

    CppCodeWriter beginNamespace(String name) {
        return beginBlock("namespace " + name);
    }

    CppCodeWriter endNamespace() {
        return endBlock("}");
    }
}

final class CppCodeGenerator {

    static final Set<String> keywords = Set.of(
        "alignas",
        "alignof",
        "and",
        "and_eq",
        "asm",
        "auto",
        "bitand",
        "bitor",
        "bool",
        "break",
        "case",
        "catch",
        "char",
        "char8_t",
        "char16_t",
        "char32_t",
        "class",
        "co_await",
        "co_return",
        "co_yield",
        "compl",
        "concept",
        "const",
        "const_cast",
        "consteval",
        "constexpr",
        "constinit",
        "continue",
        "decltype",
        "default",
        "delete",
        "do",
        "double",
        "dynamic_cast",
        "else",
        "enum",
        "explicit",
        "export",
        "extern",
        "false",
        "float",
        "for",
        "friend",
        "goto",
        "if",
        "inline",
        "int",
        "long",
        "mutable",
        "namespace",
        "new",
        "noexcept",
        "not",
        "not_eq",
        "nullptr",
        "operator",
        "or",
        "or_eq",
        "private",
        "protected",
        "public",
        "register",
        "reinterpret_cast",
        "requires",
        "return",
        "short",
        "signed",
        "sizeof",
        "static",
        "static_assert",
        "static_cast",
        "struct",
        "switch",
        "template",
        "this",
        "thread_local",
        "throw",
        "true",
        "try",
        "typedef",
        "typeid",
        "typename",
        "union",
        "unsigned",
        "using",
        "virtual",
        "void",
        "volatile",
        "wchar_t",
        "while",
        "xor",
        "xor_eq"
    );

    private final CppCodeWriter writer;
    private final String namespace;
    private final NameMangler mangler = new NameMangler(keywords, "C++");
    private final Map<Instance, MDeclaration> declarations = new HashMap<>();
    private final Map<Instance, String> names = new HashMap<>();
    private String currentNamespace = null;

    private boolean namespaceStart = true;
    private boolean afterStruct = false;

    CppCodeGenerator(String namespace, String ident) {
        this.namespace = namespace;
        this.writer = new CppCodeWriter(ident);
    }

    Map<String, String> generate(MProgram program, String header) {
        for (var decl : program.declarations()) {
            declarations.put(decl.instance(), decl);
        }

        assignNames(program);
        var ordered = order(program);

        writer.line("#pragma once").newline();
        writer.comment("Generated by bajtek. Do not edit.").newline();
        includes();
        writer.newline();

        for (var decl : ordered) {
            if (!(decl instanceof MAlias)) {
                enterNamespace(decl.instance());
                writer.forwardStruct(names.get(decl.instance()));
            }
        }

        for (var decl : ordered) {
            enterNamespace(decl.instance());

            switch (decl) {
                case MAlias a -> emitAlias(a);
                case MStruct s -> emitStruct(s);
                case MUnion u -> emitUnion(u);
            }
        }

        if (currentNamespace != null) {
            writer.endNamespace();
        }

        return Map.of(header + ".hpp", writer.code());
    }

    private void includes() {
        writer
            .include("array")
            .include("bitset")
            .include("cstddef")
            .include("cstdint")
            .include("variant")
            .include("vector");
    }

    private String namespaceOf(Instance instance) {
        var module = mangler.identifier(
            snake(NameMangler.words(moduleOf(instance)))
        );
        return namespace + "::" + (module.equals("std") ? "std_" : module);
    }

    private void assignNames(MProgram program) {
        var taken = new HashMap<String, Instance>();
        var modules = new HashMap<String, String>();

        for (var decl : program.declarations()) {
            var instance = decl.instance();
            var ns = namespaceOf(instance);
            var module = modules.putIfAbsent(ns, moduleOf(instance));

            if (module != null && !module.equals(moduleOf(instance))) {
                throw new CodegenError(
                    "modules '" +
                        module +
                        "' and '" +
                        moduleOf(instance) +
                        "' both become C++ namespace " +
                        ns
                );
            }

            var name = mangler.identifier(snake(NameMangler.words(instance)));
            var qualified = ns + "::" + name;
            var other = taken.putIfAbsent(qualified, instance);

            if (other != null) {
                throw new CodegenError(
                    "'" +
                        instanceToString(other) +
                        "' and '" +
                        instanceToString(instance) +
                        "' both become C++ type " +
                        qualified
                );
            }

            names.put(instance, name);
        }
    }

    // C++ needs a full definition of every type stored by value, and every alias before it is
    // used. Structs and unions are forward declared, which is enough for std::vector elements.
    private List<MDeclaration> order(MProgram program) {
        var done = new LinkedHashSet<Instance>();
        var visiting = new HashSet<Instance>();

        for (var decl : program.declarations()) {
            visit(decl.instance(), visiting, done);
        }

        return done.stream().map(declarations::get).toList();
    }

    private void visit(
        Instance instance,
        Set<Instance> visiting,
        Set<Instance> done
    ) {
        if (done.contains(instance)) {
            return;
        }

        if (!visiting.add(instance)) {
            throw new CodegenError(
                "'" +
                    instanceToString(instance) +
                    "' contains itself by value or through an alias, which C++ cannot" +
                    " express"
            );
        }

        for (var dependency : dependencies(declarations.get(instance))) {
            visit(dependency, visiting, done);
        }

        visiting.remove(instance);
        done.add(instance);
    }

    private Set<Instance> dependencies(MDeclaration decl) {
        var out = new LinkedHashSet<Instance>();

        switch (decl) {
            case MAlias a -> declared(a.target(), out);
            case MStruct s -> {
                for (var field : s.fields()) {
                    complete(field.type(), out, new HashSet<>());
                }
            }
            case MUnion u -> {
                for (var variant : u.variants()) {
                    complete(variant.type(), out, new HashSet<>());
                }
            }
        }

        return out;
    }

    private void declared(MType type, Set<Instance> out) {
        switch (type) {
            case MRef r -> {
                if (declarations.get(r.instance()) instanceof MAlias) {
                    out.add(r.instance());
                }
            }
            case MArray a -> declared(a.element(), out);
            case MSlice s -> declared(s.element(), out);
            case MBytes _ -> {
            }
            case MBits _ -> {
            }
        }
    }

    // A value of the type is stored, so everything it is made of must be defined, including
    // whatever an alias stands for.
    private void complete(MType type, Set<Instance> out, Set<Instance> seen) {
        switch (type) {
            case MRef r -> {
                out.add(r.instance());

                if (
                    declarations.get(r.instance()) instanceof MAlias a &&
                    seen.add(r.instance())
                ) {
                    complete(a.target(), out, seen);
                }
            }
            case MArray a -> complete(a.element(), out, seen);
            case MSlice s -> declared(s.element(), out);
            case MBytes _ -> {
            }
            case MBits _ -> {
            }
        }
    }

    private void enterNamespace(Instance instance) {
        var ns = namespaceOf(instance);

        if (ns.equals(currentNamespace)) {
            return;
        }

        if (currentNamespace != null) {
            writer.endNamespace().newline();
        }

        writer.beginNamespace(ns);
        currentNamespace = ns;
        namespaceStart = true;
        afterStruct = false;
    }

    private void separate(boolean struct) {
        if (!namespaceStart && (struct || afterStruct)) {
            writer.newline();
        }

        namespaceStart = false;
        afterStruct = struct;
    }

    private String type(MType type) {
        return switch (type) {
            case MRef r -> "::" +
                namespaceOf(r.instance()) +
                "::" +
                names.get(r.instance());
            case MBytes b -> "std::array<std::byte, " + b.size() + ">";
            case MBits b -> bits(b.size());
            case MArray a -> "std::array<" +
                type(a.element()) +
                ", " +
                a.length() +
                ">";
            case MSlice s -> "std::vector<" + type(s.element()) + ">";
        };
    }

    // C++ names are snake_case, like the standard library.
    private static String snake(List<String> words) {
        return words
            .stream()
            .map(w -> w.toLowerCase())
            .collect(Collectors.joining("_"));
    }

    private static String bits(int size) {
        if (size <= 8) return "std::uint8_t";
        if (size <= 16) return "std::uint16_t";
        if (size <= 32) return "std::uint32_t";
        if (size <= 64) return "std::uint64_t";
        return "std::bitset<" + size + ">";
    }

    private String nativeType(MAlias alias, String kind) {
        var type = switch (kind + " " + nativeSize(alias)) {
            case "unsigned 1" -> "std::uint8_t";
            case "unsigned 2" -> "std::uint16_t";
            case "unsigned 4" -> "std::uint32_t";
            case "unsigned 8" -> "std::uint64_t";
            case "signed 1" -> "std::int8_t";
            case "signed 2" -> "std::int16_t";
            case "signed 4" -> "std::int32_t";
            case "signed 8" -> "std::int64_t";
            case "float 4" -> "float";
            case "float 8" -> "double";
            case "bool 1" -> "bool";
            case "char 1" -> "char";
            default -> throw noNativeType(alias, "C++");
        };

        return type;
    }

    private void emitAlias(MAlias alias) {
        var kind = nativeKind(alias);
        var target =
            kind != null ? nativeType(alias, kind) : type(alias.target());

        separate(false);
        writer.alias(names.get(alias.instance()), target);
    }

    private void emitStruct(MStruct struct) {
        var fields = mangler.memberNames(
            struct.instance(),
            struct
                .fields()
                .stream()
                .map(f -> snake(NameMangler.words(f.name())))
                .toList(),
            Set.of()
        );

        separate(true);
        writer.beginStruct(names.get(struct.instance()));

        for (int i = 0; i < fields.size(); i++) {
            writer.structField(
                type(struct.fields().get(i).type()),
                fields.get(i)
            );
        }

        writer.endStruct();
    }

    // Each variant is a wrapper struct, so variants keep their names and two variants of the
    // same type stay distinct. The variant member is called `value`, so no wrapper may be, and a
    // nested struct cannot have the name of the union holding it either.
    private void emitUnion(MUnion union) {
        if (union.variants().isEmpty()) {
            throw new CodegenError(
                "'" + instanceToString(union.instance()) + "' has no variants"
            );
        }

        var name = names.get(union.instance());
        var variants = mangler.memberNames(
            union.instance(),
            union
                .variants()
                .stream()
                .map(v -> snake(NameMangler.words(v.name())))
                .toList(),
            Set.of("value", name)
        );

        separate(true);
        writer.beginStruct(name);

        for (int i = 0; i < variants.size(); i++) {
            writer.line(
                "struct " +
                    variants.get(i) +
                    " { " +
                    type(union.variants().get(i).type()) +
                    " value; };"
            );
        }

        writer
            .newline()
            .structField(
                "std::variant<" + String.join(", ", variants) + ">",
                "value"
            )
            .endStruct();
    }
}

// >------------------------------------------------------- Java Code Generation

final class JavaCodeWriter extends CodeWriter<JavaCodeWriter> {

    JavaCodeWriter(String ident_unit) {
        super(ident_unit);
    }

    JavaCodeWriter packageDeclaration(String name) {
        return codeline("package " + name);
    }

    JavaCodeWriter beginClass(String modifiers, String name) {
        return beginBlock(modifiers + "class " + name);
    }

    JavaCodeWriter endClass() {
        return endBlock("}");
    }

    JavaCodeWriter beginSealedInterface(String modifiers, String name) {
        return beginBlock(modifiers + "sealed interface " + name);
    }

    JavaCodeWriter endInterface() {
        return endBlock("}");
    }

    // A record without a body, on one line when it has at most one component.
    JavaCodeWriter record(
        String modifiers,
        String name,
        List<String> components,
        String implemented
    ) {
        var head = modifiers + "record " + name + "(";
        var tail =
            ")" +
            (implemented != null ? " implements " + implemented : "") +
            " {}";

        if (components.size() <= 1) {
            return line(head + String.join("", components) + tail);
        }

        line(head).ident();

        for (int i = 0; i < components.size(); i++) {
            line(components.get(i) + (i < components.size() - 1 ? "," : ""));
        }

        return unident().line(tail);
    }
}

final class JavaCodeGenerator {

    static final Set<String> keywords = Set.of(
        "_",
        "abstract",
        "assert",
        "boolean",
        "break",
        "byte",
        "case",
        "catch",
        "char",
        "class",
        "const",
        "continue",
        "default",
        "do",
        "double",
        "else",
        "enum",
        "extends",
        "false",
        "final",
        "finally",
        "float",
        "for",
        "goto",
        "if",
        "implements",
        "import",
        "instanceof",
        "int",
        "interface",
        "long",
        "native",
        "new",
        "null",
        "package",
        "permits",
        "private",
        "protected",
        "public",
        "record",
        "return",
        "sealed",
        "short",
        "static",
        "strictfp",
        "super",
        "switch",
        "synchronized",
        "this",
        "throw",
        "throws",
        "transient",
        "true",
        "try",
        "var",
        "void",
        "volatile",
        "while",
        "yield"
    );

    static final Set<String> recordMethods = Set.of(
        "clone",
        "finalize",
        "getClass",
        "hashCode",
        "notify",
        "notifyAll",
        "toString",
        "wait"
    );

    static final Map<String, String> boxes = Map.of(
        "byte",
        "java.lang.Byte",
        "short",
        "java.lang.Short",
        "int",
        "java.lang.Integer",
        "long",
        "java.lang.Long",
        "float",
        "java.lang.Float",
        "double",
        "java.lang.Double",
        "boolean",
        "java.lang.Boolean"
    );

    private final String pkg;
    private final String ident;
    private final NameMangler mangler = new NameMangler(keywords, "Java");
    private final Map<Instance, String> natives = new HashMap<>();
    private final Map<String, String> holders = new HashMap<>();
    private final Map<Instance, String> names = new HashMap<>();

    // Types are always written fully qualified, which breaks if a class in scope has the name of
    // the first package segment, so no generated class may be called that or `java`.
    private final Set<String> reserved;

    JavaCodeGenerator(String pkg, String ident) {
        this.pkg = pkg;
        this.ident = ident;
        this.reserved = Set.of(pkg.split("\\.")[0], "java");
    }

    // Generates one file per module, `<package dirs>/<module>.java`, holding a class that
    // nests every instance of the module. Modules with nothing to emit get no file.
    Map<String, String> generate(MProgram program) {
        var modules = new LinkedHashMap<String, List<MDeclaration>>();

        for (var decl : program.declarations()) {
            modules
                .computeIfAbsent(moduleOf(decl.instance()), _ ->
                    new ArrayList<>()
                )
                .add(decl);

            if (decl instanceof MAlias a && nativeKind(a) != null) {
                natives.put(a.instance(), nativeType(a, nativeKind(a)));
            }
        }

        assignNames(modules);

        var files = new LinkedHashMap<String, String>();

        for (var entry : modules.entrySet()) {
            var emitted = entry
                .getValue()
                .stream()
                .filter(d -> !natives.containsKey(d.instance()))
                .toList();

            if (emitted.isEmpty()) {
                continue;
            }

            var holder = holders.get(entry.getKey());
            files.put(
                pkg.replace('.', '/') + "/" + holder + ".java",
                emitModule(holder, emitted)
            );
        }

        return files;
    }

    private static String renamed(String id, Set<String> reserved) {
        while (reserved.contains(id)) {
            id = id + "_";
        }

        return id;
    }

    private void assignNames(Map<String, List<MDeclaration>> modules) {
        var files = new HashMap<String, String>();

        for (var module : modules.keySet()) {
            var holder = renamed(
                mangler.identifier(pascal(NameMangler.words(module))),
                reserved
            );
            var other = files.putIfAbsent(holder.toLowerCase(), module);

            if (other != null) {
                throw new CodegenError(
                    "modules '" +
                        other +
                        "' and '" +
                        module +
                        "' both become Java file " +
                        holder +
                        ".java"
                );
            }

            holders.put(module, holder);
        }

        for (var entry : modules.entrySet()) {
            var holder = holders.get(entry.getKey());
            var nested = new HashSet<>(reserved);
            nested.add(holder);

            var taken = new HashMap<String, Instance>();

            for (var decl : entry.getValue()) {
                var instance = decl.instance();

                if (natives.containsKey(instance)) {
                    continue;
                }

                var name = renamed(
                    mangler.identifier(pascal(NameMangler.words(instance))),
                    nested
                );
                var other = taken.putIfAbsent(name, instance);

                if (other != null) {
                    throw new CodegenError(
                        "'" +
                            instanceToString(other) +
                            "' and '" +
                            instanceToString(instance) +
                            "' both become Java type " +
                            pkg +
                            "." +
                            holder +
                            "." +
                            name
                    );
                }

                names.put(instance, name);
            }
        }
    }

    private String qualified(Instance instance) {
        return (
            pkg +
            "." +
            holders.get(moduleOf(instance)) +
            "." +
            names.get(instance)
        );
    }

    private String type(MType type, boolean boxed) {
        return switch (type) {
            case MRef r when natives.containsKey(r.instance()) -> box(
                natives.get(r.instance()),
                boxed
            );
            case MRef r -> qualified(r.instance());
            case MBytes _ -> list("java.lang.Byte");
            case MBits b -> box(bits(b.size()), boxed);
            case MArray a -> list(type(a.element(), true));
            case MSlice s -> list(type(s.element(), true));
        };
    }

    // Java names follow its conventions: types are PascalCase and fields are camelCase. Words
    // keep their case past the first letter, so `IPv4` stays as it is.
    private static String pascal(List<String> words) {
        return words
            .stream()
            .map(w -> capitalize(w))
            .collect(Collectors.joining());
    }

    private static String camel(List<String> words) {
        var name = pascal(words);
        return name.isEmpty()
            ? name
            : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String capitalize(String word) {
        return word.isEmpty()
            ? word
            : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static String box(String type, boolean boxed) {
        return boxed ? boxes.getOrDefault(type, type) : type;
    }

    private static String list(String element) {
        return "java.util.List<" + element + ">";
    }

    private static String bits(int size) {
        if (size <= 8) return "byte";
        if (size <= 16) return "short";
        if (size <= 32) return "int";
        if (size <= 64) return "long";
        return "java.util.BitSet";
    }

    private String nativeType(MAlias alias, String kind) {
        return switch (kind + " " + nativeSize(alias)) {
            case "unsigned 1", "signed 1", "char 1" -> "byte";
            case "unsigned 2", "signed 2" -> "short";
            case "unsigned 4", "signed 4" -> "int";
            case "unsigned 8", "signed 8" -> "long";
            case "float 4" -> "float";
            case "float 8" -> "double";
            case "bool 1" -> "boolean";
            default -> throw noNativeType(alias, "Java");
        };
    }

    private String emitModule(String holder, List<MDeclaration> decls) {
        var writer = new JavaCodeWriter(ident);

        writer.comment("Generated by bajtek. Do not edit.").newline();
        writer.packageDeclaration(pkg).newline();
        writer.beginClass("public final ", holder);
        writer.line("private " + holder + "() {}");

        for (var decl : decls) {
            writer.newline();

            switch (decl) {
                case MAlias a -> emitAlias(writer, a);
                case MStruct s -> emitStruct(writer, s);
                case MUnion u -> emitUnion(writer, holder, u);
            }
        }

        return writer.endClass().code();
    }

    private void emitAlias(JavaCodeWriter writer, MAlias alias) {
        writer.record(
            "public ",
            names.get(alias.instance()),
            List.of(type(alias.target(), false) + " value"),
            null
        );
    }

    private void emitStruct(JavaCodeWriter writer, MStruct struct) {
        var fields = mangler.memberNames(
            struct.instance(),
            struct
                .fields()
                .stream()
                .map(f -> camel(NameMangler.words(f.name())))
                .toList(),
            recordMethods
        );

        var components = new ArrayList<String>();

        for (int i = 0; i < fields.size(); i++) {
            components.add(
                type(struct.fields().get(i).type(), false) + " " + fields.get(i)
            );
        }

        writer.record(
            "public ",
            names.get(struct.instance()),
            components,
            null
        );
    }

    // A union is a sealed interface with one nested record per variant. Nested types cannot have
    // the name of a type enclosing them.
    private void emitUnion(JavaCodeWriter writer, String holder, MUnion union) {
        if (union.variants().isEmpty()) {
            throw new CodegenError(
                "'" + instanceToString(union.instance()) + "' has no variants"
            );
        }

        var name = names.get(union.instance());
        var nested = new HashSet<>(reserved);
        nested.add(holder);
        nested.add(name);

        var variants = mangler.memberNames(
            union.instance(),
            union
                .variants()
                .stream()
                .map(v -> pascal(NameMangler.words(v.name())))
                .toList(),
            nested
        );

        writer.beginSealedInterface("public ", name);

        for (int i = 0; i < variants.size(); i++) {
            writer.record(
                "",
                variants.get(i),
                List.of(type(union.variants().get(i).type(), false) + " value"),
                name
            );
        }

        writer.endInterface();
    }
}

// >----------------------------------------------------------- AST Tree Printer

record TreeNode(String label, List<TreeNode> children) {}

TreeNode toTreeNode(Record node) {
    var label = new StringBuilder(node.getClass().getSimpleName());
    var children = new ArrayList<TreeNode>();
    TokenPos pos = null;

    for (var component : node.getClass().getRecordComponents()) {
        Object value;
        try {
            value = component.getAccessor().invoke(node);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        var name = component.getName();

        switch (value) {
            case null -> {
            }
            case TokenPos p -> pos = p;
            case Instance i -> label.append(" ").append(instanceToString(i));
            case MArg a -> children.add(
                new TreeNode(name + ": " + monoToString(a), List.of())
            );
            case Record r -> {
                var child = toTreeNode(r);
                children.add(
                    new TreeNode(name + ": " + child.label(), child.children())
                );
            }
            case List<?> list when list.isEmpty() -> {
            }
            case List<?> list when (
                list.getFirst() instanceof Record
            ) -> children.add(
                new TreeNode(
                    name,
                    list
                        .stream()
                        .map(x -> toTreeNode((Record) x))
                        .toList()
                )
            );
            case List<?> list -> label.append(" ").append(list);
            default -> label.append(" ").append(value);
        }
    }

    if (pos != null) {
        label.append(" @").append(pos.line()).append(":").append(pos.column());
    }

    return new TreeNode(label.toString(), children);
}

void renderTree(TreeNode node, String prefix, boolean last, StringBuilder out) {
    out.append(prefix)
        .append(last ? "└─ " : "├─ ")
        .append(node.label())
        .append("\n");
    renderChildren(node, prefix + (last ? "   " : "│  "), out);
}

void renderChildren(TreeNode node, String prefix, StringBuilder out) {
    var children = node.children();

    for (int i = 0; i < children.size(); i++) {
        renderTree(children.get(i), prefix, i == children.size() - 1, out);
    }
}

String astToString(Record node) {
    var root = toTreeNode(node);
    var out = new StringBuilder(root.label()).append("\n");
    renderChildren(root, "", out);
    return out.toString();
}

// >----------------------------------------------------------------------- Main

enum CLIAction {
    AST,
    RESOLVE,
    MONO,
    CPP,
    JAVA,
}

List<Module> loadModulesFromFile(String path) throws IOException {
    var file = Paths.get(path);
    var directory = file.getParent() != null ? file.getParent() : Paths.get("");
    return new ModuleLoader(directory).loadFile(file);
}

List<Module> loadModulesFromStdin() throws IOException {
    var source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    return new ModuleLoader(Paths.get("")).loadSource(source, "<stdin>");
}

void writeFiles(Path out, Map<String, String> files) throws IOException {
    for (var file : files.entrySet()) {
        var target = out.resolve(file.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.getValue());
        System.err.println("wrote " + target);
    }
}

void main(String[] args) throws IOException {
    CLIAction mode = CLIAction.RESOLVE;
    String path = null;
    String out = null;

    for (int i = 0; i < args.length; i++) {
        var arg = args[i];

        if (arg.equals("-o")) {
            if (++i == args.length) {
                System.err.println("-o needs an output directory");
                System.exit(1);
            }

            out = args[i];
        } else if (arg.equals("--ast")) {
            mode = CLIAction.AST;
        } else if (arg.equals("--resolve")) {
            mode = CLIAction.RESOLVE;
        } else if (arg.equals("--mono")) {
            mode = CLIAction.MONO;
        } else if (arg.equals("--cpp")) {
            mode = CLIAction.CPP;
        } else if (arg.equals("--java")) {
            mode = CLIAction.JAVA;
        } else if (path == null) {
            path = arg;
        } else {
            System.err.println("unexpected argument: " + arg);
            System.exit(1);
        }
    }

    if ((mode == CLIAction.CPP || mode == CLIAction.JAVA) && out == null) {
        System.err.println(
            "code generation needs an output directory, given with -o DIR"
        );
        System.exit(1);
    }

    try {
        var modules =
            path != null ? loadModulesFromFile(path) : loadModulesFromStdin();

        switch (mode) {
            case AST -> {
                for (var module : modules) {
                    System.out.println(astToString(module));
                }
            }
            case RESOLVE -> {
                var program = new Resolver().resolve(modules);

                for (var module : program.modules()) {
                    System.out.println(astToString(module));
                }
            }
            case MONO -> {
                var program = new Resolver().resolve(modules);
                new KindChecker().check(program);
                var mono = new Monomorphizer(program).monomorphize();

                System.out.println(astToString(mono));
            }
            case CPP -> {
                var program = new Resolver().resolve(modules);
                new KindChecker().check(program);
                var mono = new Monomorphizer(program).monomorphize();

                writeFiles(
                    Paths.get(out),
                    new CppCodeGenerator("bajtek", "    ").generate(
                        mono,
                        modules.getLast().name()
                    )
                );
            }
            case JAVA -> {
                var program = new Resolver().resolve(modules);
                new KindChecker().check(program);
                var mono = new Monomorphizer(program).monomorphize();

                writeFiles(
                    Paths.get(out),
                    new JavaCodeGenerator("bajtek", "    ").generate(mono)
                );
            }
        }
    } catch (SyntaxError | CodegenError e) {
        System.err.println(e.getMessage());
        System.exit(1);
    }
}
