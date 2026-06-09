package com.echomind.memory.embedding;

import java.util.Optional;

/**
 * Keeps text sent to embedding models within a conservative input budget.
 */
public class EmbeddingInputPolicy {

    public static final int DEFAULT_MAX_CHARS = 8000;
    private static final String OMISSION = "\n...\n";
    private static final double HEAD_RATIO = 0.60;

    private final int maxChars;

    public EmbeddingInputPolicy(int maxChars) {
        this.maxChars = Math.max(1, maxChars);
    }

    public static EmbeddingInputPolicy defaults() {
        return new EmbeddingInputPolicy(DEFAULT_MAX_CHARS);
    }

    public String safeQuery(String input) {
        String text = normalize(input);
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= OMISSION.length() + 2) {
            return text.substring(0, maxChars);
        }
        int budget = maxChars - OMISSION.length();
        int headChars = Math.max(1, (int) Math.floor(budget * HEAD_RATIO));
        int tailChars = Math.max(1, budget - headChars);
        if (headChars + tailChars > budget) {
            tailChars = Math.max(1, budget - headChars);
        }
        return text.substring(0, headChars)
            + OMISSION
            + text.substring(text.length() - tailChars);
    }

    private String normalize(String input) {
        return Optional.ofNullable(input).orElse("")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[\\t\\x0B\\f]+", " ")
            .replaceAll(" {2,}", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
