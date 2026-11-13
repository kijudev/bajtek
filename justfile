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

# Print the typed AST of the network example
example:
    just ast examples/network.bajt

# Check that every example loads without errors
check:
    #!/usr/bin/env bash
    set -euo pipefail
    for file in examples/*.bajt; do
        java {{main}} --ast "$file" > /dev/null
        echo "ok $file"
    done
