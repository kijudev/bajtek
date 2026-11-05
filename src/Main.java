void main() {
    IO.println("Hello, World!");
}

enum Kind { PARENL, PARENR, SYMBOL, NUMBER, STRING, EOF }
record Token(Kind kind, String text, int line, int column) {}

final class SyntaxError extends RuntimeException {
    SyntaxError(String message) {
        super(message);
    }
}
