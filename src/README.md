# Design

## Lexer

**`TokenKind` enum:**

- `PARENL`: Left parenthesis
- `PARENR`: Right parenthesis
- `SYMBOL`: Symbol
- `NUMBER`: Number
- `STRING`: String
- `COMMENT`: Comment
- `EOF`: End of file

## Expression Parser

**`Expr` sealed interface:**

- `SymbolExpr`: A symbol expression - type name
- `NumberExpr`: A number expression - integer or float
- `StringExpr`: A string expression - string literal
- `ListExpr`: A list expression - a list of `Expr`

## Abstract Syntax Tree (AST) elaboration

**`Type` sealed interface:**

- `IntrinsicType`: Represents an intrinsic type
- `S8Type`: Represents a signed 8-bit integer type
- `S16Type`: Represents a signed 16-bit integer type
- `S32Type`: Represents a signed 32-bit integer type
- `S64Type`: Represents a signed 64-bit integer type
- `U8Type`: Represents an unsigned 8-bit integer type
- `U16Type`: Represents an unsigned 16-bit integer type
- `U32Type`: Represents an unsigned 32-bit integer type
- `U64Type`: Represents an unsigned 64-bit integer type
- `F32Type`: Represents a 32-bit floating-point type
- `F64Type`: Represents a 64-bit floating-point type
- `BoolType`: Represents a boolean type
- `CharType`: Represents a character type
- `StringType`: Represents a string type
