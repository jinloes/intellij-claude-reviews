package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Retrieves a byte-bounded review diff without exposing GitHub credentials outside the sidecar. */
public final class PrDiffService {
    static final int REVIEW_LIMIT_BYTES = 250_000;
    static final int VALIDATION_LIMIT_BYTES = 1_000_000;
    private static final String TRUNCATION_MARKER = "\n\n[... diff truncated at 250 KB ...]";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private final GitHubAuthService.TokenResolver tokenResolver;
    private final DiffClient diffClient;

    public PrDiffService() {
        this(new GitHubAuthService.ProcessTokenResolver(), new HttpDiffClient());
    }

    PrDiffService(GitHubAuthService.TokenResolver tokenResolver, DiffClient diffClient) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.diffClient = Objects.requireNonNull(diffClient);
    }

    public PrDiffResult get(Params params) {
        if (params.number() <= 0
                || !valid(params.owner())
                || !valid(params.repo())
                || !("review".equals(params.mode()) || "validation".equals(params.mode()))) {
            return PrDiffResult.failure("invalid_request", "Pull request diff request is invalid.");
        }
        Base base = Base.from(params.githubBaseUrl());
        if (base == null)
            return PrDiffResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(base.hostname());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED)
            return PrDiffResult.failure("not_installed", "GitHub CLI is not installed.");
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED)
            return PrDiffResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        int limitBytes =
                "validation".equals(params.mode()) ? VALIDATION_LIMIT_BYTES : REVIEW_LIMIT_BYTES;
        Response response =
                diffClient.get(
                        base.api(),
                        token.token(),
                        params.owner(),
                        params.repo(),
                        params.number(),
                        limitBytes);
        return switch (response.status()) {
            case OK -> PrDiffResult.success(response.diff(), response.truncated(), limitBytes);
            case UNAUTHENTICATED ->
                    PrDiffResult.failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host.");
            case RATE_LIMITED ->
                    PrDiffResult.failure(
                            "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
            case NETWORK ->
                    PrDiffResult.failure(
                            "network_error", "Unable to reach GitHub. Check your connection.");
            case API -> PrDiffResult.failure("api_failed", "GitHub API request failed.");
        };
    }

    public record Params(
            String githubBaseUrl, String owner, String repo, int number, String mode) {}

    interface DiffClient {
        Response get(
                String api, String token, String owner, String repo, int number, int limitBytes);
    }

    record Response(Status status, String diff, boolean truncated) {
        static Response ok(String diff, boolean truncated) {
            return new Response(Status.OK, diff, truncated);
        }

        static Response of(Status status) {
            return new Response(status, null, false);
        }
    }

    enum Status {
        OK,
        UNAUTHENTICATED,
        RATE_LIMITED,
        NETWORK,
        API
    }

    private static final class HttpDiffClient implements DiffClient {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        public Response get(
                String api, String token, String owner, String repo, int number, int limitBytes) {
            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                api + "/repos/" + owner + "/" + repo + "/pulls/"
                                                        + number))
                                .timeout(TIMEOUT)
                                .header("Authorization", "Bearer " + token)
                                .header("Accept", "application/vnd.github.v3.diff")
                                .header("X-GitHub-Api-Version", "2022-11-28")
                                .header("User-Agent", "pr-pilot-sidecar/0.1")
                                .GET()
                                .build();
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    return Response.of(Status.UNAUTHENTICATED);
                if (response.statusCode() == 429) return Response.of(Status.RATE_LIMITED);
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    return Response.of(Status.API);
                try (InputStream input = response.body()) {
                    return read(input, limitBytes);
                }
            } catch (IOException e) {
                return Response.of(Status.NETWORK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Response.of(Status.NETWORK);
            }
        }

        private Response read(InputStream input, int limitBytes) throws IOException {
            byte[] bytes = input.readNBytes(limitBytes + 1);
            boolean truncated = bytes.length > limitBytes;
            String diff =
                    new String(
                            bytes, 0, Math.min(bytes.length, limitBytes), StandardCharsets.UTF_8);
            boolean reviewMode = limitBytes == REVIEW_LIMIT_BYTES;
            return Response.ok(
                    truncated && reviewMode ? diff + TRUNCATION_MARKER : diff, truncated);
        }
    }

    private static boolean valid(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }

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
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
