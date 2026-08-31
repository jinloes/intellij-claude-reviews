package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;

record ReviewPassResult(ReviewResult review, InspectionLedger ledger) {
    ReviewPassResult {
        if (review == null) {
            throw new IllegalArgumentException("review is required");
        }
        ledger = ledger == null ? InspectionLedger.missing() : ledger;
    }

    static ReviewPassResult withoutLedger(ReviewResult review) {
        return new ReviewPassResult(review, InspectionLedger.missing());
    }

    static ReviewPassResult mergeLedger(ReviewResult review, List<ReviewPassResult> passes) {
        return new ReviewPassResult(
                review,
                InspectionLedger.merge(passes.stream().map(ReviewPassResult::ledger).toList()));
    }
}
