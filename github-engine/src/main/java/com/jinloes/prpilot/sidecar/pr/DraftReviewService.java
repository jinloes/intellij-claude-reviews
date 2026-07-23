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

/** Read-only pending-review orchestration; tokens never leave the sidecar process. */
public final class DraftReviewService {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final PendingReviewClient client;
    private final DraftReviewCodec codec;

    public DraftReviewService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                new HttpPendingReviewClient(),
                new ObjectMapper());
    }

    DraftReviewService(
            GitHubAuthService.TokenResolver tokenResolver,
            PendingReviewClient client,
            ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.codec = new DraftReviewCodec(mapper);
    }

    public DraftReviewResult load(String baseUrl, String owner, String repo, int number) {
        if (!valid(owner) || !valid(repo) || number <= 0)
            return DraftReviewResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        BaseUrls urls = BaseUrls.from(baseUrl);
        if (urls == null)
            return DraftReviewResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(urls.hostname());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED)
            return DraftReviewResult.failure("not_installed", "GitHub CLI is not installed.");
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED)
            return DraftReviewResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        Pending pending;
        try {
            pending = client.load(urls.apiBaseUrl(), token.token(), owner, repo, number);
        } catch (PendingReviewFetchException exception) {
            return DraftReviewResult.failure(exception.status(), exception.getMessage());
        }
        if (pending == null) return DraftReviewResult.none();
        DraftReviewCodec.DecodedReview review = codec.decode(pending.body(), pending.comments());
        return new DraftReviewResult(
                "ok", "Pending review draft loaded.", pending.id(), pending.commitId(), review);
    }

    private boolean valid(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }

    interface PendingReviewClient {
        Pending load(String baseUrl, String token, String owner, String repo, int number);
    }

    record Pending(
            String id, String commitId, String body, List<DraftReviewCodec.ApiComment> comments) {}

    /** Thrown by a {@link PendingReviewClient} to signal a token-free domain failure status. */
    static final class PendingReviewFetchException extends RuntimeException {
        private final String status;

        PendingReviewFetchException(String status, String message) {
            super(message);
            this.status = status;
        }

        String status() {
            return status;
        }
    }

    private record BaseUrls(String apiBaseUrl, String hostname) {
        static BaseUrls from(String value) {
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
                        ? new BaseUrls("https://api.github.com", null)
                        : new BaseUrls(origin + "/api/v3", uri.getHost());
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    /**
     * Fetches the PENDING review (if any) for a pull request over the GitHub REST API, plus its
     * inline comments. Never returns or logs the token used to authenticate.
     */
    private static final class HttpPendingReviewClient implements PendingReviewClient {
        private final HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public Pending load(
                String apiBaseUrl, String token, String owner, String repo, int number) {
            String reviewsUrl =
                    apiBaseUrl
                            + "/repos/"
                            + URLEncoder.encode(owner, StandardCharsets.UTF_8)
                            + "/"
                            + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                            + "/pulls/"
                            + number
                            + "/reviews";
            JsonNode reviews = fetchJson(reviewsUrl, token);
            if (!reviews.isArray()) {
                throw new PendingReviewFetchException("api_failed", "GitHub API request failed.");
            }
            JsonNode pending = null;
            for (JsonNode review : reviews) {
                if ("PENDING".equals(review.path("state").asText(null))) {
                    pending = review;
                    break;
                }
            }
            if (pending == null) return null;

            String id = pending.path("id").isMissingNode() ? null : pending.path("id").asText();
            if (id == null || id.isBlank()) {
                throw new PendingReviewFetchException("api_failed", "GitHub API request failed.");
            }
            String commitId =
                    pending.path("commit_id").isTextual()
                            ? pending.path("commit_id").textValue()
                            : null;
            String body = pending.path("body").isTextual() ? pending.path("body").textValue() : "";

            JsonNode comments = fetchJson(reviewsUrl + "/" + id + "/comments", token);
            if (!comments.isArray()) {
                throw new PendingReviewFetchException("api_failed", "GitHub API request failed.");
            }
            List<DraftReviewCodec.ApiComment> apiComments = new ArrayList<>();
            for (JsonNode comment : comments) {
                apiComments.add(
                        new DraftReviewCodec.ApiComment(
                                nullableText(comment, "path"),
                                nullableInt(comment, "line"),
                                nullableInt(comment, "original_line"),
                                nullableText(comment, "body")));
            }
            return new Pending(id, commitId, body, apiComments);
        }

        private static String nullableText(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isTextual() ? value.textValue() : null;
        }

        private static Integer nullableInt(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.canConvertToInt() ? value.intValue() : null;
        }

        private JsonNode fetchJson(String url, String token) {
            URI uri = URI.create(url);
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
                        throw new PendingReviewFetchException(
                                "not_authenticated",
                                "Run 'gh auth login' in a terminal for this GitHub host.");
                    }
                    if (statusCode == 429) {
                        if (attempt < MAX_ATTEMPTS) {
                            backoff(attempt);
                            continue;
                        }
                        throw new PendingReviewFetchException(
                                "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
                    }
                    if (statusCode < 200 || statusCode >= 300) {
                        if (statusCode >= 500 && attempt < MAX_ATTEMPTS) {
                            backoff(attempt);
                            continue;
                        }
                        throw new PendingReviewFetchException(
                                "api_failed", "GitHub API request failed.");
                    }
                    try {
                        return mapper.readTree(response.body());
                    } catch (IOException exception) {
                        throw new PendingReviewFetchException(
                                "api_failed", "GitHub API request failed.");
                    }
                } catch (IOException exception) {
                    if (attempt == MAX_ATTEMPTS) {
                        throw new PendingReviewFetchException(
                                "network_error", "Unable to reach GitHub. Check your connection.");
                    }
                    backoff(attempt);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new PendingReviewFetchException(
                            "network_error", "Unable to reach GitHub. Check your connection.");
                }
            }
            throw new PendingReviewFetchException(
                    "network_error", "Unable to reach GitHub. Check your connection.");
        }

        private void backoff(int attempt) {
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
