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
import java.util.Objects;
import java.util.regex.Pattern;

/** Loads pull-request metadata without exposing GitHub credentials outside the sidecar process. */
public final class PrDetailService {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ATTEMPTS = 3;
    private static final String DEFAULT_BASE_URL = "https://github.com";
    private static final String DEFAULT_API_URL = "https://api.github.com";
    private static final Pattern REPOSITORY_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final DetailClient detailClient;

    public PrDetailService() {
        this(new GitHubAuthService.ProcessTokenResolver(), new HttpDetailClient());
    }

    PrDetailService(GitHubAuthService.TokenResolver tokenResolver, DetailClient detailClient) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.detailClient = Objects.requireNonNull(detailClient);
    }

    public PrDetailResult get(PrDetailParams params) {
        if (!isValidIdentity(params.owner(), params.repo(), params.number())) {
            return PrDetailResult.failure("invalid_request", "Pull request identity is invalid.");
        }

        BaseUrls baseUrls;
        try {
            baseUrls = BaseUrls.from(params.githubBaseUrl());
        } catch (IllegalArgumentException exception) {
            return PrDetailResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }

        GitHubAuthService.TokenResolution tokenResolution =
                tokenResolver.resolve(baseUrls.hostnameArgument());
        if (tokenResolution.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED) {
            return PrDetailResult.failure("not_installed", "GitHub CLI is not installed.");
        }
        if (tokenResolution.status() != GitHubAuthService.TokenStatus.RESOLVED) {
            return PrDetailResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        }

        DetailResponse response =
                detailClient.get(
                        baseUrls.apiBaseUrl(),
                        tokenResolution.token(),
                        params.owner(),
                        params.repo(),
                        params.number());
        return switch (response.status()) {
            case OK -> PrDetailResult.success(response.detail());
            case NOT_AUTHENTICATED ->
                    PrDetailResult.failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host.");
            case RATE_LIMITED ->
                    PrDetailResult.failure(
                            "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
            case NETWORK_ERROR ->
                    PrDetailResult.failure(
                            "network_error", "Unable to reach GitHub. Check your connection.");
            case API_FAILED -> PrDetailResult.failure("api_failed", "GitHub API request failed.");
        };
    }

    public record PrDetailParams(String githubBaseUrl, String owner, String repo, int number) {}

    interface DetailClient {
        DetailResponse get(String apiBaseUrl, String token, String owner, String repo, int number);
    }

    record DetailResponse(DetailStatus status, PrDetail detail) {
        static DetailResponse success(PrDetail detail) {
            return new DetailResponse(DetailStatus.OK, detail);
        }

        static DetailResponse of(DetailStatus status) {
            return new DetailResponse(status, null);
        }
    }

    enum DetailStatus {
        OK,
        NOT_AUTHENTICATED,
        RATE_LIMITED,
        NETWORK_ERROR,
        API_FAILED
    }

    private static final class HttpDetailClient implements DetailClient {
        private final HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        @Override
        public DetailResponse get(
                String apiBaseUrl, String token, String owner, String repo, int number) {
            URI uri =
                    URI.create(
                            apiBaseUrl
                                    + "/repos/"
                                    + URLEncoder.encode(owner, StandardCharsets.UTF_8)
                                    + "/"
                                    + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                                    + "/pulls/"
                                    + number);
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
                        return DetailResponse.of(DetailStatus.NOT_AUTHENTICATED);
                    }
                    if (statusCode == 429) {
                        if (attempt < MAX_ATTEMPTS) {
                            backoff(attempt);
                            continue;
                        }
                        return DetailResponse.of(DetailStatus.RATE_LIMITED);
                    }
                    if (statusCode < 200 || statusCode >= 300) {
                        if (statusCode >= 500 && attempt < MAX_ATTEMPTS) {
                            backoff(attempt);
                            continue;
                        }
                        return DetailResponse.of(DetailStatus.API_FAILED);
                    }
                    return parseDetailResponse(response.body());
                } catch (IOException exception) {
                    if (attempt == MAX_ATTEMPTS) {
                        return DetailResponse.of(DetailStatus.NETWORK_ERROR);
                    }
                    backoff(attempt);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return DetailResponse.of(DetailStatus.NETWORK_ERROR);
                }
            }
            return DetailResponse.of(DetailStatus.NETWORK_ERROR);
        }

        private void backoff(int attempt) {
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static DetailResponse parseDetailResponse(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            if (root == null || !root.isObject() || !root.path("merged").isBoolean()) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            String title = nullableText(root, "title");
            String description = nullableText(root, "body");
            if (title == null || description == null) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            PrDetail.Head head = parseHead(root.path("head"));
            if (!root.path("head").isNull() && head == null) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            String baseRepoFullName = parseBaseRepo(root.path("base"));
            if (!root.path("base").isNull() && baseRepoFullName == null) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            return DetailResponse.success(
                    new PrDetail(
                            root.path("merged").booleanValue(),
                            title,
                            description,
                            head,
                            baseRepoFullName));
        } catch (IOException exception) {
            return DetailResponse.of(DetailStatus.API_FAILED);
        }
    }

    private static PrDetail.Head parseHead(JsonNode head) {
        if (head.isNull()) {
            return null;
        }
        if (!head.isObject() || !head.path("sha").isTextual() || !head.path("ref").isTextual()) {
            return null;
        }
        JsonNode repo = head.path("repo");
        if (!repo.isNull()
                && (!repo.isObject()
                        || !repo.path("full_name").isTextual()
                        || !repo.path("clone_url").isTextual())) {
            return null;
        }
        return new PrDetail.Head(
                head.path("sha").textValue(),
                head.path("ref").textValue(),
                repo.isNull() ? null : repo.path("full_name").textValue(),
                repo.isNull() ? null : repo.path("clone_url").textValue());
    }

    private static String parseBaseRepo(JsonNode base) {
        if (base.isNull()) {
            return null;
        }
        if (!base.isObject()) {
            return null;
        }
        JsonNode repo = base.path("repo");
        return repo.isNull()
                ? ""
                : repo.isObject() && repo.path("full_name").isTextual()
                        ? repo.path("full_name").textValue()
                        : null;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() ? "" : value.isTextual() ? value.textValue() : null;
    }

    private static boolean isValidIdentity(String owner, String repo, int number) {
        return number > 0
                && owner != null
                && repo != null
                && REPOSITORY_SEGMENT.matcher(owner).matches()
                && REPOSITORY_SEGMENT.matcher(repo).matches();
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
