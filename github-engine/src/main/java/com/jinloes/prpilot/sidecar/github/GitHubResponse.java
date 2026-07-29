package com.jinloes.prpilot.sidecar.github;

/**
 * A GitHub REST response reduced to the two things callers act on: the HTTP status and the raw
 * body.
 *
 * <p>Transport failures are represented as {@link #NETWORK_ERROR} rather than an exception so that
 * every caller maps one uniform shape onto its own domain result.
 */
public record GitHubResponse(int statusCode, String body) {
    /** Synthetic status meaning the request never produced an HTTP response. */
    public static final int NETWORK_ERROR = 0;

    public static GitHubResponse networkError() {
        return new GitHubResponse(NETWORK_ERROR, "");
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean isNetworkError() {
        return statusCode == NETWORK_ERROR;
    }

    /** True when the token is missing, invalid, or lacks scope for the resource. */
    public boolean isUnauthenticated() {
        return statusCode == 401 || statusCode == 403;
    }

    public boolean isRateLimited() {
        return statusCode == 429;
    }
}
