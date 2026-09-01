package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubHttpClient;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Adds authenticated-user review freshness to a bounded pull-request list. */
final class PrReviewStatusService implements PrListService.ReviewStatusClient {
    private static final int MAX_PULL_REQUESTS = 50;
    private static final Duration OPTIONAL_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Set<String> SUBMITTED_STATES =
            Set.of("APPROVED", "CHANGES_REQUESTED", "COMMENTED");
    private static final String REVIEW_FIELDS =
            "headRefOid reviews(last: 1, author: $viewer, "
                    + "states: [APPROVED, CHANGES_REQUESTED, COMMENTED]) "
                    + "{ nodes { state submittedAt commit { oid } } }";

    private final ApiClient client;
    private final ObjectMapper mapper;

    PrReviewStatusService() {
        this(new HttpApiClient(), new ObjectMapper());
    }

    PrReviewStatusService(ApiClient client, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public PrListService.ReviewStatusResponse enrich(
            GitHubApiBase baseUrls, String token, List<PullRequestSummary> pullRequests) {
        if (pullRequests.isEmpty()) {
            return new PrListService.ReviewStatusResponse(true, pullRequests);
        }
        if (pullRequests.size() > MAX_PULL_REQUESTS) {
            return unavailable(pullRequests);
        }
        String viewer = viewerLogin(baseUrls.apiBaseUrl(), token);
        if (viewer == null) {
            return unavailable(pullRequests);
        }

        String requestBody;
        try {
            requestBody = requestBody(viewer, pullRequests);
        } catch (IOException exception) {
            return unavailable(pullRequests);
        }
        GitHubResponse response = client.postJson(baseUrls.graphqlUrl(), token, requestBody);
        if (!response.isSuccess()) {
            return unavailable(pullRequests);
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode errors = root.path("errors");
            JsonNode data = root.path("data");
            if ((errors.isArray() && !errors.isEmpty()) || !data.isObject()) {
                return unavailable(pullRequests);
            }

            boolean available = true;
            List<PullRequestSummary> enriched = new ArrayList<>(pullRequests.size());
            for (int index = 0; index < pullRequests.size(); index++) {
                ReviewStatus reviewStatus =
                        reviewStatus(data.path(alias(index)).path("pullRequest"));
                available &= reviewStatus != ReviewStatus.UNAVAILABLE;
                enriched.add(pullRequests.get(index).withReviewStatus(reviewStatus));
            }
            return new PrListService.ReviewStatusResponse(available, enriched);
        } catch (IOException exception) {
            return unavailable(pullRequests);
        }
    }

    private String viewerLogin(String apiBaseUrl, String token) {
        GitHubResponse response = client.get(apiBaseUrl + "/user", token);
        if (!response.isSuccess()) {
            return null;
        }
        try {
            JsonNode login = mapper.readTree(response.body()).path("login");
            return login.isTextual() && !login.textValue().isBlank() ? login.textValue() : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private String requestBody(String viewer, List<PullRequestSummary> pullRequests)
            throws IOException {
        StringBuilder declaration = new StringBuilder("query ReviewStatus($viewer: String!");
        StringBuilder selection = new StringBuilder(") {");
        ObjectNode variables = mapper.createObjectNode();
        variables.put("viewer", viewer);
        for (int index = 0; index < pullRequests.size(); index++) {
            declaration
                    .append(", $owner")
                    .append(index)
                    .append(": String!, $repo")
                    .append(index)
                    .append(": String!, $number")
                    .append(index)
                    .append(": Int!");
            selection
                    .append(' ')
                    .append(alias(index))
                    .append(": repository(owner: $owner")
                    .append(index)
                    .append(", name: $repo")
                    .append(index)
                    .append(") { pullRequest(number: $number")
                    .append(index)
                    .append(") { ")
                    .append(REVIEW_FIELDS)
                    .append(" } }");
            PullRequestSummary pullRequest = pullRequests.get(index);
            variables.put("owner" + index, pullRequest.owner());
            variables.put("repo" + index, pullRequest.repo());
            variables.put("number" + index, pullRequest.number());
        }
        selection.append(" }");

        ObjectNode body = mapper.createObjectNode();
        body.put("query", declaration.append(selection).toString());
        body.set("variables", variables);
        return mapper.writeValueAsString(body);
    }

    static ReviewStatus reviewStatus(JsonNode pullRequest) {
        JsonNode headRefOid = pullRequest.path("headRefOid");
        JsonNode reviews = pullRequest.path("reviews").path("nodes");
        if (!pullRequest.isObject()
                || !headRefOid.isTextual()
                || headRefOid.textValue().isBlank()
                || !reviews.isArray()) {
            return ReviewStatus.UNAVAILABLE;
        }

        JsonNode latest = null;
        Instant latestSubmittedAt = null;
        for (JsonNode review : reviews) {
            if (!SUBMITTED_STATES.contains(review.path("state").asText())) {
                continue;
            }
            String submittedAt = review.path("submittedAt").asText("");
            Instant submitted;
            try {
                submitted = Instant.parse(submittedAt);
            } catch (DateTimeParseException exception) {
                return ReviewStatus.UNAVAILABLE;
            }
            if (latestSubmittedAt == null || !submitted.isBefore(latestSubmittedAt)) {
                latest = review;
                latestSubmittedAt = submitted;
            }
        }
        if (latest == null) {
            return ReviewStatus.UNREVIEWED;
        }
        JsonNode reviewedOid = latest.path("commit").path("oid");
        if (!reviewedOid.isTextual() || reviewedOid.textValue().isBlank()) {
            return ReviewStatus.UNAVAILABLE;
        }
        return headRefOid.textValue().equals(reviewedOid.textValue())
                ? ReviewStatus.REVIEWED
                : ReviewStatus.UPDATED_SINCE_REVIEW;
    }

    private static PrListService.ReviewStatusResponse unavailable(
            List<PullRequestSummary> pullRequests) {
        return new PrListService.ReviewStatusResponse(
                false,
                pullRequests.stream()
                        .map(pr -> pr.withReviewStatus(ReviewStatus.UNAVAILABLE))
                        .toList());
    }

    private static String alias(int index) {
        return "pr" + index;
    }

    interface ApiClient {
        GitHubResponse get(String url, String token);

        GitHubResponse postJson(String url, String token, String body);
    }

    private static final class HttpApiClient implements ApiClient {
        private final GitHubHttpClient httpClient = new GitHubHttpClient();

        @Override
        public GitHubResponse get(String url, String token) {
            return httpClient.getOnce(url, token, OPTIONAL_REQUEST_TIMEOUT);
        }

        @Override
        public GitHubResponse postJson(String url, String token, String body) {
            return httpClient.postJsonOnce(url, token, body, OPTIONAL_REQUEST_TIMEOUT);
        }
    }
}
