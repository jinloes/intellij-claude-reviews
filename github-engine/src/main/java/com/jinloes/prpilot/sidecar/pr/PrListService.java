package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Lists pull requests without exposing the GitHub token outside the sidecar process. */
public final class PrListService {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int RESULT_LIMIT = 50;
    private static final int REQUEST_LIMIT = RESULT_LIMIT + 1;
    private static final int MAX_ATTEMPTS = 3;
    private static final String DEFAULT_BASE_URL = "https://github.com";
    private static final String DEFAULT_API_URL = "https://api.github.com";

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final SearchClient searchClient;
    private final PrSearchQueryService queryService;

    public PrListService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                new HttpSearchClient(),
                new PrSearchQueryService());
    }

    PrListService(
            GitHubAuthService.TokenResolver tokenResolver,
            SearchClient searchClient,
            PrSearchQueryService queryService) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.searchClient = Objects.requireNonNull(searchClient);
        this.queryService = Objects.requireNonNull(queryService);
    }

    public PrListResult list(PrListParams params) {
        BaseUrls baseUrls;
        try {
            baseUrls = BaseUrls.from(params.githubBaseUrl());
        } catch (IllegalArgumentException exception) {
            return PrListResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }

        GitHubAuthService.TokenResolution tokenResolution =
                tokenResolver.resolve(baseUrls.hostnameArgument());
        if (tokenResolution.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED) {
            return PrListResult.failure("not_installed", "GitHub CLI is not installed.");
        }
        if (tokenResolution.status() != GitHubAuthService.TokenStatus.RESOLVED) {
            return PrListResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        }

        String query =
                queryService
                        .build(
                                new PrSearchQueryService.QueryParams(
                                        params.state(), params.searchScope(), params.currentRepo()))
                        .query();
        SearchResponse response =
                searchClient.search(baseUrls.apiBaseUrl(), tokenResolution.token(), query);
        return switch (response.status()) {
            case OK -> PrListResult.success(query, response.limited(), response.prs());
            case NOT_AUTHENTICATED ->
                    PrListResult.failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host.");
            case RATE_LIMITED ->
                    PrListResult.failure(
                            "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
            case NETWORK_ERROR ->
                    PrListResult.failure(
                            "network_error", "Unable to reach GitHub. Check your connection.");
            case API_FAILED -> PrListResult.failure("api_failed", "GitHub API request failed.");
        };
    }

    public record PrListParams(
            String githubBaseUrl, String state, String searchScope, String currentRepo) {}

    interface SearchClient {
        SearchResponse search(String apiBaseUrl, String token, String query);
    }

    record SearchResponse(SearchStatus status, boolean limited, List<PullRequestSummary> prs) {
        static SearchResponse success(boolean limited, List<PullRequestSummary> prs) {
            return new SearchResponse(SearchStatus.OK, limited, List.copyOf(prs));
        }

        static SearchResponse of(SearchStatus status) {
            return new SearchResponse(status, false, List.of());
        }
    }

    enum SearchStatus {
        OK,
        NOT_AUTHENTICATED,
        RATE_LIMITED,
        NETWORK_ERROR,
        API_FAILED
    }

    private static final class HttpSearchClient implements SearchClient {
        private final HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        @Override
        public SearchResponse search(String apiBaseUrl, String token, String query) {
            URI uri =
                    URI.create(
                            apiBaseUrl
                                    + "/search/issues?q="
                                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                                    + "&per_page="
                                    + REQUEST_LIMIT
                                    + "&sort=updated");
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    HttpRequest request =
                            HttpRequest.newBuilder()
                                    .uri(uri)
                                    .timeout(TIMEOUT)
                                    .header("Authorization", "Bearer " + token)
                                    .header("Accept", "application/vnd.github.v3+json")
                                    .header("X-GitHub-Api-Version", "2022-11-28")
                                    .header("User-Agent", "pr-pilot-sidecar/0.1")
                                    .GET()
                                    .build();
                    HttpResponse<String> response =
                            httpClient.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int statusCode = response.statusCode();
                    if (statusCode == 401 || statusCode == 403) {
                        return SearchResponse.of(SearchStatus.NOT_AUTHENTICATED);
                    }
                    if (statusCode == 429) {
                        if (attempt < MAX_ATTEMPTS) {
                            backoff(attempt);
                            continue;
                        }
                        return SearchResponse.of(SearchStatus.RATE_LIMITED);
                    }
                    if (statusCode < 200 || statusCode >= 300) {
                        if (statusCode >= 500 && attempt < MAX_ATTEMPTS) {
                            backoff(attempt);
                            continue;
                        }
                        return SearchResponse.of(SearchStatus.API_FAILED);
                    }
                    return parseSearchResponse(response.body());
                } catch (IOException exception) {
                    if (attempt == MAX_ATTEMPTS) {
                        return SearchResponse.of(SearchStatus.NETWORK_ERROR);
                    }
                    backoff(attempt);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return SearchResponse.of(SearchStatus.NETWORK_ERROR);
                }
            }
            return SearchResponse.of(SearchStatus.NETWORK_ERROR);
        }

        private void backoff(int attempt) {
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static SearchResponse parseSearchResponse(String body) {
        try {
            JsonNode items = new ObjectMapper().readTree(body).path("items");
            if (!items.isArray()) {
                return SearchResponse.of(SearchStatus.API_FAILED);
            }
            List<PullRequestSummary> prs = new ArrayList<>();
            for (JsonNode item : items) {
                if (prs.size() == REQUEST_LIMIT) {
                    break;
                }
                PullRequestSummary pr = map(item);
                if (pr == null) {
                    return SearchResponse.of(SearchStatus.API_FAILED);
                }
                prs.add(pr);
            }
            boolean limited = prs.size() > RESULT_LIMIT;
            return SearchResponse.success(limited, limited ? prs.subList(0, RESULT_LIMIT) : prs);
        } catch (IOException exception) {
            return SearchResponse.of(SearchStatus.API_FAILED);
        }
    }

    private static PullRequestSummary map(JsonNode item) {
        String repositoryUrl = text(item, "repository_url");
        if (repositoryUrl == null) {
            return null;
        }
        String[] repositoryParts = repositoryUrl.split("/");
        if (repositoryParts.length < 2) {
            return null;
        }
        JsonNode user = item.path("user");
        if (!item.path("number").canConvertToInt()
                || !item.path("title").isTextual()
                || !item.path("html_url").isTextual()
                || !item.path("created_at").isTextual()
                || !user.path("login").isTextual()) {
            return null;
        }
        return new PullRequestSummary(
                item.path("number").intValue(),
                text(item, "title"),
                repositoryParts[repositoryParts.length - 2],
                repositoryParts[repositoryParts.length - 1],
                user.path("login").textValue(),
                text(item, "created_at"),
                text(item, "html_url"),
                item.path("draft").asBoolean(false));
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).textValue();
    }

    private record BaseUrls(String apiBaseUrl, String hostnameArgument) {
        private static BaseUrls from(String value) {
            String candidate = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
            URI uri;
            try {
                uri = URI.create(candidate);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid GitHub base URL", exception);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || (!"/".equals(uri.getPath()) && !uri.getPath().isEmpty())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("Invalid GitHub base URL");
            }
            String normalized = "https://" + uri.getHost().toLowerCase();
            return DEFAULT_BASE_URL.equals(normalized)
                    ? new BaseUrls(DEFAULT_API_URL, null)
                    : new BaseUrls(normalized + "/api/v3", uri.getHost());
        }
    }
}
