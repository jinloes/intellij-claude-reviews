package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Merges candidate findings from a bounded follow-up without replacing the primary summary. */
final class ReviewResultMerger {
    private static final int MAX_COMMENTS = 20;

    private ReviewResultMerger() {}

    static ReviewResult merge(ReviewResult baseline, ReviewResult followUp) {
        Map<String, LineComment> unique = new LinkedHashMap<>();
        baseline.getLineComments().forEach(comment -> unique.put(key(comment), comment));
        followUp.getLineComments().forEach(comment -> unique.putIfAbsent(key(comment), comment));

        List<LineComment> comments = new ArrayList<>(unique.values());
        comments.sort(
                Comparator.comparingInt(ReviewResultMerger::priority)
                        .reversed()
                        .thenComparing(LineComment::getFile)
                        .thenComparingInt(LineComment::getLine));
        if (comments.size() > MAX_COMMENTS) {
            comments = new ArrayList<>(comments.subList(0, MAX_COMMENTS));
        }
        return new ReviewResult(baseline.getSummary(), verdict(comments), comments);
    }

    private static String key(LineComment comment) {
        return comment.getFile()
                + "|"
                + comment.getLine()
                + "|"
                + comment.getType()
                + "|"
                + comment.getBody().trim().toLowerCase(Locale.ROOT);
    }

    private static int priority(LineComment comment) {
        int severity =
                switch (comment.getSeverity()) {
                    case "blocker" -> 40;
                    case "major" -> 30;
                    case "minor" -> 20;
                    case "nit" -> 10;
                    default -> 0;
                };
        int confidence =
                switch (comment.getConfidence()) {
                    case "high" -> 3;
                    case "medium" -> 2;
                    case "low" -> 1;
                    default -> 0;
                };
        return severity + confidence;
    }

    private static String verdict(List<LineComment> comments) {
        boolean blocking =
                comments.stream()
                        .anyMatch(
                                comment ->
                                        "issue".equals(comment.getType())
                                                && ("blocker".equals(comment.getSeverity())
                                                        || "major".equals(comment.getSeverity())));
        if (blocking) {
            return "REQUEST_CHANGES";
        }
        return comments.isEmpty() ? "APPROVE" : "COMMENT";
    }
}
