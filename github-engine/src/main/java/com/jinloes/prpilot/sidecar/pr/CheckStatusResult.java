package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.sidecar.github.GitHubFailure;
import java.util.List;

/**
 * Token-free CI state for a PR head commit.
 *
 * <p>Carries both the rendered {@code summary} that goes into the review prompt and the structured
 * {@code checkRuns}/{@code annotations} behind it. The structure is not redundant: annotations are
 * machine-comparable against generated findings, which is what lets a later phase suppress model
 * comments that merely restate something CI already told the author.
 *
 * <p>CI context is purely additive. {@code state} is {@code none} whenever CI has produced nothing
 * usable — including on every failure path — so a caller can always treat this result as optional
 * and never blocks a review on it.
 *
 * @param state {@code complete}, {@code in_progress}, or {@code none}
 */
public record CheckStatusResult(
        String status,
        String message,
        String state,
        List<CheckRunSummary> checkRuns,
        List<CheckAnnotation> annotations,
        String summary) {

    public CheckStatusResult {
        checkRuns = checkRuns == null ? List.of() : List.copyOf(checkRuns);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    static final String STATE_COMPLETE = "complete";
    static final String STATE_IN_PROGRESS = "in_progress";
    static final String STATE_NONE = "none";

    static CheckStatusResult success(
            String state,
            List<CheckRunSummary> checkRuns,
            List<CheckAnnotation> annotations,
            String summary) {
        return new CheckStatusResult(
                "ok", "CI status loaded.", state, checkRuns, annotations, summary);
    }

    /** No CI is configured for this commit; a normal outcome, not an error. */
    static CheckStatusResult none() {
        return new CheckStatusResult(
                "ok", "No CI checks reported.", STATE_NONE, List.of(), List.of(), "");
    }

    static CheckStatusResult failure(GitHubFailure failure) {
        return new CheckStatusResult(
                failure.status(), failure.message(), STATE_NONE, List.of(), List.of(), "");
    }
}
