package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSearchQueryService;
import com.jinloes.prpilot.sidecar.repo.RepoDetector;
import com.jinloes.prpilot.sidecar.review.ReviewJsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;

final class StdioJsonRpcServer {
    private static final String JSON_RPC_VERSION = "2.0";

    private final ObjectMapper objectMapper;
    private final StdioFrameCodec frameCodec;
    private final SidecarBootstrapService bootstrapService;
    private final ReviewJsonParser reviewJsonParser;
    private final PrSearchQueryService prSearchQueryService;
    private final RepoDetector repoDetector;
    private final GitHubAuthService gitHubAuthService;
    private final PrListService prListService;

    StdioJsonRpcServer(
            ObjectMapper objectMapper,
            StdioFrameCodec frameCodec,
            SidecarBootstrapService bootstrapService,
            ReviewJsonParser reviewJsonParser,
            PrSearchQueryService prSearchQueryService,
            RepoDetector repoDetector,
            GitHubAuthService gitHubAuthService,
            PrListService prListService) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.frameCodec = Objects.requireNonNull(frameCodec);
        this.bootstrapService = Objects.requireNonNull(bootstrapService);
        this.reviewJsonParser = Objects.requireNonNull(reviewJsonParser);
        this.prSearchQueryService = Objects.requireNonNull(prSearchQueryService);
        this.repoDetector = Objects.requireNonNull(repoDetector);
        this.gitHubAuthService = Objects.requireNonNull(gitHubAuthService);
        this.prListService = Objects.requireNonNull(prListService);
    }

    void run(InputStream input, OutputStream output) {
        try {
            byte[] frame;
            while ((frame = frameCodec.readFrame(input)) != null) {
                JsonNode response = handle(frame);
                if (response != null) {
                    frameCodec.writeFrame(output, objectMapper.writeValueAsBytes(response));
                }
            }
        } catch (IOException exception) {
            throw new SidecarProtocolException(
                    "Unable to read or write JSON-RPC messages", exception);
        }
    }

    JsonNode handle(byte[] frame) {
        JsonNode request;
        try {
            request = objectMapper.readTree(frame);
        } catch (IOException exception) {
            return error(null, -32700, "Parse error");
        }

        if (!isValidRequest(request)) {
            return error(requestId(request), -32600, "Invalid Request");
        }

        boolean notification = !request.has("id");
        String method = request.path("method").asText();
        JsonNode response;
        try {
            response =
                    switch (method) {
                        case "initialize" ->
                                result(requestId(request), bootstrapService.initialize());
                        case "review/parse" -> parseReview(request);
                        case "pr/buildSearchQuery" -> buildPrSearchQuery(request);
                        case "repo/detect" -> detectRepo(request);
                        case "github/checkAuth" -> checkGitHubAuth(request);
                        case "prs/list" -> listPullRequests(request);
                        default -> error(requestId(request), -32601, "Method not found");
                    };
        } catch (RuntimeException exception) {
            response = error(requestId(request), -32603, "Internal error");
        }

        return notification ? null : response;
    }

    private ObjectNode parseReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("raw").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(requestId(request), reviewJsonParser.parse(params.path("raw").textValue()));
    }

    private ObjectNode buildPrSearchQuery(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(params, Set.of("state", "searchScope", "currentRepo"))
                || !hasOnlyTextValues(params)) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                prSearchQueryService.build(
                        new PrSearchQueryService.QueryParams(
                                optionalText(params, "state"),
                                optionalText(params, "searchScope"),
                                optionalText(params, "currentRepo"))));
    }

    private ObjectNode detectRepo(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("path").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(requestId(request), repoDetector.detect(params.path("path").textValue()));
    }

    private ObjectNode checkGitHubAuth(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("githubBaseUrl").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                gitHubAuthService.check(params.path("githubBaseUrl").textValue()));
    }

    private ObjectNode listPullRequests(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !hasOnlyFields(
                        params, Set.of("githubBaseUrl", "state", "searchScope", "currentRepo"))
                || !hasOnlyTextValues(params)
                || !params.path("githubBaseUrl").isTextual()) {
            return error(requestId(request), -32602, "Invalid params");
        }
        return result(
                requestId(request),
                prListService.list(
                        new PrListService.PrListParams(
                                params.path("githubBaseUrl").textValue(),
                                optionalText(params, "state"),
                                optionalText(params, "searchScope"),
                                optionalText(params, "currentRepo"))));
    }

    private boolean hasOnlyFields(JsonNode object, Set<String> allowedFields) {
        return object.properties().stream()
                .allMatch(entry -> allowedFields.contains(entry.getKey()));
    }

    private boolean hasOnlyTextValues(JsonNode object) {
        return object.properties().stream().allMatch(entry -> entry.getValue().isTextual());
    }

    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null ? null : value.textValue();
    }

    private boolean isValidRequest(JsonNode request) {
        return request != null
                && request.isObject()
                && JSON_RPC_VERSION.equals(request.path("jsonrpc").asText())
                && request.path("method").isTextual();
    }

    private JsonNode requestId(JsonNode request) {
        if (request != null && request.isObject() && request.has("id")) {
            return request.get("id");
        }
        return JsonNodeFactory.instance.nullNode();
    }

    private ObjectNode result(JsonNode id, Object value) {
        ObjectNode response = baseResponse(id);
        response.set("result", objectMapper.valueToTree(value));
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = baseResponse(id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private ObjectNode baseResponse(JsonNode id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        return response;
    }

    private static final class SidecarProtocolException extends RuntimeException {
        private SidecarProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
