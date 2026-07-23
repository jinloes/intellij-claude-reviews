package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DraftReviewServiceTest {
    @Test
    void returnsNoneWhenNoPendingReviewExists() {
        DraftReviewService service =
                new DraftReviewService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret"),
                        (base, token, owner, repo, number) -> null,
                        new ObjectMapper());
        assertThat(service.load("https://github.com", "acme", "repo", 1).status())
                .isEqualTo("none");
    }

    @Test
    void usesEnterpriseApiBaseAndHostname() {
        AtomicReference<String> hostname = new AtomicReference<>();
        AtomicReference<String> apiBase = new AtomicReference<>();
        DraftReviewService service =
                new DraftReviewService(
                        value -> {
                            hostname.set(value);
                            return GitHubAuthService.TokenResolution.resolved("token");
                        },
                        (base, token, owner, repo, number) -> {
                            apiBase.set(base);
                            return null;
                        },
                        new ObjectMapper());

        assertThat(service.load("https://github.example.test", "acme", "repo", 1).status())
                .isEqualTo("none");
        assertThat(hostname.get()).isEqualTo("github.example.test");
        assertThat(apiBase.get()).isEqualTo("https://github.example.test/api/v3");
    }

    @Test
    void returnsInvalidBaseUrlForMalformedBaseUrl() {
        DraftReviewService service =
                new DraftReviewService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret"),
                        (base, token, owner, repo, number) -> null,
                        new ObjectMapper());
        assertThat(service.load("not-a-url", "acme", "repo", 1).status())
                .isEqualTo("invalid_base_url");
    }

    @Test
    void returnsInvalidRequestForBlankOwner() {
        DraftReviewService service =
                new DraftReviewService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret"),
                        (base, token, owner, repo, number) -> null,
                        new ObjectMapper());
        assertThat(service.load("https://github.com", "", "repo", 1).status())
                .isEqualTo("invalid_request");
    }

    @Test
    void returnsNotInstalledWhenGhCliMissing() {
        DraftReviewService service =
                new DraftReviewService(
                        ignored -> GitHubAuthService.TokenResolution.notInstalled(),
                        (base, token, owner, repo, number) -> null,
                        new ObjectMapper());
        assertThat(service.load("https://github.com", "acme", "repo", 1).status())
                .isEqualTo("not_installed");
    }

    @Test
    void returnsNotAuthenticatedWhenTokenUnresolved() {
        DraftReviewService service =
                new DraftReviewService(
                        ignored -> GitHubAuthService.TokenResolution.notAuthenticated(),
                        (base, token, owner, repo, number) -> null,
                        new ObjectMapper());
        assertThat(service.load("https://github.com", "acme", "repo", 1).status())
                .isEqualTo("not_authenticated");
    }

    @Test
    void mapsClientFetchFailureToDomainStatusWithoutLeakingToken() {
        DraftReviewService service =
                new DraftReviewService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("super-secret"),
                        (base, token, owner, repo, number) -> {
                            throw new DraftReviewService.PendingReviewFetchException(
                                    "rate_limited",
                                    "GitHub rate limit exceeded. Try again shortly.");
                        },
                        new ObjectMapper());
        DraftReviewResult result = service.load("https://github.com", "acme", "repo", 1);
        assertThat(result.status()).isEqualTo("rate_limited");
        assertThat(result.toString()).doesNotContain("super-secret");
    }

    @Test
    void decodesPendingReviewWithoutLeakingToken() {
        DraftReviewService service =
                new DraftReviewService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret"),
                        (base, token, owner, repo, number) ->
                                new DraftReviewService.Pending(
                                        "7", "sha", "<!-- claude-verdict: APPROVE -->", List.of()),
                        new ObjectMapper());
        DraftReviewResult result = service.load("https://github.com", "acme", "repo", 1);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.review().verdict()).isEqualTo("APPROVE");
        assertThat(result.toString()).doesNotContain("secret");
    }
}
