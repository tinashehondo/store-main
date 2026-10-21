package com.example.store.dto;

/** Regular expressions for {@code @Pattern} constraints on free-text input. */
public final class TextConstraints {

    /**
     * PostgreSQL text cannot hold a NUL character: without this check it reaches the database and fails there as a data
     * integrity violation (409) instead of a 400.
     */
    public static final String NO_NUL = "[^\\x00]*";

    public static final String NO_NUL_MESSAGE = "must not contain NUL characters";

    /**
     * At least one character that {@link String#strip()} keeps, so that the term is never empty once stripped.
     * {@code \P{javaWhitespace}} is the complement of {@link Character#isWhitespace}, which {@code strip()} uses; plain
     * {@code \S} is ASCII-only and lets e.g. an em space through. DOTALL so that a newline is not "blank".
     */
    public static final String NOT_BLANK = "(?s).*\\P{javaWhitespace}.*";

    private TextConstraints() {}
}
