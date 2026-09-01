package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PrReviewStatusServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class Enrich {

        @Test
        void derivesReviewFreshnessAndUsesOneBoundedGraphQlRequest() throws Exception {
            RecordingApiClient client =
                    new RecordingApiClient(
                            json(Map.of("login", "octocat")),
                            json(
                                    Map.of(
                                            "data",
                                            Map.of(
                                                    "pr0",
                                                    repository(
                                                            pullRequest(
                                                                    "head-1",
                                                                    List.of(
                                                                            review(
                                                                                    "APPROVED",
                                                                                    "2026-08-01T00:00:00Z",
                                                                                    "head-1")))),
                                                    "pr1",
                                                    repository(
                                                            pullRequest(
                                                                    "head-2",
                                                                    List.of(
                                                                            review(
                                                                                    "COMMENTED",
                                                                                    "2026-08-02T00:00:00Z",
                                                                                    "old-head")))),
                                                    "pr2",
                                                    repository(
                                                            pullRequest("head-3", List.of()))))));
            PrReviewStatusService service = new PrReviewStatusService(client, MAPPER);

            PrListService.ReviewStatusResponse result =
                    service.enrich(
                            GitHubApiBase.require("https://github.example.test"),
                            "secret-token",
                            List.of(pr(1), pr(2), pr(3)));

            assertThat(result.available()).isTrue();
            assertThat(result.prs())
                    .extracting(PullRequestSummary::reviewStatus)
                    .containsExactly(
                            ReviewStatus.REVIEWED,
                            ReviewStatus.UPDATED_SINCE_REVIEW,
                            ReviewStatus.UNREVIEWED);
            assertThat(client.getUrls).containsExactly("https://github.example.test/api/v3/user");
            assertThat(client.postUrls).containsExactly("https://github.example.test/api/graphql");
            assertThat(client.tokens).containsOnly("secret-token");
            JsonNode posted = MAPPER.readTree(client.postBodies.get(0));
            assertThat(posted.path("query").asText())
                    .contains(
                            "reviews(last: 1, author: $viewer, states: [APPROVED, CHANGES_REQUESTED, COMMENTED])")
                    .contains("pr0: repository")
                    .contains("pr2: repository")
                    .doesNotContain("pr3: repository");
            assertThat(posted.path("variables").path("viewer").asText()).isEqualTo("octocat");
            assertThat(posted.path("variables").path("number2").asInt()).isEqualTo(3);
            assertThat(posted.path("variables").size()).isEqualTo(10);
        }

        @Test
        void degradesEveryRowWhenGraphQlReturnsPartialErrors() throws Exception {
            RecordingApiClient client =
                    new RecordingApiClient(
                            json(Map.of("login", "octocat")),
                            json(
                                    Map.of(
                                            "data",
                                            Map.of(
                                                    "pr0",
                                                    repository(pullRequest("head", List.of()))),
                                            "errors",
                                            List.of(Map.of("message", "Field unavailable")))));

            PrListService.ReviewStatusResponse result =
                    new PrReviewStatusService(client, MAPPER)
                            .enrich(
                                    GitHubApiBase.require("https://github.com"),
                                    "token",
                                    List.of(pr(1)));

            assertThat(result.available()).isFalse();
            assertThat(result.prs().get(0).reviewStatus()).isEqualTo(ReviewStatus.UNAVAILABLE);
        }

        @Test
        void degradesWhenViewerOrGraphQlCannotBeRead() {
            RecordingApiClient viewerFailure =
                    new RecordingApiClient(
                            new GitHubResponse(500, ""), new GitHubResponse(200, ""));
            PrListService.ReviewStatusResponse viewerResult =
                    new PrReviewStatusService(viewerFailure, MAPPER)
                            .enrich(
                                    GitHubApiBase.require("https://github.com"),
                                    "token",
                                    List.of(pr(1)));

            assertThat(viewerResult.available()).isFalse();
            assertThat(viewerFailure.postUrls).isEmpty();

            RecordingApiClient graphQlFailure =
                    new RecordingApiClient(
                            new GitHubResponse(200, "{\"login\":\"octocat\"}"),
                            new GitHubResponse(503, ""));
            PrListService.ReviewStatusResponse graphQlResult =
                    new PrReviewStatusService(graphQlFailure, MAPPER)
                            .enrich(
                                    GitHubApiBase.require("https://github.com"),
                                    "token",
                                    List.of(pr(1)));

            assertThat(graphQlResult.available()).isFalse();
            assertThat(graphQlResult.prs().get(0).reviewStatus())
                    .isEqualTo(ReviewStatus.UNAVAILABLE);
        }

        @Test
        void neverBuildsMoreThanFiftyAliasesForTheListBound() throws Exception {
            List<PullRequestSummary> pullRequests = new ArrayList<>();
            Map<String, Object> data = new LinkedHashMap<>();
            for (int index = 0; index < 50; index++) {
                pullRequests.add(pr(index + 1));
                data.put("pr" + index, repository(pullRequest("head-" + index, List.of())));
            }
            RecordingApiClient client =
                    new RecordingApiClient(
                            json(Map.of("login", "octocat")), json(Map.of("data", data)));

            PrListService.ReviewStatusResponse result =
                    new PrReviewStatusService(client, MAPPER)
                            .enrich(
                                    GitHubApiBase.require("https://github.com"),
                                    "token",
                                    pullRequests);

            assertThat(result.prs()).hasSize(50);
            String query = MAPPER.readTree(client.postBodies.get(0)).path("query").asText();
            assertThat(query).contains("pr49: repository").doesNotContain("pr50: repository");
        }

        @Test
        void refusesToEnrichMoreThanTheListBound() throws Exception {
            List<PullRequestSummary> pullRequests = new ArrayList<>();
            for (int index = 0; index < 51; index++) {
                pullRequests.add(pr(index + 1));
            }
            RecordingApiClient client =
                    new RecordingApiClient(
                            json(Map.of("login", "octocat")), json(Map.of("data", Map.of())));

            PrListService.ReviewStatusResponse result =
                    new PrReviewStatusService(client, MAPPER)
                            .enrich(
                                    GitHubApiBase.require("https://github.com"),
                                    "token",
                                    pullRequests);

            assertThat(result.available()).isFalse();
            assertThat(result.prs())
                    .extracting(PullRequestSummary::reviewStatus)
                    .containsOnly(ReviewStatus.UNAVAILABLE);
            assertThat(client.getUrls).isEmpty();
            assertThat(client.postUrls).isEmpty();
        }

        @Test
        void retainsGoodRowsWhileMarkingMissingRowDataUnavailable() throws Exception {
            RecordingApiClient client =
                    new RecordingApiClient(
                            json(Map.of("login", "octocat")),
                            json(
                                    Map.of(
                                            "data",
                                            Map.of(
                                                    "pr0",
                                                    repository(pullRequest("head", List.of())),
                                                    "pr1",
                                                    Map.of()))));

            PrListService.ReviewStatusResponse result =
                    new PrReviewStatusService(client, MAPPER)
                            .enrich(
                                    GitHubApiBase.require("https://github.com"),
                                    "token",
                                    List.of(pr(1), pr(2)));

            assertThat(result.available()).isFalse();
            assertThat(result.prs())
                    .extracting(PullRequestSummary::reviewStatus)
                    .containsExactly(ReviewStatus.UNREVIEWED, ReviewStatus.UNAVAILABLE);
        }

        @Test
        void malformedViewerOrGraphQlJsonDegradesWithoutThrowing() {
            RecordingApiClient malformedViewer =
                    new RecordingApiClient(
                            new GitHubResponse(200, "{"), new GitHubResponse(200, "{}"));
            RecordingApiClient malformedGraphQl =
                    new RecordingApiClient(
                            new GitHubResponse(200, "{\"login\":\"octocat\"}"),
                            new GitHubResponse(200, "{"));

            PrListService.ReviewStatusResponse viewerResult =
                    new PrReviewStatusService(malformedViewer, MAPPER)
                            .enrich(
                                    GitHubApiBase.require("https://github.com"),
                                    "token",
                                    List.of(pr(1)));
            PrListService.ReviewStatusResponse graphQlResult =
                    new PrReviewStatusService(malformedGraphQl, MAPPER)
                            .enrich(
                                    GitHubApiBase.require("https://github.com"),
                                    "token",
                                    List.of(pr(1)));

            assertThat(viewerResult.available()).isFalse();
            assertThat(malformedViewer.postUrls).isEmpty();
            assertThat(graphQlResult.available()).isFalse();
            assertThat(graphQlResult.prs().get(0).reviewStatus())
                    .isEqualTo(ReviewStatus.UNAVAILABLE);
        }
    }

    @Nested
    class ReviewStatusMapping {

        @Test
        void latestEligibleReviewOfCurrentHeadRestoresReviewed() {
            JsonNode pullRequest =
                    MAPPER.valueToTree(
                            pullRequest(
                                    "current",
                                    List.of(
                                            review("APPROVED", "2026-07-01T00:00:00Z", "old"),
                                            review("PENDING", "2026-09-01T00:00:00Z", "current"),
                                            review("DISMISSED", "2026-09-02T00:00:00Z", "current"),
                                            review(
                                                    "COMMENTED",
                                                    "2026-08-01T00:00:00Z",
                                                    "current"))));

            assertThat(PrReviewStatusService.reviewStatus(pullRequest))
                    .isEqualTo(ReviewStatus.REVIEWED);
        }

        @Test
        void pendingAndDismissedReviewsDoNotCount() {
            JsonNode pullRequest =
                    MAPPER.valueToTree(
                            pullRequest(
                                    "current",
                                    List.of(
                                            review("PENDING", "2026-08-01T00:00:00Z", "current"),
                                            review(
                                                    "DISMISSED",
                                                    "2026-08-02T00:00:00Z",
                                                    "current"))));

            assertThat(PrReviewStatusService.reviewStatus(pullRequest))
                    .isEqualTo(ReviewStatus.UNREVIEWED);
        }

        @Test
        void missingHeadReviewConnectionOrCommitIsUnavailable() {
            assertThat(
                            PrReviewStatusService.reviewStatus(
                                    MAPPER.valueToTree(
                                            Map.of("reviews", Map.of("nodes", List.of())))))
                    .isEqualTo(ReviewStatus.UNAVAILABLE);
            assertThat(
                            PrReviewStatusService.reviewStatus(
                                    MAPPER.valueToTree(Map.of("headRefOid", "head"))))
                    .isEqualTo(ReviewStatus.UNAVAILABLE);
            assertThat(
                            PrReviewStatusService.reviewStatus(
                                    MAPPER.valueToTree(
                                            pullRequest(
                                                    "head",
                                                    List.of(
                                                            Map.of(
                                                                    "state",
                                                                    "APPROVED",
                                                                    "submittedAt",
                                                                    Instant.EPOCH.toString()))))))
                    .isEqualTo(ReviewStatus.UNAVAILABLE);
        }
    }

    private static PullRequestSummary pr(int number) {
        return new PullRequestSummary(
                number,
                "Title " + number,
                "acme",
                "widgets",
                "author",
                "2026-01-01T00:00:00Z",
                "https://github.com/acme/widgets/pull/" + number,
                false);
    }

    private static Map<String, Object> repository(Map<String, Object> pullRequest) {
        return Map.of("pullRequest", pullRequest);
    }

    private static Map<String, Object> pullRequest(
            String headRefOid, List<Map<String, Object>> reviews) {
        return Map.of("headRefOid", headRefOid, "reviews", Map.of("nodes", reviews));
    }

    private static Map<String, Object> review(String state, String submittedAt, String commitOid) {
        return Map.of(
                "state", state,
                "submittedAt", submittedAt,
                "commit", Map.of("oid", commitOid));
    }

    private static String json(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    private static final class RecordingApiClient implements PrReviewStatusService.ApiClient {
        private final GitHubResponse getResponse;
        private final GitHubResponse postResponse;
        private final List<String> getUrls = new ArrayList<>();
        private final List<String> postUrls = new ArrayList<>();
        private final List<String> postBodies = new ArrayList<>();
        private final List<String> tokens = new ArrayList<>();

        RecordingApiClient(String getBody, String postBody) {
            this(new GitHubResponse(200, getBody), new GitHubResponse(200, postBody));
        }

        RecordingApiClient(GitHubResponse getResponse, GitHubResponse postResponse) {
            this.getResponse = getResponse;
            this.postResponse = postResponse;
        }

        @Override
        public GitHubResponse get(String url, String token) {
            getUrls.add(url);
            tokens.add(token);
            return getResponse;
        }

        @Override
        public GitHubResponse postJson(String url, String token, String body) {
            postUrls.add(url);
            postBodies.add(body);
            tokens.add(token);
            return postResponse;
        }
    }
}
