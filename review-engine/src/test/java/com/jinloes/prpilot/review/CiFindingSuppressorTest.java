package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CiFindingSuppressorTest {

    private static LineComment comment(String file, int line, String body) {
        return new LineComment(file, line, "issue", body);
    }

    private static CiAnnotation annotation(String file, int line, String message) {
        return new CiAnnotation(file, line, "warning", message);
    }

    private static ReviewResult review(LineComment... comments) {
        ReviewResult result = new ReviewResult();
        result.setSummary("summary");
        result.setVerdict("COMMENT");
        result.setLineComments(new ArrayList<>(List.of(comments)));
        return result;
    }

    private static List<String> bodies(ReviewResult result) {
        return result.getLineComments().stream().map(LineComment::getBody).toList();
    }

    @Nested
    class Suppress {

        @Test
        void dropsACommentThatRestatesACiFindingOnTheSameLine() {
            ReviewResult result =
                    CiFindingSuppressor.suppress(
                            review(comment("A.java", 10, "Possible null dereference on config")),
                            List.of(
                                    annotation(
                                            "A.java", 10, "Possible null dereference on config")));

            assertThat(result.getLineComments()).isEmpty();
        }

        @Test
        void keepsADistinctFindingOnTheSameLine() {
            ReviewResult result =
                    CiFindingSuppressor.suppress(
                            review(
                                    comment(
                                            "A.java",
                                            10,
                                            "This mutates shared state across threads")),
                            List.of(
                                    annotation(
                                            "A.java", 10, "Missing final newline at end of file")));

            assertThat(bodies(result)).containsExactly("This mutates shared state across threads");
        }

        @Test
        void keepsACommentOnADifferentFile() {
            ReviewResult result =
                    CiFindingSuppressor.suppress(
                            review(comment("B.java", 10, "Possible null dereference on config")),
                            List.of(
                                    annotation(
                                            "A.java", 10, "Possible null dereference on config")));

            assertThat(result.getLineComments()).hasSize(1);
        }

        /** CI often anchors a finding a line or two from where the reviewer would put it. */
        @Test
        void toleratesASmallLineOffsetButNotALargeOne() {
            List<CiAnnotation> ci =
                    List.of(annotation("A.java", 10, "unused import statement here"));

            assertThat(
                            CiFindingSuppressor.suppress(
                                            review(
                                                    comment(
                                                            "A.java",
                                                            12,
                                                            "unused import statement here")),
                                            ci)
                                    .getLineComments())
                    .isEmpty();
            assertThat(
                            CiFindingSuppressor.suppress(
                                            review(
                                                    comment(
                                                            "A.java",
                                                            40,
                                                            "unused import statement here")),
                                            ci)
                                    .getLineComments())
                    .hasSize(1);
        }

        /**
         * "Process completed with exit code 1" has no distinctive words after filtering, and would
         * otherwise match every comment on its line.
         */
        @Test
        void neverSuppressesFromAnAnnotationWithNoDistinctiveWords() {
            ReviewResult result =
                    CiFindingSuppressor.suppress(
                            review(
                                    comment(
                                            "A.java",
                                            10,
                                            "This leaks a file handle on the error path")),
                            List.of(annotation("A.java", 10, "on 1 a of")));

            assertThat(result.getLineComments()).hasSize(1);
        }

        @Test
        void suppressesOnlyTheDuplicateAndKeepsTheRest() {
            ReviewResult result =
                    CiFindingSuppressor.suppress(
                            review(
                                    comment("A.java", 10, "Possible null dereference on config"),
                                    comment("A.java", 50, "Race condition between these writes"),
                                    comment("B.java", 1, "Consider extracting this helper")),
                            List.of(
                                    annotation(
                                            "A.java", 10, "Possible null dereference on config")));

            assertThat(bodies(result))
                    .containsExactly(
                            "Race condition between these writes",
                            "Consider extracting this helper");
        }

        @Test
        void preservesSummaryAndVerdictWhenComentsAreDropped() {
            ReviewResult result =
                    CiFindingSuppressor.suppress(
                            review(comment("A.java", 10, "Possible null dereference on config")),
                            List.of(
                                    annotation(
                                            "A.java", 10, "Possible null dereference on config")));

            assertThat(result.getSummary()).isEqualTo("summary");
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        /** Nothing to do must return the very same instance, not a defensive copy. */
        @Test
        void returnsTheInputUntouchedWhenThereIsNothingToSuppress() {
            ReviewResult input = review(comment("A.java", 10, "unique finding"));

            assertThat(CiFindingSuppressor.suppress(input, List.of())).isSameAs(input);
            assertThat(CiFindingSuppressor.suppress(input, null)).isSameAs(input);
            assertThat(
                            CiFindingSuppressor.suppress(
                                    input,
                                    List.of(annotation("Z.java", 1, "unrelated CI failure"))))
                    .isSameAs(input);
            assertThat(CiFindingSuppressor.suppress(null, List.of())).isNull();
        }

        @Test
        void toleratesAReviewWithNoComments() {
            ReviewResult empty = new ReviewResult();
            assertThat(
                            CiFindingSuppressor.suppress(
                                    empty, List.of(annotation("A.java", 1, "boom"))))
                    .isSameAs(empty);
        }

        /** Partial overlap below the threshold must not be treated as a duplicate. */
        @Test
        void requiresSubstantialOverlapNotASingleSharedWord() {
            ReviewResult result =
                    CiFindingSuppressor.suppress(
                            review(
                                    comment(
                                            "A.java",
                                            10,
                                            "config parsing should reject empty input")),
                            List.of(
                                    annotation(
                                            "A.java",
                                            10,
                                            "config value exceeds maximum permitted length limit")));

            assertThat(result.getLineComments()).hasSize(1);
        }
    }
}
