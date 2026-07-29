package com.jinloes.prpilot.sidecar.pr;

import java.util.regex.Pattern;

/**
 * Validation and bounding helpers shared by the prompt-context services.
 *
 * <p>Owner, repo, and commit SHA values are interpolated into request paths, so they are validated
 * against a strict allowlist rather than escaped.
 */
final class PromptContext {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,64}");

    private PromptContext() {}

    static boolean validSegment(String value) {
        // Dots are legal inside GitHub names ("my.repo"), so the character class allows them — but
        // a segment of only dots is "." or "..", which would traverse out of the intended path.
        return value != null
                && SEGMENT.matcher(value).matches()
                && !value.chars().allMatch(character -> character == '.');
    }

    static boolean validRepo(String owner, String repo) {
        return validSegment(owner) && validSegment(repo);
    }

    static boolean validSha(String sha) {
        return sha != null && SHA.matcher(sha).matches();
    }

    /** Collapses a value to a single bounded line, so one field cannot dominate the prompt. */
    static String oneLine(String value, int limit) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }

    /** Bounds multi-line text while preserving its line structure. */
    static String bounded(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.strip();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "\n…[truncated]";
    }
}
