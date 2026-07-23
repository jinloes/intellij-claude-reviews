package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.List;
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
