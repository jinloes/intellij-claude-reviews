package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubHttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Retrieves a byte-bounded review diff without exposing GitHub credentials outside the sidecar. */
public final class PrDiffService {
    static final int REVIEW_LIMIT_BYTES = 250_000;
    static final int VALIDATION_LIMIT_BYTES = 1_000_000;
    private static final String TRUNCATION_MARKER = "\n\n[... diff truncated at 250 KB ...]";
    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private final GitHubAuthService.TokenResolver tokenResolver;
    private final DiffClient diffClient;
    private final Backoff backoff;

    public PrDiffService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                new HttpDiffClient(),
                new ThreadBackoff());
    }

    PrDiffService(GitHubAuthService.TokenResolver tokenResolver, DiffClient diffClient) {
        this(tokenResolver, diffClient, new ThreadBackoff());
    }

    PrDiffService(
            GitHubAuthService.TokenResolver tokenResolver, DiffClient diffClient, Backoff backoff) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.diffClient = Objects.requireNonNull(diffClient);
        this.backoff = Objects.requireNonNull(backoff);
    }

    public PrDiffResult get(Params params) {
        if (params.number() <= 0
                || !valid(params.owner())
                || !valid(params.repo())
                || !("review".equals(params.mode()) || "validation".equals(params.mode()))) {
            return PrDiffResult.failure("invalid_request", "Pull request diff request is invalid.");
        }
        GitHubApiBase base = GitHubApiBase.parse(params.githubBaseUrl());
        if (base == null)
            return PrDiffResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(base.hostnameArgument());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED)
            return PrDiffResult.failure("not_installed", "GitHub CLI is not installed.");
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED)
            return PrDiffResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        int limitBytes =
                "validation".equals(params.mode()) ? VALIDATION_LIMIT_BYTES : REVIEW_LIMIT_BYTES;
        Response response = Response.of(Status.NETWORK);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response =
                    diffClient.get(
                            base.apiBaseUrl(),
                            token.token(),
                            params.owner(),
                            params.repo(),
                            params.number(),
                            limitBytes);
            if (!retryable(response.status())
                    || attempt == MAX_ATTEMPTS
                    || Thread.currentThread().isInterrupted()) break;
            backoff.pause(attempt);
        }
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
            case NOT_FOUND ->
                    PrDiffResult.failure(
                            "api_failed",
                            "Pull request not found or inaccessible to the active gh account.");
            case API, TRANSIENT_API ->
                    PrDiffResult.failure("api_failed", "GitHub API request failed.");
        };
    }

    public record Params(
            String githubBaseUrl, String owner, String repo, int number, String mode) {}

    interface DiffClient {
        Response get(
                String api, String token, String owner, String repo, int number, int limitBytes);
    }

    interface Backoff {
        void pause(int attempt);
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
        TRANSIENT_API,
        NOT_FOUND,
        API
    }

    private static final class HttpDiffClient implements DiffClient {
        private final GitHubHttpClient httpClient = new GitHubHttpClient();

        @Override
        public Response get(
                String api, String token, String owner, String repo, int number, int limitBytes) {
            String url = api + "/repos/" + owner + "/" + repo + "/pulls/" + number;
            try {
                return httpClient.stream(
                        url,
                        token,
                        GitHubHttpClient.ACCEPT_DIFF,
                        (statusCode, body) -> {
                            if (statusCode == 401 || statusCode == 403)
                                return Response.of(Status.UNAUTHENTICATED);
                            if (statusCode == 429) return Response.of(Status.RATE_LIMITED);
                            if (statusCode == 404) return Response.of(Status.NOT_FOUND);
                            if (statusCode < 200 || statusCode >= 300) {
                                return Response.of(
                                        statusCode >= 500 ? Status.TRANSIENT_API : Status.API);
                            }
                            return read(body, limitBytes);
                        });
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

    private static final class ThreadBackoff implements Backoff {
        @Override
        public void pause(int attempt) {
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean retryable(Status status) {
        return status == Status.RATE_LIMITED
                || status == Status.NETWORK
                || status == Status.TRANSIENT_API;
    }

    private static boolean valid(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }
}
