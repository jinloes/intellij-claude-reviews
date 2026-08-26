package com.jinloes.prpilot.sidecar.pr;

/**
 * One CI check on the PR head commit, reduced to what a reviewer-model can act on.
 *
 * @param name the check's display name, e.g. {@code build / test (17)}
 * @param status lifecycle state: {@code queued}, {@code in_progress}, or {@code completed}
 * @param conclusion outcome when {@code status} is {@code completed}, else empty — one of {@code
 *     success}, {@code failure}, {@code neutral}, {@code cancelled}, {@code timed_out}, {@code
 *     action_required}, {@code startup_failure}, {@code skipped}, or {@code stale}
 * @param output bounded {@code output.title}/{@code output.summary} text, empty when absent
 */
public record CheckRunSummary(String name, String status, String conclusion, String output) {

    /** True when this check finished in a state a reviewer should treat as a real failure. */
    public boolean isFailing() {
        return "failure".equals(conclusion)
                || "timed_out".equals(conclusion)
                || "action_required".equals(conclusion)
                || "startup_failure".equals(conclusion);
    }

    /** True when the check has not produced a conclusion yet. */
    public boolean isPending() {
        return !"completed".equals(status);
    }
}
