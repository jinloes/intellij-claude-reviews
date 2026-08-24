package com.jinloes.prpilot.sidecar.pr;

/** Token-free outcome of a draft-review save/submit/delete mutation. */
public record DraftReviewMutationResult(
        String status,
        String message,
        String reviewId,
        boolean commentsDropped,
        boolean recoveryRequired) {
    static DraftReviewMutationResult saved(String reviewId, boolean commentsDropped) {
        return new DraftReviewMutationResult(
                "ok", "Draft review saved.", reviewId, commentsDropped, false);
    }

    static DraftReviewMutationResult ok(String message) {
        return new DraftReviewMutationResult("ok", message, null, false, false);
    }

    static DraftReviewMutationResult failure(String status, String message) {
        return new DraftReviewMutationResult(status, message, null, false, false);
    }

    static DraftReviewMutationResult recoveryRequired(String status, String message) {
        return new DraftReviewMutationResult(status, message, null, false, true);
    }
}
