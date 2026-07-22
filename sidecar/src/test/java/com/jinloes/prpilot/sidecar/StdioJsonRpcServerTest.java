package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.pr.PrSearchQueryService;
import com.jinloes.prpilot.sidecar.review.ReviewJsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                        new PrSearchQueryService());
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
