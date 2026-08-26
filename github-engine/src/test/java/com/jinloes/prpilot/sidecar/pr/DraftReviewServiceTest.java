package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void httpClientFindsPendingReviewAndCommentsOnSecondPages() {
        ObjectMapper mapper = new ObjectMapper();
        var firstReviews = mapper.createArrayNode();
        for (int index = 0; index < DraftReviewService.PAGE_SIZE; index++) {
            firstReviews.addObject().put("id", index + 1).put("state", "COMMENTED");
        }
        var secondReviews = mapper.createArrayNode();
        secondReviews
                .addObject()
                .put("id", 777)
                .put("state", "PENDING")
                .put("commit_id", "head-sha")
                .put("body", "<!-- claude-verdict: COMMENT -->");
        var firstComments = mapper.createArrayNode();
        for (int index = 0; index < DraftReviewService.PAGE_SIZE; index++) {
            firstComments
                    .addObject()
                    .put("path", "A.java")
                    .put("line", index + 1)
                    .put("body", "[NOTE] page one " + index);
        }
        var secondComments = mapper.createArrayNode();
        secondComments
                .addObject()
                .put("path", "B.java")
                .put("line", 101)
                .put("body", "[ISSUE] page two");
        List<String> urls = new ArrayList<>();
        DraftReviewService.HttpPendingReviewClient client =
                new DraftReviewService.HttpPendingReviewClient(
                        (url, token) -> {
                            urls.add(url);
                            if (url.contains("/comments")) {
                                return new GitHubResponse(
                                        200,
                                        url.endsWith("page=1")
                                                ? firstComments.toString()
                                                : secondComments.toString());
                            }
                            return new GitHubResponse(
                                    200,
                                    url.endsWith("page=1")
                                            ? firstReviews.toString()
                                            : secondReviews.toString());
                        });

        DraftReviewService.Pending pending =
                client.load("https://api.github.com", "secret", "acme", "repo", 1);

        assertThat(pending.id()).isEqualTo("777");
        assertThat(pending.commitId()).isEqualTo("head-sha");
        assertThat(pending.comments()).hasSize(101);
        assertThat(pending.comments().get(100).body()).isEqualTo("[ISSUE] page two");
        assertThat(urls)
                .contains(
                        "https://api.github.com/repos/acme/repo/pulls/1/reviews?per_page=100&page=2",
                        "https://api.github.com/repos/acme/repo/pulls/1/reviews/777/comments?per_page=100&page=2");
    }

    @Test
    void httpClientFailsClosedAtTheReviewPaginationCap() {
        ObjectMapper mapper = new ObjectMapper();
        var fullPage = mapper.createArrayNode();
        for (int index = 0; index < DraftReviewService.PAGE_SIZE; index++) {
            fullPage.addObject().put("id", index + 1).put("state", "COMMENTED");
        }
        AtomicInteger calls = new AtomicInteger();
        DraftReviewService.HttpPendingReviewClient client =
                new DraftReviewService.HttpPendingReviewClient(
                        (url, token) -> {
                            calls.incrementAndGet();
                            return new GitHubResponse(200, fullPage.toString());
                        });

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.load("https://api.github.com", "secret", "acme", "repo", 1))
                .isInstanceOf(DraftReviewService.PendingReviewFetchException.class)
                .hasMessageContaining("pagination limit");
        assertThat(calls).hasValue(DraftReviewService.MAX_PAGES);
    }
}
