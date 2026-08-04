package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical save/submit/delete orchestration for GitHub pending reviews; tokens never leave the
 * engine. Includes the body-first, per-comment 422 fallback used by both hosts.
 */
public final class DraftReviewMutationService {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern REVIEW_ID = Pattern.compile("[0-9]+");
    private static final Set<String> EVENTS = Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT");

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final GitHubRestClient client;
    private final DraftReviewCodec codec;
    private final ObjectMapper mapper;

    public DraftReviewMutationService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                new HttpGitHubRestClient(),
                new ObjectMapper());
    }

    DraftReviewMutationService(
            GitHubAuthService.TokenResolver tokenResolver,
            GitHubRestClient client,
            ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
        this.codec = new DraftReviewCodec(mapper);
    }

    public DraftReviewMutationResult save(SaveParams params) {
        if (params == null
                || !valid(params.owner())
                || !valid(params.repo())
                || params.number() <= 0) {
            return DraftReviewMutationResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        }
        Session session = openSession(params.baseUrl());
        if (session == null) {
            return DraftReviewMutationResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }
        if (session.failure() != null) return session.failure();

        String basePath = pullPath(params.owner(), params.repo(), params.number());
        String reviewsUrl = basePath + "/reviews";
        String provisionalReviewId = null;
        try {
            String existingId = findPendingReviewId(session, basePath);
            if (existingId != null) {
                try {
                    requireSuccess(
                            client.delete(
                                    session.apiBase(),
                                    session.token(),
                                    basePath + "/reviews/" + existingId));
                } catch (MutationException ignored) {
                    // non-fatal: already gone or in a non-deletable state
                }
            }

            String headSha = getHeadSha(session, basePath);
            List<DraftReviewCodec.LineComment> lineComments = toLineComments(params.lineComments());
            List<DraftReviewCodec.LineComment> orphans = toLineComments(params.orphans());
            ArrayNode comments = codec.buildCommentArray(lineComments, orphans);
            String encodedBody = codec.encodeBody(params.summary(), params.verdict(), lineComments);
            String bodyWithOrphans =
                    orphans.isEmpty()
                            ? encodedBody
                            : encodedBody + "\n\n" + codec.buildOrphanSection(orphans);

            ObjectNode payload = mapper.createObjectNode();
            payload.put("commit_id", headSha);
            payload.put("body", bodyWithOrphans);
            // Omitting "event" creates a PENDING (draft) review; event:"PENDING" is invalid (422).
            payload.set("comments", comments);

            RestResponse createResponse =
                    client.post(session.apiBase(), session.token(), reviewsUrl, writeJson(payload));
            boolean commentsDropped = false;
            JsonNode created;
            if (createResponse.statusCode() == 422 && !comments.isEmpty()) {
                // One or more inline comments reference an invalid path or line. Create the
                // review body-only first (guaranteed to succeed), then add each comment
                // individually so only the bad ones are dropped.
                ObjectNode bodyOnlyPayload = payload.deepCopy();
                String bodyOnly = codec.encodeBody(params.summary(), params.verdict(), List.of());
                if (!orphans.isEmpty()) {
                    bodyOnly += "\n\n" + codec.buildOrphanSection(orphans);
                }
                bodyOnlyPayload.put("body", bodyOnly);
                bodyOnlyPayload.set("comments", mapper.createArrayNode());
                RestResponse bodyOnlyResponse =
                        client.post(
                                session.apiBase(),
                                session.token(),
                                reviewsUrl,
                                writeJson(bodyOnlyPayload));
                requireSuccess(bodyOnlyResponse);
                created = readJson(bodyOnlyResponse.body());
                String reviewId = created.path("id").asText("");
                if (reviewId.isBlank()) {
                    throw new MutationException("api_failed", "GitHub API request failed.");
                }
                provisionalReviewId = reviewId;
                String commentsUrl = reviewsUrl + "/" + reviewId + "/comments";
                List<JsonNode> dropped = new ArrayList<>();
                for (JsonNode comment : comments) {
                    RestResponse commentResponse =
                            client.post(
                                    session.apiBase(),
                                    session.token(),
                                    commentsUrl,
                                    comment.toString());
                    if (commentResponse.statusCode() == 422) {
                        dropped.add(comment);
                    } else {
                        requireSuccess(commentResponse);
                    }
                }
                commentsDropped = !dropped.isEmpty();
                List<DraftReviewCodec.LineComment> acceptedComments =
                        codec.acceptedComments(lineComments, orphans, comments, dropped);
                String updatedBody =
                        codec.encodeBody(params.summary(), params.verdict(), acceptedComments);
                if (!orphans.isEmpty()) {
                    updatedBody += "\n\n" + codec.buildOrphanSection(orphans);
                }
                if (!dropped.isEmpty()) {
                    updatedBody += "\n\n" + codec.buildDroppedSection(dropped);
                }
                ObjectNode updatePayload = mapper.createObjectNode();
                updatePayload.put("body", updatedBody);
                RestResponse updateResponse =
                        client.put(
                                session.apiBase(),
                                session.token(),
                                reviewsUrl + "/" + reviewId,
                                writeJson(updatePayload));
                requireSuccess(updateResponse);
            } else {
                requireSuccess(createResponse);
                created = readJson(createResponse.body());
            }
            String reviewId = created.path("id").asText("");
            if (reviewId.isBlank()) {
                return DraftReviewMutationResult.failure(
                        "api_failed", "GitHub API request failed.");
            }
            return DraftReviewMutationResult.saved(reviewId, commentsDropped);
        } catch (MutationException exception) {
            deleteProvisionalReview(session, reviewsUrl, provisionalReviewId);
            return DraftReviewMutationResult.failure(exception.status(), exception.getMessage());
        } catch (RuntimeException exception) {
            deleteProvisionalReview(session, reviewsUrl, provisionalReviewId);
            return DraftReviewMutationResult.failure("api_failed", "GitHub API request failed.");
        }
    }

    public DraftReviewMutationResult submit(SubmitParams params) {
        if (!valid(params.owner())
                || !valid(params.repo())
                || params.number() <= 0
                || !REVIEW_ID.matcher(params.reviewId() == null ? "" : params.reviewId()).matches()
                || !EVENTS.contains(params.event())) {
            return DraftReviewMutationResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        }
        Session session = openSession(params.baseUrl());
        if (session == null) {
            return DraftReviewMutationResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }
        if (session.failure() != null) return session.failure();

        try {
            String url =
                    pullPath(params.owner(), params.repo(), params.number())
                            + "/reviews/"
                            + params.reviewId()
                            + "/events";
            ObjectNode payload = mapper.createObjectNode();
            payload.put("event", params.event());
            payload.put("body", effectiveBody(params.event(), params.body()));
            requireSuccess(
                    client.post(session.apiBase(), session.token(), url, writeJson(payload)));
            return DraftReviewMutationResult.ok("Review submitted.");
        } catch (MutationException exception) {
            return DraftReviewMutationResult.failure(exception.status(), exception.getMessage());
        }
    }

    public DraftReviewMutationResult delete(DeleteParams params) {
        if (!valid(params.owner())
                || !valid(params.repo())
                || params.number() <= 0
                || !REVIEW_ID
                        .matcher(params.reviewId() == null ? "" : params.reviewId())
                        .matches()) {
            return DraftReviewMutationResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        }
        Session session = openSession(params.baseUrl());
        if (session == null) {
            return DraftReviewMutationResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }
        if (session.failure() != null) return session.failure();

        try {
            String url =
                    pullPath(params.owner(), params.repo(), params.number())
                            + "/reviews/"
                            + params.reviewId();
            requireSuccess(client.delete(session.apiBase(), session.token(), url));
            return DraftReviewMutationResult.ok("Draft review deleted.");
        } catch (MutationException exception) {
            return DraftReviewMutationResult.failure(exception.status(), exception.getMessage());
        }
    }

    // GitHub rejects REQUEST_CHANGES/COMMENT submissions with an empty body ("422: You need to
    // leave a comment indicating the requested changes."), so a placeholder is required when the
    // caller does not supply one.
    private static String effectiveBody(String event, String body) {
        if (body != null && !body.isBlank()) return body;
        return switch (event) {
            case "APPROVE" -> "Looks good to me!";
            case "REQUEST_CHANGES" -> "Requesting changes.";
            case "COMMENT" -> "Leaving comments.";
            default -> body == null ? "" : body;
        };
    }

    private String findPendingReviewId(Session session, String basePath) {
        RestResponse response =
                client.get(session.apiBase(), session.token(), basePath + "/reviews");
        requireSuccess(response);
        JsonNode reviews = readJson(response.body());
        if (!reviews.isArray())
            throw new MutationException("api_failed", "GitHub API request failed.");
        for (JsonNode review : reviews) {
            if ("PENDING".equals(review.path("state").asText(null))) {
                String id = review.path("id").isMissingNode() ? null : review.path("id").asText();
                return id == null || id.isBlank() ? null : id;
            }
        }
        return null;
    }

    private String getHeadSha(Session session, String basePath) {
        RestResponse response = client.get(session.apiBase(), session.token(), basePath);
        requireSuccess(response);
        JsonNode detail = readJson(response.body());
        String sha = detail.path("head").path("sha").asText("");
        if (sha.isBlank()) throw new MutationException("api_failed", "GitHub API request failed.");
        return sha;
    }

    private List<DraftReviewCodec.LineComment> toLineComments(List<CommentInput> comments) {
        List<DraftReviewCodec.LineComment> result = new ArrayList<>();
        for (CommentInput c : comments) {
            result.add(
                    new DraftReviewCodec.LineComment(
                            c.file(),
                            c.line(),
                            c.type(),
                            c.body(),
                            c.severity(),
                            c.category(),
                            c.confidence(),
                            c.rationale()));
        }
        return result;
    }

    private static String pullPath(String owner, String repo, int number) {
        return "/repos/"
                + URLEncoder.encode(owner, StandardCharsets.UTF_8)
                + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                + "/pulls/"
                + number;
    }

    private void deleteProvisionalReview(Session session, String reviewsUrl, String reviewId) {
        if (reviewId == null) return;
        try {
            client.delete(session.apiBase(), session.token(), reviewsUrl + "/" + reviewId);
        } catch (RuntimeException ignored) {
            // Best effort: the original API failure remains the user-visible result.
        }
    }

    private void requireSuccess(RestResponse response) {
        if (!isSuccess(response)) {
            throw new MutationException("api_failed", "GitHub API request failed.");
        }
    }

    private static boolean isSuccess(RestResponse response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    static boolean isRetryableHttpMethod(String method) {
        return !"POST".equals(method);
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException exception) {
            throw new MutationException("api_failed", "GitHub API request failed.");
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (IOException exception) {
            throw new MutationException("api_failed", "GitHub API request failed.");
        }
    }

    private boolean valid(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }

    private Session openSession(String baseUrl) {
        GitHubApiBase urls = GitHubApiBase.parse(baseUrl);
        if (urls == null) return null;
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(urls.hostnameArgument());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED) {
            return Session.failed(
                    DraftReviewMutationResult.failure(
                            "not_installed", "GitHub CLI is not installed."));
        }
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED) {
            return Session.failed(
                    DraftReviewMutationResult.failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host."));
        }
        return Session.ready(urls.apiBaseUrl(), token.token());
    }

    private record Session(String apiBase, String token, DraftReviewMutationResult failure) {
        static Session ready(String apiBase, String token) {
            return new Session(apiBase, token, null);
        }

        static Session failed(DraftReviewMutationResult failure) {
            return new Session(null, null, failure);
        }
    }

    /** A single inline or general comment supplied by the caller (host UI). */
    public record CommentInput(
            String file,
            int line,
            String type,
            String body,
            String severity,
            String category,
            String confidence,
            String rationale) {}

    public record SaveParams(
            String baseUrl,
            String owner,
            String repo,
            int number,
            String summary,
            String verdict,
            List<CommentInput> lineComments,
            List<CommentInput> orphans) {}

    public record SubmitParams(
            String baseUrl,
            String owner,
            String repo,
            int number,
            String reviewId,
            String event,
            String body) {}

    public record DeleteParams(
            String baseUrl, String owner, String repo, int number, String reviewId) {}

    /** Generic token-bearing GitHub REST verb client; non-infra 4xx statuses are returned as-is. */
    interface GitHubRestClient {
        RestResponse get(String apiBase, String token, String path);

        RestResponse post(String apiBase, String token, String path, String jsonBody);

        RestResponse put(String apiBase, String token, String path, String jsonBody);

        RestResponse delete(String apiBase, String token, String path);
    }

    /** Raw HTTP outcome; only genuine infra failures (auth/rate-limit/5xx/network) throw. */
    record RestResponse(int statusCode, String body) {}

    /** Thrown to signal a token-free domain failure status. */
    static final class MutationException extends RuntimeException {
        private final String status;

        MutationException(String status, String message) {
            super(message);
            this.status = status;
        }

        String status() {
            return status;
        }
    }

    /**
     * Real GitHub REST client backed by {@link HttpClient}. Never logs or returns the token.
     *
     * <p>Deliberately does <em>not</em> use the shared {@code GitHubHttpClient}: that client's
     * retry policy is only safe for idempotent reads. These verbs create and submit reviews, so
     * merging them would risk a retried POST duplicating a submitted review. Keep write transport
     * separate until mutation retries are made idempotent (e.g. via a request key).
     */
    private static final class HttpGitHubRestClient implements GitHubRestClient {
        private final HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        @Override
        public RestResponse get(String apiBase, String token, String path) {
            return send("GET", apiBase + path, token, null);
        }

        @Override
        public RestResponse post(String apiBase, String token, String path, String jsonBody) {
            return send("POST", apiBase + path, token, jsonBody);
        }

        @Override
        public RestResponse put(String apiBase, String token, String path, String jsonBody) {
            return send("PUT", apiBase + path, token, jsonBody);
        }

        @Override
        public RestResponse delete(String apiBase, String token, String path) {
            return send("DELETE", apiBase + path, token, null);
        }

        private RestResponse send(String method, String url, String token, String jsonBody) {
            URI uri = URI.create(url);
            int maxAttempts = isRetryableHttpMethod(method) ? MAX_ATTEMPTS : 1;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    HttpRequest.Builder builder =
                            HttpRequest.newBuilder(uri)
                                    .timeout(TIMEOUT)
                                    .header("Authorization", "Bearer " + token)
                                    .header("Accept", "application/vnd.github.v3+json")
                                    .header("X-GitHub-Api-Version", "2022-11-28")
                                    .header("User-Agent", "pr-pilot-sidecar/0.1");
                    HttpRequest request =
                            switch (method) {
                                case "GET" -> builder.GET().build();
                                case "DELETE" -> builder.DELETE().build();
                                case "POST" ->
                                        builder.header("Content-Type", "application/json")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                jsonBody, StandardCharsets.UTF_8))
                                                .build();
                                case "PUT" ->
                                        builder.header("Content-Type", "application/json")
                                                .PUT(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                jsonBody, StandardCharsets.UTF_8))
                                                .build();
                                default -> throw new IllegalArgumentException(method);
                            };
                    HttpResponse<String> response =
                            httpClient.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int statusCode = response.statusCode();
                    if (statusCode == 401 || statusCode == 403) {
                        throw new MutationException(
                                "not_authenticated",
                                "Run 'gh auth login' in a terminal for this GitHub host.");
                    }
                    if (statusCode == 429) {
                        if (attempt < maxAttempts) {
                            backoff(attempt);
                            continue;
                        }
                        throw new MutationException(
                                "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
                    }
                    if (statusCode >= 500) {
                        if (attempt < maxAttempts) {
                            backoff(attempt);
                            continue;
                        }
                        throw new MutationException(
                                "network_error", "Unable to reach GitHub. Check your connection.");
                    }
                    return new RestResponse(statusCode, response.body());
                } catch (IOException exception) {
                    if (attempt == maxAttempts) {
                        throw new MutationException(
                                "network_error", "Unable to reach GitHub. Check your connection.");
                    }
                    backoff(attempt);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new MutationException(
                            "network_error", "Unable to reach GitHub. Check your connection.");
                }
            }
            throw new MutationException(
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
