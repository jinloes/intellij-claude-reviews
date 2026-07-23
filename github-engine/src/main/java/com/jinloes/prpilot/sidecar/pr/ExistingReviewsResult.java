package com.jinloes.prpilot.sidecar.pr;

/** Token-free outcome containing prior submitted-review context for an AI prompt. */
public record ExistingReviewsResult(String status, String message, String summary) {
    static ExistingReviewsResult success(String summary) {
        return new ExistingReviewsResult("ok", "Existing reviews loaded.", summary);
    }

    static ExistingReviewsResult failure(String status, String message) {
        return new ExistingReviewsResult(status, message, "");
    }
}
