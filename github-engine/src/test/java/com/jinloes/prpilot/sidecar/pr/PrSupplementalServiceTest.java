package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrSupplementalServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void searchesWithABoundedEncodedQuery() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(
                        "{\"items\":[{\"number\":42,\"title\":\"Fix\","
                                + "\"repository_url\":\"https://api.github.com/repos/acme/widgets\","
                                + "\"user\":{\"login\":\"octo\"},"
                                + "\"created_at\":\"2026-07-22T01:00:00Z\","
                                + "\"html_url\":\"https://example/pr/42\"}]}"));
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
    void rejectsMalformedSearchResponses() {
        List<String> malformedBodies =
                List.of(
                        "{}",
                        "{\"items\":{}}",
                        "{\"items\":[{}]}",
                        "{\"items\":[{\"number\":42,\"title\":\"Fix\","
                                + "\"repository_url\":\"malformed\","
                                + "\"user\":{\"login\":\"octo\"},"
                                + "\"created_at\":\"2026-07-22T01:00:00Z\","
                                + "\"html_url\":\"https://example/pr/42\"}]}",
                        "{\"items\":[{\"number\":0,\"title\":\"Fix\","
                                + "\"repository_url\":\"https://api.github.com/repos/acme/widgets\","
                                + "\"user\":{\"login\":\"octo\"},"
                                + "\"created_at\":\"2026-07-22T01:00:00Z\","
                                + "\"html_url\":\"https://example/pr/42\"}]}");

        for (String body : malformedBodies) {
            FakeClient client = new FakeClient();
            client.responses.add(ok(body));

            PrSearchResult result =
                    service(client)
                            .search(
                                    new PrSupplementalService.SearchParams(
                                            "https://github.com", "is:pr", 50));

            assertThat(result.status()).as(body).isEqualTo("api_failed");
            assertThat(result.prs()).as(body).isEmpty();
        }
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
                ok(
                        "[{\"pull_request_review_id\":2,\"path\":\"src/App.java\","
                                + "\"line\":12,\"body\":\"Nice change\"}]"));

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
        assertThat(client.paths)
                .containsExactly(
                        "/repos/acme/widgets/pulls/42/reviews?per_page=100&page=1",
                        "/repos/acme/widgets/pulls/42/comments?per_page=100&page=1");
    }

    @Test
    void paginatesReviewsAndFetchesCommentsOnceForThePullRequest() {
        FakeClient client = new FakeClient();
        ArrayNode firstReviewPage = MAPPER.createArrayNode();
        for (int i = 0; i < 99; i++) {
            firstReviewPage.add(review(1_000 + i, "PENDING", "pending-" + i));
        }
        firstReviewPage.add(review(2, "APPROVED", "sam"));
        client.responses.add(ok(firstReviewPage.toString()));
        client.responses.add(
                ok(MAPPER.createArrayNode().add(review(3, "COMMENTED", "lee")).toString()));
        client.responses.add(
                ok(
                        MAPPER.createArrayNode()
                                .add(comment(2, "src/A.java", 10, "First"))
                                .add(comment(3, "src/B.java", 20, "Second"))
                                .toString()));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary())
                .contains("Review by @sam")
                .contains("src/A.java:10: \"First\"")
                .contains("Review by @lee")
                .contains("src/B.java:20: \"Second\"");
        assertThat(client.paths)
                .containsExactly(
                        "/repos/acme/widgets/pulls/42/reviews?per_page=100&page=1",
                        "/repos/acme/widgets/pulls/42/reviews?per_page=100&page=2",
                        "/repos/acme/widgets/pulls/42/comments?per_page=100&page=1");
    }

    @Test
    void failedLaterPageReturnsAnExplicitFailureWithoutPartialContext() {
        FakeClient client = new FakeClient();
        ArrayNode fullReviewPage = MAPPER.createArrayNode();
        for (int i = 0; i < 100; i++) {
            fullReviewPage.add(review(i + 1, "COMMENTED", "reviewer-" + i));
        }
        client.responses.add(ok(fullReviewPage.toString()));
        client.responses.add(new GitHubResponse(500, ""));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("api_failed");
        assertThat(result.summary()).isEmpty();
        assertThat(client.paths).hasSize(2);
    }

    @Test
    void capsRenderedExistingReviewContext() {
        FakeClient client = new FakeClient();
        client.responses.add(
                ok(MAPPER.createArrayNode().add(review(2, "COMMENTED", "sam")).toString()));
        ArrayNode comments = MAPPER.createArrayNode();
        for (int i = 0; i < 100; i++) {
            comments.add(comment(2, "src/File" + i + ".java", i + 1, "x".repeat(500)));
        }
        client.responses.add(ok(comments.toString()));
        client.responses.add(ok("[]"));

        ExistingReviewsResult result =
                service(client)
                        .existingReviews(
                                new PrSupplementalService.IdentityParams(
                                        "https://github.com", "acme", "widgets", 42));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.summary().length())
                .isLessThanOrEqualTo(PrSupplementalService.MAX_EXISTING_REVIEWS_CHARS);
        assertThat(result.summary()).endsWith("...(existing review context truncated)");
    }

    private static PrSupplementalService service(FakeClient client) {
        return new PrSupplementalService(
                hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                client,
                new ObjectMapper());
    }

    private static GitHubResponse ok(String body) {
        return new GitHubResponse(200, body);
    }

    private static ObjectNode review(long id, String state, String reviewer) {
        ObjectNode review = MAPPER.createObjectNode();
        review.put("id", id);
        review.put("state", state);
        review.put("body", "Overall");
        review.put("submitted_at", "2026-07-22T01:00:00Z");
        review.putObject("user").put("login", reviewer);
        return review;
    }

    private static ObjectNode comment(long reviewId, String path, int line, String body) {
        ObjectNode comment = MAPPER.createObjectNode();
        comment.put("pull_request_review_id", reviewId);
        comment.put("path", path);
        comment.put("line", line);
        comment.put("body", body);
        return comment;
    }

    private static final class FakeClient implements PrSupplementalService.ApiClient {
        private final Deque<GitHubResponse> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();

        @Override
        public GitHubResponse get(String apiBase, String token, String path) {
            assertThat(apiBase).isEqualTo("https://api.github.com");
            assertThat(token).isEqualTo("secret-token");
            paths.add(path);
            return responses.removeFirst();
        }
    }
}
