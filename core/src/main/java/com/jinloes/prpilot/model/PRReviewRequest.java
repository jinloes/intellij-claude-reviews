package com.jinloes.prpilot.model;

/**
 * Bundles the data needed to generate a PR review: the pull-request metadata, the unified diff, any
 * previously-verified patterns that should be excluded from re-flagging, an optional prior review
 * result to use as refinement context on re-generation, and a formatted summary of reviews already
 * submitted by other reviewers so Claude avoids repeating their findings.
 */
public final class PRReviewRequest {

    private final PullRequest pr;
    private final String diff;
    private final String knownPatterns;
    private final String priorReview;
    private final String existingReviews;
    private final String repoGuidelines;
    private final String focusAreas;
    private final String customInstructions;

    /** Convenience constructor with no prior review or existing reviews. */
    public PRReviewRequest(PullRequest pr, String diff, String knownPatterns) {
        this(pr, diff, knownPatterns, null, null);
    }

    /** Convenience constructor with no existing reviews. */
    public PRReviewRequest(PullRequest pr, String diff, String knownPatterns, String priorReview) {
        this(pr, diff, knownPatterns, priorReview, null);
    }

    public PRReviewRequest(
            PullRequest pr,
            String diff,
            String knownPatterns,
            String priorReview,
            String existingReviews) {
        this(pr, diff, knownPatterns, priorReview, existingReviews, null, null, null);
    }

    public PRReviewRequest(
            PullRequest pr,
            String diff,
            String knownPatterns,
            String priorReview,
            String existingReviews,
            String repoGuidelines,
            String focusAreas,
            String customInstructions) {
        this.pr = pr;
        this.diff = diff;
        this.knownPatterns = knownPatterns;
        this.priorReview = priorReview;
        this.existingReviews = existingReviews;
        this.repoGuidelines = repoGuidelines;
        this.focusAreas = focusAreas;
        this.customInstructions = customInstructions;
    }

    public PullRequest getPr() {
        return pr;
    }

    public String getDiff() {
        return diff;
    }

    public String getKnownPatterns() {
        return knownPatterns;
    }

    public String getPriorReview() {
        return priorReview;
    }

    public String getExistingReviews() {
        return existingReviews;
    }

    public String getRepoGuidelines() {
        return repoGuidelines;
    }

    public String getFocusAreas() {
        return focusAreas;
    }

    public String getCustomInstructions() {
        return customInstructions;
    }
}
