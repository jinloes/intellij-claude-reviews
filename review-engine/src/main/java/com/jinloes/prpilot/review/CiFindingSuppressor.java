package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drops generated comments that merely restate a finding CI already reported on the same lines.
 *
 * <p>The author sees CI annotations in the GitHub UI already, so repeating one costs reviewer
 * attention and buys nothing. Phase 2 delivered the soft version of this — the critique prompt asks
 * the model to drop such findings — but a prompt request is not a guarantee; this is the
 * deterministic pass.
 *
 * <p><b>Deliberately conservative.</b> Wrongly dropping a real finding is far worse than leaving a
 * duplicate, because a dropped finding is invisible: the reviewer cannot tell it ever existed.
 * Suppression therefore requires <em>both</em> a location match and substantial wording overlap,
 * and an annotation with no distinctive words suppresses nothing at all.
 */
public final class CiFindingSuppressor {

    private static final Logger log = LoggerFactory.getLogger(CiFindingSuppressor.class);

    /**
     * CI annotations and review comments often disagree by a line or two — an annotation may point
     * at a declaration while the comment lands on the statement below it.
     */
    static final int LINE_TOLERANCE = 2;

    /**
     * Jaccard similarity required across the annotation and comment's distinctive words. Using the
     * union as the denominator is intentionally symmetric: a terse CI annotation cannot suppress a
     * longer review finding merely because all of its few words happen to be present.
     */
    static final double MIN_OVERLAP = 0.6;

    /** Below this length a word carries no signal ("the", "a", "null" is kept, "is" is not). */
    private static final int MIN_TOKEN_LENGTH = 4;

    /** Short phrases are too ambiguous to justify making a review finding disappear. */
    private static final int MIN_SIGNAL_TOKENS = 4;

    private CiFindingSuppressor() {}

    /**
     * Returns a review with CI-duplicate comments removed, or the input unchanged when there is
     * nothing to suppress.
     */
    public static ReviewResult suppress(ReviewResult review, List<CiAnnotation> annotations) {
        if (review == null
                || annotations == null
                || annotations.isEmpty()
                || review.getLineComments() == null
                || review.getLineComments().isEmpty()) {
            return review;
        }

        List<LineComment> kept = new ArrayList<>();
        int dropped = 0;
        for (LineComment comment : review.getLineComments()) {
            if (comment != null && isDuplicateOfCi(comment, annotations)) {
                dropped++;
                continue;
            }
            kept.add(comment);
        }
        if (dropped == 0) {
            return review;
        }

        log.info("Suppressed {} review comment(s) already reported by CI", dropped);
        ReviewResult result = new ReviewResult();
        result.setSummary(review.getSummary());
        result.setVerdict(
                kept.isEmpty() && "REQUEST_CHANGES".equals(review.getVerdict())
                        ? "COMMENT"
                        : review.getVerdict());
        result.setLineComments(kept);
        return result;
    }

    static boolean isDuplicateOfCi(LineComment comment, List<CiAnnotation> annotations) {
        Set<String> commentTokens = tokens(comment.getBody());
        for (CiAnnotation annotation : annotations) {
            if (annotation == null || !sameLocation(comment, annotation)) continue;
            Set<String> annotationTokens = tokens(annotation.getMessage());
            if (annotationTokens.size() < MIN_SIGNAL_TOKENS
                    || commentTokens.size() < MIN_SIGNAL_TOKENS) {
                continue;
            }
            long shared = annotationTokens.stream().filter(commentTokens::contains).count();
            int union = annotationTokens.size() + commentTokens.size() - (int) shared;
            if ((double) shared / union >= MIN_OVERLAP) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameLocation(LineComment comment, CiAnnotation annotation) {
        return StringUtils.equalsIgnoreCase(comment.getFile(), annotation.getFile())
                && Math.abs(comment.getLine() - annotation.getLine()) <= LINE_TOLERANCE;
    }

    /** Lowercased words of at least {@link #MIN_TOKEN_LENGTH} characters, deduplicated. */
    private static Set<String> tokens(String text) {
        if (StringUtils.isBlank(text)) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() >= MIN_TOKEN_LENGTH)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
