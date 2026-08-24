package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.sidecar.github.GitHubFailure;
import java.util.List;

/**
 * Token-free commit-message context for a PR.
 *
 * <p>Commit messages carry author intent — why a change was made — which the diff alone never
 * shows. {@code summary} and {@code closingIssueNumbers} are empty on every failure path so the
 * caller can treat them as optional.
 */
public record PrCommitsResult(
        String status,
        String message,
        int count,
        String summary,
        List<Integer> closingIssueNumbers) {

    public PrCommitsResult {
        closingIssueNumbers = List.copyOf(closingIssueNumbers);
    }

    static PrCommitsResult success(int count, String summary, List<Integer> closingIssueNumbers) {
        return new PrCommitsResult("ok", "Commits loaded.", count, summary, closingIssueNumbers);
    }

    static PrCommitsResult failure(GitHubFailure failure) {
        return new PrCommitsResult(failure.status(), failure.message(), 0, "", List.of());
    }
}
