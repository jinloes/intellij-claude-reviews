package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only pending-review orchestration; tokens never leave the sidecar process. */
public final class DraftReviewService {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private final GitHubAuthService.TokenResolver tokenResolver;
    private final PendingReviewClient client;
    private final DraftReviewCodec codec;

    DraftReviewService(
            GitHubAuthService.TokenResolver tokenResolver,
            PendingReviewClient client,
            ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.codec = new DraftReviewCodec(mapper);
    }

    DraftReviewResult load(String baseUrl, String owner, String repo, int number) {
        if (!valid(owner) || !valid(repo) || number <= 0)
            return DraftReviewResult.failure(
                    "invalid_request", "Pull request identity is invalid.");
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(null);
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED)
            return DraftReviewResult.failure("not_installed", "GitHub CLI is not installed.");
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED)
            return DraftReviewResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        Pending pending = client.load(baseUrl, token.token(), owner, repo, number);
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
}
