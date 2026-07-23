package com.jinloes.prpilot.sidecar.github;

/** A stable, token-free result from {@link GitHubAuthService#check(String)}. */
public record CheckAuthResult(String status, String username, String message) {
    static CheckAuthResult authenticated(String username) {
        return new CheckAuthResult(
                "authenticated", username, "GitHub authentication is available.");
    }

    static CheckAuthResult invalidBaseUrl() {
        return new CheckAuthResult(
                "invalid_base_url",
                null,
                "GitHub base URL must be an HTTPS origin without credentials, a path, query, or fragment.");
    }

    static CheckAuthResult notInstalled() {
        return new CheckAuthResult(
                "not_installed", null, "GitHub CLI is not installed or is not available on PATH.");
    }

    static CheckAuthResult notAuthenticated() {
        return new CheckAuthResult(
                "not_authenticated",
                null,
                "Run 'gh auth login' in a terminal for this GitHub host.");
    }

    static CheckAuthResult apiFailed() {
        return new CheckAuthResult(
                "api_failed",
                null,
                "GitHub authentication could not be verified. Try again shortly.");
    }
}
