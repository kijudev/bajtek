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
`(type (bytestring N) (array u8 N))`.

Directives, written with `#`, adjust encoding:

- `#pack`: use a packed layout
- `(#endian little|big)`: byte order, on a declaration or a single field
- `(#native kind)`: tells codegen how to map a `bytes` alias to a native type
- `#open`: on an import, brings that module's names into scope unqualified

`(import foo)` loads `foo.bajt` from the same directory. Names from an imported module are
written qualified, like `foo/bar`, unless the import is `#open`. Every module implicitly
imports `prelude` with `#open`, so the integer, float, `bool` and `char` types are always in
scope. Comments are written `(* ... *)` and can be nested.

Compiler pipeline: lexer, S-expression parser, elaboration into a typed AST, module loading.
Still to come: name resolution, kind checking, monomorphization, layout and codegen.

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
java src/Main.java FILE          # parse FILE and print it back as an S-expression
java src/Main.java --ast FILE    # load FILE and its imports, print the typed AST of each
java src/Main.java --ast < FILE  # read from stdin; imports are looked up in the current directory
```

With [just](https://github.com/casey/just):

```sh
just                 # list commands
just parse FILE      # print FILE as an S-expression
just ast FILE        # print the typed AST of FILE and its imports
just example         # print the typed AST of examples/network.bajt
just check           # check that every example loads
```
