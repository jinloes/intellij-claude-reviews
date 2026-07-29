package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.sidecar.github.GitHubFailure;

/**
 * Token-free commit-message context for a PR.
 *
 * <p>Commit messages carry author intent — why a change was made — which the diff alone never
 * shows. {@code summary} is empty on every failure path so the caller can treat it as optional.
 */
public record PrCommitsResult(String status, String message, int count, String summary) {

    static PrCommitsResult success(int count, String summary) {
        return new PrCommitsResult("ok", "Commits loaded.", count, summary);
    }

    static PrCommitsResult failure(GitHubFailure failure) {
        return new PrCommitsResult(failure.status(), failure.message(), 0, "");
    }
}
