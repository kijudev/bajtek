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

# Generate a C++ header with the model types of a schema into a directory
cpp file out:
    java {{ main }} --cpp {{ file }} -o {{ out }}

# Generate Java sources with the model types of a schema into a directory
java file out:
    java {{ main }} --java {{ file }} -o {{ out }}

# Print the typed AST of the network example
ast-example:
    just ast examples/network.bajt

# Resolve every name in the network example
resolve-example:
    just resolve examples/network.bajt

# Check that every example generates C++ and Java code that compiles
check:
    #!/usr/bin/env bash
    set -euo pipefail
    out=$(mktemp -d)
    trap 'rm -rf "$out"' EXIT
    for file in examples/*.bajt; do
        name=$(basename "$file" .bajt)
        java {{ main }} --cpp "$file" -o "$out/$name/cpp" 2> /dev/null
        printf '#include "%s"\n' "$out/$name/cpp/$name.hpp" | c++ -std=c++20 -Wall -Wextra -fsyntax-only -x c++ -
        java {{ main }} --java "$file" -o "$out/$name/java" 2> /dev/null
        if [ -d "$out/$name/java" ]; then
            javac -Xlint:all -Werror -d "$out/$name/classes" $(find "$out/$name/java" -name '*.java')
        fi
        echo "ok $file"
    done
