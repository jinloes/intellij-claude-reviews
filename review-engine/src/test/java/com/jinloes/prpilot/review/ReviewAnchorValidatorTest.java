package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewAnchorValidatorTest {
    @Nested
    class Validate {
        @Test
        void retainsChangedLineFindingsAndDropsUnchangedOrUnknownAnchors() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/A.java b/src/A.java
                            --- a/src/A.java
                            +++ b/src/A.java
                            @@ -4,2 +4,2 @@
                             unchanged
                            -old
                            +new
                            """);
            ReviewResult review =
                    new ReviewResult(
                            "summary",
                            "REQUEST_CHANGES",
                            List.of(
                                    issue("src/A.java", 5),
                                    issue("src/A.java", 4),
                                    issue("../../secret", 5)));

            ReviewResult validated = ReviewAnchorValidator.validate(review, manifest);

            assertThat(validated.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getLine)
                    .isEqualTo(5);
            assertThat(validated.getVerdict()).isEqualTo("REQUEST_CHANGES");
        }

        @Test
        void downgradesTheVerdictWhenEveryBlockingFindingHasAnInvalidAnchor() {
            InspectionManifest manifest = InspectionManifest.fromDiff("");
            ReviewResult review =
                    new ReviewResult("summary", "REQUEST_CHANGES", List.of(issue("src/A.java", 5)));

            ReviewResult validated = ReviewAnchorValidator.validate(review, manifest);

            assertThat(validated.getLineComments()).isEmpty();
            assertThat(validated.getVerdict()).isEqualTo("COMMENT");
        }
    }

    private static LineComment issue(String file, int line) {
        LineComment comment = new LineComment(file, line, "issue", "Broken.");
        comment.setSeverity("major");
        comment.setCategory("correctness");
        comment.setConfidence("high");
        comment.setRationale("Evidence.");
        return comment;
    }
}
