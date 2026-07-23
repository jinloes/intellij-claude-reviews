package com.jinloes.prpilot.sidecar.pr;

/** Token-free pending-review outcome for the read-only draft capability. */
public record DraftReviewResult(
        String status,
        String message,
        String id,
        String commitId,
        DraftReviewCodec.DecodedReview review) {
    static DraftReviewResult none() {
        return new DraftReviewResult("none", "No pending review draft.", null, null, null);
    }

    static DraftReviewResult failure(String status, String message) {
        return new DraftReviewResult(status, message, null, null, null);
    }
}
