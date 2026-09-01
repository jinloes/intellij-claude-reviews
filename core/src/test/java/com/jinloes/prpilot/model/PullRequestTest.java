package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PullRequestTest {

    @Nested
    class ReviewStatusMapping {

        @Test
        void defaultsLegacyConstructorsToUnavailable() {
            PullRequest pullRequest =
                    new PullRequest("Title", "url", "owner", "repo", 1, "", "author", "date");

            assertThat(pullRequest.getReviewStatus()).isEqualTo(ReviewStatus.UNAVAILABLE);
        }

        @Test
        void copiesAllFieldsWhenChangingReviewStatus() {
            PullRequest original =
                    new PullRequest(
                            "Title",
                            "url",
                            "owner",
                            "repo",
                            1,
                            "body",
                            "author",
                            "date",
                            true,
                            ReviewStatus.UNAVAILABLE);

            PullRequest updated = original.withReviewStatus(ReviewStatus.REVIEWED);

            assertThat(updated.getTitle()).isEqualTo(original.getTitle());
            assertThat(updated.getBody()).isEqualTo(original.getBody());
            assertThat(updated.isDraft()).isTrue();
            assertThat(updated.getReviewStatus()).isEqualTo(ReviewStatus.REVIEWED);
        }
    }
}
