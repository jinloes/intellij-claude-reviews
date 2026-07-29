package com.jinloes.prpilot.sidecar.github;

/**
 * An opened GitHub call context: the resolved REST base URL and a token, or the {@link
 * GitHubFailure} explaining why neither could be obtained.
 *
 * <p>The token is deliberately not exposed beyond the calling service — it is passed straight to
 * {@link GitHubHttpClient} and never returned to a host or serialized into a result.
 */
public record GitHubSession(String apiBaseUrl, String token, GitHubFailure failure) {

    /**
     * Validates the origin and resolves a token for it. A blank origin defaults to github.com, per
     * {@link GitHubApiBase#parse(String)}.
     */
    public static GitHubSession open(
            GitHubAuthService.TokenResolver tokenResolver, String githubBaseUrl) {
        GitHubApiBase base = GitHubApiBase.parse(githubBaseUrl);
        if (base == null) {
            return failed(GitHubFailure.INVALID_BASE_URL);
        }
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(base.hostnameArgument());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED) {
            return failed(GitHubFailure.NOT_INSTALLED);
        }
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED) {
            return failed(GitHubFailure.NOT_AUTHENTICATED);
        }
        return new GitHubSession(base.apiBaseUrl(), token.token(), null);
    }

    private static GitHubSession failed(GitHubFailure failure) {
        return new GitHubSession(null, null, failure);
    }

    public boolean isOpen() {
        return failure == null;
    }
}
