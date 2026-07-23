package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrSupplementalServiceTest {
    @Test
    void searchesWithABoundedEncodedQuery() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(
                        "{\"items\":[{\"number\":42,\"title\":\"Fix\","
                                + "\"repository_url\":\"https://api.github.com/repos/acme/widgets\","
                                + "\"user\":{\"login\":\"octo\"},\"html_url\":\"https://example/pr/42\"}]}"));
        PrSupplementalService service = service(client);

        PrSearchResult result =
                service.search(
                        new PrSupplementalService.SearchParams(
                                "https://github.com", "is:pr review-requested:@me", 50));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.prs())
                .singleElement()
                .extracting(PullRequestSummary::repo)
                .isEqualTo("widgets");
        assertThat(client.paths).singleElement().asString().contains("review-requested%3A%40me");
        assertThat(result.toString()).doesNotContain("secret-token");
    }

    @Test
    void rejectsAnUnboundedSearchWithoutResolvingCredentials() {
        int[] tokenCalls = {0};
        PrSupplementalService service =
                new PrSupplementalService(
                        hostname -> {
                            tokenCalls[0]++;
                            return GitHubAuthService.TokenResolution.resolved("secret-token");
                        },
                        new FakeClient(),
                        new ObjectMapper());

        PrSearchResult result =
                service.search(new PrSupplementalService.SearchParams("", "is:pr", 101));

        assertThat(result.status()).isEqualTo("invalid_request");
        assertThat(tokenCalls[0]).isZero();
    }

    @Test
    void loadsStarredRepositoriesUntilAPartialPage() {
        FakeClient client = new FakeClient();
        client.responses.add(ok("[{\"full_name\":\"acme/one\"},{\"full_name\":\"acme/two\"}]"));

        StarredReposResult result = service(client).starred("https://github.com");

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.repositories()).containsExactly("acme/one", "acme/two");
        assertThat(client.paths).singleElement().asString().contains("page=1");
    }

    @Test
    void formatsSubmittedReviewsAndIgnoresPendingReviews() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(
                        "[{\"id\":1,\"state\":\"PENDING\"},"
                                + "{\"id\":2,\"state\":\"APPROVED\",\"body\":\"Looks good\","
                                + "\"submitted_at\":\"2026-07-22T01:00:00Z\",\"user\":{\"login\":\"sam\"}}]"));
        client.responses.add(
                ok("[{\"path\":\"src/App.java\",\"line\":12,\"body\":\"Nice change\"}]"));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary())
                .contains("Review by @sam (APPROVED, 2026-07-22):")
                .contains("Overall: \"Looks good\"")
                .contains("src/App.java:12: \"Nice change\"");
        assertThat(client.paths).hasSize(2);
    }

    private static PrSupplementalService service(FakeClient client) {
        return new PrSupplementalService(
                hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                client,
                new ObjectMapper());
    }

    private static PrSupplementalService.ApiResponse ok(String body) {
        return new PrSupplementalService.ApiResponse(200, body);
    }

    private static final class FakeClient implements PrSupplementalService.ApiClient {
        private final Deque<PrSupplementalService.ApiResponse> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();

        @Override
        public PrSupplementalService.ApiResponse get(String apiBase, String token, String path) {
            assertThat(apiBase).isEqualTo("https://api.github.com");
            assertThat(token).isEqualTo("secret-token");
            paths.add(path);
            return responses.removeFirst();
        }
    }
}
