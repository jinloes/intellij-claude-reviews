package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.model.ReviewStatus;

/** Token-free pull-request fields required by the PR list UI. */
public record PullRequestSummary(
        int number,
        String title,
        String owner,
        String repo,
        String author,
        String createdAt,
        String htmlUrl,
        boolean isDraft,
        ReviewStatus reviewStatus) {

    public PullRequestSummary(
            int number,
            String title,
            String owner,
            String repo,
            String author,
            String createdAt,
            String htmlUrl,
            boolean isDraft) {
        this(
                number,
                title,
                owner,
                repo,
                author,
                createdAt,
                htmlUrl,
                isDraft,
                ReviewStatus.UNAVAILABLE);
    }

    public PullRequestSummary withReviewStatus(ReviewStatus value) {
        return new PullRequestSummary(
                number, title, owner, repo, author, createdAt, htmlUrl, isDraft, value);
    }
}
