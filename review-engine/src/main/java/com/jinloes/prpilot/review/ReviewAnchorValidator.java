package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;

/** Drops findings that do not point to a changed new-side line in the engine-owned manifest. */
final class ReviewAnchorValidator {
    private ReviewAnchorValidator() {}

    static ReviewResult validate(ReviewResult review, InspectionManifest manifest) {
        List<LineComment> valid =
                review.getLineComments().stream()
                        .filter(
                                comment ->
                                        manifest.hunkFor(comment.getFile(), comment.getLine())
                                                .isPresent())
                        .toList();
        if (valid.size() == review.getLineComments().size()) {
            return review;
        }
        String verdict;
        if (valid.stream()
                .anyMatch(
                        comment ->
                                "issue".equals(comment.getType())
                                        && ("blocker".equals(comment.getSeverity())
                                                || "major".equals(comment.getSeverity())))) {
            verdict = "REQUEST_CHANGES";
        } else if (valid.isEmpty() && !"APPROVE".equals(review.getVerdict())) {
            verdict = "COMMENT";
        } else {
            verdict = valid.isEmpty() ? "APPROVE" : "COMMENT";
        }
        return new ReviewResult(review.getSummary(), verdict, valid);
    }
}
