package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewService;
import com.jinloes.prpilot.sidecar.pr.PrDetailService;
import com.jinloes.prpilot.sidecar.pr.PrDiffService;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSearchQueryService;
import com.jinloes.prpilot.sidecar.repo.RepoDetector;
import com.jinloes.prpilot.sidecar.review.ReviewJsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdioJsonRpcServerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StdioFrameCodec frameCodec = new StdioFrameCodec();
    private StdioJsonRpcServer server;

    @BeforeEach
    void setUp() {
        server =
                new StdioJsonRpcServer(
                        objectMapper,
                        frameCodec,
                        new SidecarBootstrapService(),
                        new ReviewJsonParser(objectMapper),
                        new PrSearchQueryService(),
                        new RepoDetector(),
                        new GitHubAuthService(),
                        new PrListService(),
                        new PrDetailService(),
                        new PrDiffService(),
                        new DraftReviewService(),
                        new DraftReviewMutationService());
    }

    @Test
    void handlesInitializeAndWritesOnlyAFramedResponse() throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        frameCodec.writeFrame(
                input,
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\"}"
                        .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        server.run(new ByteArrayInputStream(input.toByteArray()), output);

        JsonNode response =
                objectMapper.readTree(
                        frameCodec.readFrame(new ByteArrayInputStream(output.toByteArray())));
        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.path("id").asInt()).isEqualTo(7);
        assertThat(response.path("result").path("serviceName").asText())
                .isEqualTo("pr-pilot-sidecar");
        assertThat(response.path("result").path("protocolVersion").asInt()).isEqualTo(1);
        assertThat(response.path("result").path("capabilities").path("reviewParse").asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("prSearchQuery").asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("githubAuth").asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("prList").asBoolean())
                .isTrue();
        assertThat(response.path("result").path("capabilities").path("prDetail").asBoolean())
                .isTrue();
        assertThat(
                        response.path("result")
                                .path("capabilities")
                                .path("draftReviewMutations")
                                .asBoolean())
                .isTrue();
    }

    @Test
    void parsesAValidProviderReview() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"parse-1\",\"method\":\"review/parse\","
                                        + "\"params\":{\"raw\":\"```json\\n{\\\"summary\\\":\\\"s\\\","
                                        + "\\\"verdict\\\":\\\"APPROVE\\\",\\\"lineComments\\\":[]}\\n```\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("parse-1");
        assertThat(response.path("result").path("valid").asBoolean()).isTrue();
        assertThat(response.path("result").path("review").path("verdict").asText())
                .isEqualTo("APPROVE");
    }

    @Test
    void returnsAStructuredValidationResultForAnInvalidReview() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"review/parse\","
                                        + "\"params\":{\"raw\":\"{\\\"summary\\\":\\\"s\\\","
                                        + "\\\"verdict\\\":\\\"LGTM\\\",\\\"lineComments\\\":[]}\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("result").path("valid").asBoolean()).isFalse();
        assertThat(response.path("result").path("error").path("code").asText())
                .isEqualTo("invalid_review_json");
        assertThat(response.path("result").path("error").path("message").asText())
                .isEqualTo("review JSON has invalid verdict");
    }

    @Test
    void rejectsMissingRawReviewParams() {
        JsonNode response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"review/parse\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void buildsAPullRequestSearchQuery() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"query-1\",\"method\":\"pr/buildSearchQuery\","
                                        + "\"params\":{\"state\":\"closed\",\"searchScope\":\"currentRepo\","
                                        + "\"currentRepo\":\"acme/platform\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("query-1");
        assertThat(response.path("result").path("query").asText())
                .isEqualTo("is:pr is:closed repo:acme/platform");
    }

    @Test
    void rejectsUnknownOrNonTextPullRequestQueryParams() {
        JsonNode unknownFieldResponse =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"pr/buildSearchQuery\",\"params\":{\"extra\":true}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonTextResponse =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"pr/buildSearchQuery\",\"params\":{\"state\":1}}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(unknownFieldResponse.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonTextResponse.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void detectsARepoFromAGitConfig(@TempDir java.nio.file.Path tempDir) throws IOException {
        java.nio.file.Path gitDir = tempDir.resolve(".git");
        java.nio.file.Files.createDirectories(gitDir);
        java.nio.file.Files.writeString(
                gitDir.resolve("config"),
                "[remote \"origin\"]\n\turl = https://github.com/acme/widgets.git\n");

        com.fasterxml.jackson.databind.node.ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", "repo-1");
        request.put("method", "repo/detect");
        request.putObject("params").put("path", tempDir.toString());

        JsonNode response = server.handle(objectMapper.writeValueAsBytes(request));

        assertThat(response.path("id").asText()).isEqualTo("repo-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("found");
        assertThat(response.path("result").path("repository").path("owner").asText())
                .isEqualTo("acme");
        assertThat(response.path("result").path("repository").path("repo").asText())
                .isEqualTo("widgets");
    }

    @Test
    void rejectsInvalidRepoDetectParams() {
        JsonNode missingField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"repo/detect\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonTextField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"repo/detect\",\"params\":{\"path\":1}}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingField.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonTextField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidGitHubBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"auth-1\",\"method\":\"github/checkAuth\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("auth-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("username").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidGitHubAuthParams() {
        JsonNode missingField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"github/checkAuth\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"github/checkAuth\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"extra\":\"x\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingField.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidPrListBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"list-1\",\"method\":\"prs/list\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("list-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("prs").isArray()).isTrue();
        assertThat(response.path("result").path("prs")).isEmpty();
    }

    @Test
    void rejectsInvalidPrListParams() {
        JsonNode missingBaseUrl =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"prs/list\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonTextField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"prs/list\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"state\":true}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingBaseUrl.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonTextField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidPrDetailBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"detail-1\",\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":42}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("detail-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("detail").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidPrDetailParams() {
        JsonNode missingNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonIntegralNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":1.5}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingNumber.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonIntegralNumber.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"draft-1\",\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":42}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("draft-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("review").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidDraftReviewParams() {
        JsonNode missingNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":1,\"extra\":\"x\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingNumber.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidSaveDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"save-1\",\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[]}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("save-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidSaveDraftReviewParams() {
        JsonNode missingLineComments =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode malformedComment =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[{\"file\":\"a.java\"}]}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[],\"extra\":true}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingLineComments.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(malformedComment.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidSubmitReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"submit-1\",\"method\":\"prs/submitReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"reviewId\":\"7\",\"event\":\"APPROVE\",\"body\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("submit-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidSubmitReviewParams() {
        JsonNode missingReviewId =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"prs/submitReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"event\":\"APPROVE\",\"body\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingReviewId.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidDeleteDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"delete-1\",\"method\":\"prs/deleteDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"reviewId\":\"7\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("delete-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidDeleteDraftReviewParams() {
        JsonNode missingReviewId =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"prs/deleteDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingReviewId.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsParseErrorForMalformedJson() throws IOException {
        JsonNode response = server.handle("not-json".getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").isNull()).isTrue();
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(response.path("error").path("message").asText()).isEqualTo("Parse error");
    }

    @Test
    void returnsMethodNotFoundAndPreservesTheRequestId() throws IOException {
        JsonNode response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":\"review/generate\"}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("request-1");
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void suppressesResponsesToNotifications() {
        JsonNode response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\"}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response).isNull();
    }
}
