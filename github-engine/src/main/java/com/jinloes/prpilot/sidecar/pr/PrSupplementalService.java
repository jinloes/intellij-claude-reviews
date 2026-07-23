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
import java.util.regex.Pattern;

/** Provides token-safe GitHub reads used by notifications and review prompt context. */
public final class PrSupplementalService {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_QUERY_LENGTH = 8 * 1024;
    private static final int STARRED_LIMIT = 200;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final ApiClient client;
    private final ObjectMapper mapper;

    public PrSupplementalService() {
        this(new GitHubAuthService.ProcessTokenResolver(), new HttpApiClient(), new ObjectMapper());
    }

    PrSupplementalService(
            GitHubAuthService.TokenResolver tokenResolver, ApiClient client, ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public PrSearchResult search(SearchParams params) {
        int limit = Math.max(1, Math.min(params.limit(), 100));
        if (params.query() == null
                || params.query().isBlank()
                || params.query().length() > MAX_QUERY_LENGTH
                || params.limit() < 1
                || params.limit() > 100) {
            return PrSearchResult.failure(
                    "invalid_request", "Pull request search is invalid.", limit);
        }
        Session session = openSession(params.githubBaseUrl());
        if (session.failure() != null) {
            return PrSearchResult.failure(
                    session.failure().status(), session.failure().message(), limit);
        }
        String path =
                "/search/issues?q="
                        + URLEncoder.encode(params.query(), StandardCharsets.UTF_8)
                        + "&per_page="
                        + (limit + 1)
                        + "&sort=updated";
        ApiResponse response = client.get(session.apiBase(), session.token(), path);
        Failure failure = failure(response);
        if (failure != null)
            return PrSearchResult.failure(failure.status(), failure.message(), limit);
        try {
            JsonNode items = mapper.readTree(response.body()).path("items");
            if (!items.isArray()) {
                return PrSearchResult.failure(
                        "api_failed", "GitHub API response was invalid.", limit);
            }
            List<PullRequestSummary> prs = new ArrayList<>();
            for (JsonNode item : items) {
                if (prs.size() > limit) break;
                PullRequestSummary pr = mapSearchItem(item);
                if (pr == null) {
                    return PrSearchResult.failure(
                            "api_failed", "GitHub API response was invalid.", limit);
                }
                prs.add(pr);
            }
            boolean limited = prs.size() > limit;
            return PrSearchResult.success(limit, limited, prs.stream().limit(limit).toList());
        } catch (IOException exception) {
            return PrSearchResult.failure("api_failed", "GitHub API response was invalid.", limit);
        }
    }

    private PullRequestSummary mapSearchItem(JsonNode item) {
        String repositoryUrl = item.path("repository_url").textValue();
        String[] parts = repositoryUrl == null ? new String[0] : repositoryUrl.split("/");
        if (parts.length < 2
                || !SEGMENT.matcher(parts[parts.length - 2]).matches()
                || !SEGMENT.matcher(parts[parts.length - 1]).matches()
                || !item.path("number").canConvertToInt()
                || item.path("number").intValue() < 1
                || !item.path("title").isTextual()
                || item.path("title").textValue().isBlank()
                || !item.path("user").path("login").isTextual()
                || item.path("user").path("login").textValue().isBlank()
                || !item.path("created_at").isTextual()
                || item.path("created_at").textValue().isBlank()
                || !item.path("html_url").isTextual()
                || item.path("html_url").textValue().isBlank()) {
            return null;
        }
        return new PullRequestSummary(
                item.path("number").intValue(),
                item.path("title").textValue(),
                parts[parts.length - 2],
                parts[parts.length - 1],
                item.path("user").path("login").textValue(),
                item.path("created_at").textValue(),
                item.path("html_url").textValue(),
                item.path("draft").asBoolean(false));
    }

    public StarredReposResult starred(String githubBaseUrl) {
        Session session = openSession(githubBaseUrl);
        if (session.failure() != null) {
            return StarredReposResult.failure(
                    session.failure().status(), session.failure().message());
        }
        List<String> repositories = new ArrayList<>();
        for (int page = 1; repositories.size() < STARRED_LIMIT; page++) {
            ApiResponse response =
                    client.get(
                            session.apiBase(),
                            session.token(),
                            "/user/starred?per_page=100&sort=updated&page=" + page);
            Failure failure = failure(response);
            if (failure != null)
                return StarredReposResult.failure(failure.status(), failure.message());
            try {
                JsonNode items = mapper.readTree(response.body());
                if (!items.isArray())
                    return StarredReposResult.failure(
                            "api_failed", "GitHub API response was invalid.");
                if (items.isEmpty()) break;
                for (JsonNode item : items) {
                    String fullName = item.path("full_name").asText("");
                    if (!fullName.isBlank() && repositories.size() < STARRED_LIMIT)
                        repositories.add(fullName);
                }
                if (items.size() < 100) break;
            } catch (IOException exception) {
                return StarredReposResult.failure("api_failed", "GitHub API response was invalid.");
            }
        }
        return StarredReposResult.success(repositories.size() == STARRED_LIMIT, repositories);
    }

    public ExistingReviewsResult existingReviews(IdentityParams params) {
        if (!validIdentity(params))
            return ExistingReviewsResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        Session session = openSession(params.githubBaseUrl());
        if (session.failure() != null)
            return ExistingReviewsResult.failure(
                    session.failure().status(), session.failure().message());
        String reviewsPath =
                "/repos/"
                        + params.owner()
                        + "/"
                        + params.repo()
                        + "/pulls/"
                        + params.number()
                        + "/reviews";
        ApiResponse response = client.get(session.apiBase(), session.token(), reviewsPath);
        Failure failure = failure(response);
        if (failure != null)
            return ExistingReviewsResult.failure(failure.status(), failure.message());
        try {
            JsonNode reviews = mapper.readTree(response.body());
            if (!reviews.isArray())
                return ExistingReviewsResult.failure(
                        "api_failed", "GitHub API response was invalid.");
            List<String> lines = new ArrayList<>();
            for (JsonNode review : reviews) {
                if ("PENDING".equals(review.path("state").asText())) continue;
                String id = review.path("id").asText();
                String reviewer = review.path("user").path("login").asText("");
                String state = review.path("state").asText("COMMENTED");
                String submittedAt = review.path("submitted_at").asText("");
                String date = submittedAt.length() >= 10 ? submittedAt.substring(0, 10) : "";
                lines.add(
                        "Review by @"
                                + reviewer
                                + " ("
                                + state
                                + (date.isEmpty() ? "" : ", " + date)
                                + "):");
                String body = oneLine(review.path("body").asText(""), 300);
                if (!body.isEmpty()) lines.add("  Overall: \"" + body + "\"");
                ApiResponse comments =
                        client.get(
                                session.apiBase(),
                                session.token(),
                                reviewsPath + "/" + id + "/comments");
                if (comments.isSuccess()) appendComments(lines, comments.body());
                lines.add("");
            }
            return ExistingReviewsResult.success(String.join("\n", lines).trim());
        } catch (IOException exception) {
            return ExistingReviewsResult.failure("api_failed", "GitHub API response was invalid.");
        }
    }

    private void appendComments(List<String> lines, String body) {
        try {
            JsonNode comments = mapper.readTree(body);
            if (!comments.isArray()) return;
            for (JsonNode comment : comments) {
                String text = oneLine(comment.path("body").asText(""), 200);
                if (text.isEmpty()) continue;
                int line = comment.path("line").asInt(comment.path("original_line").asInt(0));
                String location = comment.path("path").asText("") + (line > 0 ? ":" + line : "");
                lines.add("  - " + location + ": \"" + text + "\"");
            }
        } catch (IOException ignored) {
            // Individual comment lookup failures must not discard the usable review summary.
        }
    }

    private Session openSession(String githubBaseUrl) {
        Base base = Base.from(githubBaseUrl);
        if (base == null)
            return new Session(
                    null,
                    null,
                    new Failure("invalid_base_url", "GitHub base URL must be an HTTPS origin."));
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(base.hostname());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED)
            return new Session(
                    null, null, new Failure("not_installed", "GitHub CLI is not installed."));
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED)
            return new Session(
                    null,
                    null,
                    new Failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host."));
        return new Session(base.api(), token.token(), null);
    }

    private static Failure failure(ApiResponse response) {
        if (response.isSuccess()) return null;
        if (response.statusCode() == 401 || response.statusCode() == 403)
            return new Failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        if (response.statusCode() == 429)
            return new Failure("rate_limited", "GitHub rate limit exceeded. Try again shortly.");
        if (response.statusCode() == 0)
            return new Failure("network_error", "Unable to reach GitHub. Check your connection.");
        return new Failure("api_failed", "GitHub API request failed.");
    }

    private static boolean validIdentity(IdentityParams params) {
        return params.number() > 0
                && params.owner() != null
                && SEGMENT.matcher(params.owner()).matches()
                && params.repo() != null
                && SEGMENT.matcher(params.repo()).matches();
    }

    private static String oneLine(String value, int limit) {
        String normalized = value.trim().replace('\n', ' ');
        return normalized.substring(0, Math.min(normalized.length(), limit));
    }

    public record SearchParams(String githubBaseUrl, String query, int limit) {}

    public record IdentityParams(String githubBaseUrl, String owner, String repo, int number) {}

    interface ApiClient {
        ApiResponse get(String apiBase, String token, String path);
    }

    record ApiResponse(int statusCode, String body) {
        boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    private record Session(String apiBase, String token, Failure failure) {}

    private record Failure(String status, String message) {}

    private record Base(String api, String hostname) {
        static Base from(String value) {
            try {
                URI uri =
                        URI.create(
                                value == null || value.isBlank()
                                        ? "https://github.com"
                                        : value.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null
                        || uri.getPort() != -1
                        || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                        || uri.getQuery() != null
                        || uri.getFragment() != null) return null;
                String origin = "https://" + uri.getHost().toLowerCase();
                return "https://github.com".equals(origin)
                        ? new Base("https://api.github.com", null)
                        : new Base(origin + "/api/v3", uri.getHost());
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    private static final class HttpApiClient implements ApiClient {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        @Override
        public ApiResponse get(String apiBase, String token, String path) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    HttpRequest request =
                            HttpRequest.newBuilder()
                                    .uri(URI.create(apiBase + path))
                                    .timeout(TIMEOUT)
                                    .header("Authorization", "Bearer " + token)
                                    .header("Accept", "application/vnd.github+json")
                                    .header("X-GitHub-Api-Version", "2022-11-28")
                                    .header("User-Agent", "pr-pilot-engine/0.1")
                                    .GET()
                                    .build();
                    HttpResponse<String> response =
                            client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 500 && attempt < MAX_ATTEMPTS) {
                        Thread.sleep(250L * attempt);
                        continue;
                    }
                    return new ApiResponse(response.statusCode(), response.body());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new ApiResponse(0, "");
                } catch (IOException | IllegalArgumentException exception) {
                    if (attempt == MAX_ATTEMPTS) return new ApiResponse(0, "");
                    try {
                        Thread.sleep(250L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return new ApiResponse(0, "");
                    }
                }
            }
            return new ApiResponse(0, "");
        }
    }
}
