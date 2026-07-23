package com.jinloes.prpilot.sidecar.review;

public record ReviewParseResult(boolean valid, ReviewResult review, ReviewValidationError error) {
    public static ReviewParseResult valid(ReviewResult review) {
        return new ReviewParseResult(true, review, null);
    }

    public static ReviewParseResult invalid(String message) {
        return new ReviewParseResult(
                false, null, new ReviewValidationError("invalid_review_json", message));
    }

    public record ReviewValidationError(String code, String message) {}
}
