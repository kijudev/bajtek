# Bajtek

Binary serialization library with S-expression schemas, canonical encoding, and multi-language codegen.

## Design

A schema is a set of modules, one module per `.bajt` file. A module declares types:

- `type`: an alias for another type, e.g. `(type ipv4 (bytestring 4))`
- `struct`: named fields stored in order
- `union`: tagged variants; the tag is a `u8` for now

The built-in type forms are `(bytes N)`, `(bits N)`, `(array T N)` for a fixed number of
elements, and `(slice T L)` for a length-prefixed sequence whose length is stored as `L`.

Declarations can be generic over types and numbers: `(struct (pair A B) ...)` and
`(type (bytestring N) (array u8 N))`. Whether a parameter is a type or a number is inferred from
how it is used; a parameter that is never constrained is a type. Sizes in `bytes`, `bits` and
`array` can be number parameters, e.g. `(type (blob N) (bytes N))`.

Directives, written with `#`, adjust encoding:

- `#pack`: use a packed layout
- `(#endian little|big)`: byte order, on a declaration or a single field
- `(#native kind)`: tells codegen how to map a `bytes` alias to a native type
- `#open`: on an import, brings that module's names into scope unqualified

`(import foo)` loads `foo.bajt` from the same directory. Names from an imported module are
written qualified, like `foo/bar`, unless the import is `#open`. Every module implicitly
imports `prelude` with `#open`, so the integer, float, `bool` and `char` types are always in
scope. Names never shadow each other: a module's own declarations, everything from its `#open`
imports and type parameters must all have distinct names, otherwise it is an error. Comments are
written `(* ... *)` and can be nested.

Compiler pipeline: lexer, S-expression parser, elaboration into a typed AST, module loading,
name resolution, kind checking, monomorphization. Monomorphization starts from every non-generic
declaration and creates one instance per generic declaration and argument list it uses, e.g.
`(network/pair prelude/u16 network/text)`; aliases are kept as instances, not expanded. Still to
come: layout and serialization code.

Code generators write into an output directory. Instance names are mangled from their arguments,
e.g. `(pair u16 text)`, and each generator follows its language's naming conventions: C++ names
are snake_case, like `pair_u16_text`, while Java types are PascalCase, like `PairU16Text`, and Java
fields are camelCase. Names that the target language does not allow get a trailing `_`.

C++ codegen emits one header, `<module>.hpp` for the input module, with a model type per instance,
in `namespace bajtek::<module>`: aliases become `using`, structs become structs, and a union becomes
a struct holding a `std::variant` of one wrapper struct per variant, e.g. `message::leave{42}`.

Java codegen emits one file per module, e.g. `bajtek/Network.java`, holding a class with one nested
type per instance. Aliases are nominal, so each becomes a record wrapping its target, except
`#native` ones, which become primitives (unsigned values use the signed type of the same size).
Structs become records, and a union becomes a sealed interface with one nested record per variant,
e.g. `new Message.Leave(42L)`. `bytes`, `array` and `slice` all become `java.util.List`.

## Example

```lisp
(* examples/geometry.bajt *)
(module geometry (
    (struct point2d (
        (x f64)
        (y f64)
    ))

    (struct point3d (
        (x f64)
        (y f64)
        (z f64)
    ))

    (union point (
        (flat point2d)
        (spatial point3d)
    ))
))
```

```lisp
(* A module using generics, imports and directives *)
(module shapes (
    (import geometry)

    (type (vec T) (slice T u32))

    (union (option T) (
        (none (array u8 0))
        (some T)
    ))

    (struct polygon (
        (points (vec geometry/point2d))
        (label (option (vec char)))
        (color (array u8 3))
    ) #pack (#endian little))
))
```

See [`examples/`](examples) for the prelude and a larger network protocol schema.

## Usage

Requires JDK 25. `nix develop` provides a dev shell with it.

```sh
java src/Main.java FILE                # parse FILE and print it back as an S-expression
java src/Main.java --ast FILE          # load FILE and its imports, print the typed AST of each
java src/Main.java --resolve FILE      # like --ast, with every name resolved to "module/name"
java src/Main.java --mono FILE         # kind check and print every instance the schema uses
java src/Main.java --cpp FILE -o DIR   # generate a C++ header with the model types into DIR
java src/Main.java --java FILE -o DIR  # generate Java sources with the model types into DIR
java src/Main.java --ast < FILE        # read from stdin; imports are looked up in the current directory
```

With [just](https://github.com/casey/just):

```sh
just                # list commands
just parse FILE     # print FILE as an S-expression
just ast FILE       # print the typed AST of FILE and its imports
just resolve FILE   # print the resolved AST of FILE and its imports
just mono FILE      # print the monomorphized instances of FILE and its imports
just cpp FILE DIR   # generate a C++ header for FILE and its imports into DIR
just java FILE DIR  # generate Java sources for FILE and its imports into DIR
just example        # print the typed AST of examples/network.bajt
just check          # check that every example generates C++ and Java code that compiles
```
