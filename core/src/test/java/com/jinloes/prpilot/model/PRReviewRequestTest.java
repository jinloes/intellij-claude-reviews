package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRReviewRequestTest {

    private static PullRequest pr() {
        return new PullRequest("T", "url", "o", "r", 1, "body", "author", "2026-01-01", false);
    }

    @Nested
    class Builder {

        /**
         * Every setter is checked because a builder that silently drops a field produces a review
         * missing one prompt section with no error anywhere — the failure is invisible at runtime.
         */
        @Test
        void roundTripsEveryContextField() {
            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), "diff")
                            .priorReview("prior")
                            .existingReviews("existing")
                            .repoGuidelines("guidelines")
                            .focusAreas("focus")
                            .customInstructions("custom")
                            .ciStatus("ci")
                            .commits("commits")
                            .linkedIssue("issue")
                            .repoProfile("profile")
                            .build();

            assertThat(request.getDiff()).isEqualTo("diff");
            assertThat(request.getPriorReview()).isEqualTo("prior");
            assertThat(request.getExistingReviews()).isEqualTo("existing");
            assertThat(request.getRepoGuidelines()).isEqualTo("guidelines");
            assertThat(request.getFocusAreas()).isEqualTo("focus");
            assertThat(request.getCustomInstructions()).isEqualTo("custom");
            assertThat(request.getCiStatus()).isEqualTo("ci");
            assertThat(request.getCommits()).isEqualTo("commits");
            assertThat(request.getLinkedIssue()).isEqualTo("issue");
            assertThat(request.getRepoProfile()).isEqualTo("profile");
        }

        @Test
        void defaultsCiAnnotationsToEmptyRatherThanNull() {
            assertThat(PRReviewRequest.builder(pr(), "diff").build().getCiAnnotations()).isEmpty();
            assertThat(
                            PRReviewRequest.builder(pr(), "diff")
                                    .ciAnnotations(null)
                                    .build()
                                    .getCiAnnotations())
                    .isEmpty();
        }

        @Test
        void copiesCiAnnotationsSoLaterCallerMutationCannotChangeTheRequest() {
            List<CiAnnotation> source =
                    new ArrayList<>(List.of(new CiAnnotation("A.java", 1, "warning", "m")));

            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), "diff").ciAnnotations(source).build();
            source.clear();

            assertThat(request.getCiAnnotations()).hasSize(1);
        }
    }
}
