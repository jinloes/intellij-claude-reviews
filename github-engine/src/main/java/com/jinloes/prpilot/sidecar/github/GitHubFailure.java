package com.jinloes.prpilot.sidecar.github;

/**
 * The uniform failure shape engine services report: a stable machine-readable {@code status} plus a
 * message safe to surface to a user.
 *
 * <p>Centralized here because the status strings are part of the JSON-RPC contract every host
 * branches on. Before this existed each service repeated the same status-code-to-status mapping,
 * and they had already drifted (see {@link GitHubHttpClient}'s javadoc on retry unification).
 */
public record GitHubFailure(String status, String message) {

    public static final GitHubFailure INVALID_BASE_URL =
            new GitHubFailure("invalid_base_url", "GitHub base URL must be an HTTPS origin.");

    public static final GitHubFailure INVALID_REQUEST =
            new GitHubFailure("invalid_request", "Pull request identity is invalid.");

    public static final GitHubFailure NOT_INSTALLED =
            new GitHubFailure("not_installed", "GitHub CLI is not installed.");

    public static final GitHubFailure NOT_AUTHENTICATED =
            new GitHubFailure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");

    public static final GitHubFailure RATE_LIMITED =
            new GitHubFailure("rate_limited", "GitHub rate limit exceeded. Try again shortly.");

    public static final GitHubFailure NETWORK_ERROR =
            new GitHubFailure("network_error", "Unable to reach GitHub. Check your connection.");

    public static final GitHubFailure API_FAILED =
            new GitHubFailure("api_failed", "GitHub API request failed.");

    public static final GitHubFailure INVALID_RESPONSE =
            new GitHubFailure("api_failed", "GitHub API response was invalid.");

    /** Maps a response onto its failure, or returns null when the request succeeded. */
    public static GitHubFailure of(GitHubResponse response) {
        if (response.isSuccess()) return null;
        if (response.isUnauthenticated()) return NOT_AUTHENTICATED;
        if (response.isRateLimited()) return RATE_LIMITED;
        if (response.isNetworkError()) return NETWORK_ERROR;
        return API_FAILED;
    }
}
