package com.jinloes.prpilot.sidecar.github;

import java.net.URI;

/**
 * A validated GitHub origin, resolved to its REST API base URL and the {@code gh --hostname}
 * argument for that host.
 *
 * <p>Accepts only a bare HTTPS origin — no credentials, port, path, query, or fragment — because
 * the value reaches both a subprocess argument ({@code gh auth token --hostname}) and a request
 * URI. Rejecting anything richer keeps both uses unambiguous.
 *
 * <p>{@code github.com} maps to {@code api.github.com}; any other host is treated as GitHub
 * Enterprise and maps to {@code <origin>/api/v3}. {@link #hostnameArgument()} is null for
 * github.com, since {@code gh} defaults to it and passing {@code --hostname} is unnecessary.
 */
public record GitHubApiBase(String apiBaseUrl, String hostnameArgument) {
    private static final String DEFAULT_BASE_URL = "https://github.com";
    private static final String DEFAULT_API_URL = "https://api.github.com";

    /**
     * Parses a GitHub origin, returning null when it is not a bare HTTPS origin. A null or blank
     * value defaults to github.com.
     */
    public static GitHubApiBase parse(String value) {
        URI uri;
        try {
            uri = URI.create(value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
        String path = uri.getPath();
        boolean pathIsRootOrEmpty = path == null || path.isEmpty() || "/".equals(path);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || !pathIsRootOrEmpty
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            return null;
        }
        String origin = "https://" + uri.getHost().toLowerCase();
        return DEFAULT_BASE_URL.equals(origin)
                ? new GitHubApiBase(DEFAULT_API_URL, null)
                : new GitHubApiBase(origin + "/api/v3", uri.getHost());
    }

    /**
     * Parses a GitHub origin, throwing {@link IllegalArgumentException} when it is not a bare HTTPS
     * origin. Equivalent to {@link #parse(String)} for callers that signal failure by exception.
     */
    public static GitHubApiBase require(String value) {
        GitHubApiBase base = parse(value);
        if (base == null) {
            throw new IllegalArgumentException("Invalid GitHub base URL");
        }
        return base;
    }
}
