# Bajtek development commands. Comments above each recipe show up in `just --list`.

main := "src/Main.java"

# List available commands
default:
    @just --list

# Parse a schema and print it back as an S-expression (no imports are loaded)
parse file:
    java {{ main }} {{ file }}

# Load a schema with everything it imports and print the typed AST of each module
ast file:
    java {{ main }} --ast {{ file }}

# Like ast, but with every name resolved to its declaration ("module/name")
resolve file:
    java {{ main }} --resolve {{ file }}

# Kind check, then print every instance of every declaration the schema uses
mono file:
    java {{ main }} --mono {{ file }}

# Generate a C++ header with the model types of a schema
cpp file:
    java {{ main }} --cpp {{ file }}

# Print the typed AST of the network example
ast-example:
    just ast examples/network.bajt

# Resolve every name in the network example
resolve-example:
    just resolve examples/network.bajt

# Check that every example generates a C++ header that compiles
check:
    #!/usr/bin/env bash
    set -euo pipefail
    header=$(mktemp --suffix .hpp)
    trap 'rm -f "$header"' EXIT
    for file in examples/*.bajt; do
        java {{ main }} --cpp "$file" > "$header"
        printf '#include "%s"\n' "$header" | c++ -std=c++20 -Wall -Wextra -fsyntax-only -x c++ -
        echo "ok $file"
    done
