package com.wywf.core;

public enum Modifier {
    DEFAULT,
    NEAR,
    IN,
    SOME,
    FAR,
    UNDER,
    NEVER,
    ONLY,
    BETWEEN,
    AT,
    EXACTLY,
    AT_LEAST,
    AT_MOST;

    public boolean counted() { return this == EXACTLY || this == AT_LEAST || this == AT_MOST; }
    public boolean accepts(int count, int wanted) {
        return switch (this) {
            case EXACTLY -> count == wanted;
            case AT_LEAST -> count >= wanted;
            case AT_MOST -> count <= wanted;
            default -> throw new IllegalStateException("Not a count modifier");
        };
    }
}
