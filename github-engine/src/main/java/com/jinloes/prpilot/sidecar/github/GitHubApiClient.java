package com.jinloes.prpilot.sidecar.github;

/**
 * Path-relative authenticated GET seam.
 *
 * <p>Services depend on this rather than on {@link GitHubHttpClient} directly so their tests can
 * script responses without opening sockets, while production wiring still funnels every call
 * through the one shared transport via {@link #http()}.
 */
@FunctionalInterface
public interface GitHubApiClient {

    /** Issues an authenticated JSON GET against {@code apiBaseUrl + path}. */
    GitHubResponse get(String apiBaseUrl, String token, String path);

    /** The production transport, backed by the shared {@link GitHubHttpClient}. */
    static GitHubApiClient http() {
        GitHubHttpClient httpClient = new GitHubHttpClient();
        return (apiBaseUrl, token, path) -> httpClient.get(apiBaseUrl + path, token);
    }
}
