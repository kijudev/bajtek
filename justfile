# Bajtek development commands. Comments above each recipe show up in `just --list`.

main := "src/Main.java"

# List available commands
default:
    @just --list

# Parse a schema and print it back as an S-expression (no imports are loaded)
parse file:
    java {{main}} {{file}}

# Load a schema with everything it imports and print the typed AST of each module
ast file:
    java {{main}} --ast {{file}}

# Like ast, but with every name resolved to its declaration ("module/name")
resolve file:
    java {{main}} --resolve {{file}}

# Print the typed AST of the network example
example:
    just ast examples/network.bajt

# Check that every example loads and resolves without errors
check:
    #!/usr/bin/env bash
    set -euo pipefail
    for file in examples/*.bajt; do
        java {{main}} --resolve "$file" > /dev/null
        echo "ok $file"
    done
