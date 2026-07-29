package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.sidecar.github.GitHubFailure;

/**
 * Token-free issue context for the issues a PR declares it closes.
 *
 * <p>This is the statement of what the change is <em>supposed</em> to do, which is the only way a
 * reviewer can judge whether the diff actually does it. {@code summary} is empty both when no issue
 * is linked and on every failure path, since neither should block a review.
 *
 * @param count how many linked issues were resolved
 */
public record LinkedIssueResult(String status, String message, int count, String summary) {

    static LinkedIssueResult success(int count, String summary) {
        return new LinkedIssueResult("ok", "Linked issues loaded.", count, summary);
    }

    /** The PR body declares no closing reference; a normal outcome, not an error. */
    static LinkedIssueResult none() {
        return new LinkedIssueResult("ok", "No linked issues found.", 0, "");
    }

    static LinkedIssueResult failure(GitHubFailure failure) {
        return new LinkedIssueResult(failure.status(), failure.message(), 0, "");
    }
}
