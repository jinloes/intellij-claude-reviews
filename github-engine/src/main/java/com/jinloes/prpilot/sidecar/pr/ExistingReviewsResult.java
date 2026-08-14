package com.jinloes.prpilot.sidecar.pr;

/** Token-free outcome containing prior submitted-review context for an AI prompt. */
public record ExistingReviewsResult(String status, String message, String summary) {
    static ExistingReviewsResult success(String summary, boolean partial) {
        String message =
                partial
                        ? "Existing reviews loaded without inline comments."
                        : "Existing reviews loaded.";
        return new ExistingReviewsResult("ok", message, summary);
    }

    static ExistingReviewsResult failure(String status, String message) {
        return new ExistingReviewsResult(status, message, "");
    }
}
