package com.jinloes.prpilot.ui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

final class BridgeMessageValidator {

    static final int PROTOCOL_VERSION = 1;
    private static final int MAX_TEXT = 100_000;
    private static final int MAX_COMMENTS = 1_000;
    private static final Set<String> TYPES =
            Set.of(
                    "refreshPRs",
                    "selectPR",
                    "generateReview",
                    "cancelReview",
                    "saveDraft",
                    "submitReview",
                    "deleteDraft",
                    "askClaude",
                    "clearChat",
                    "openUrl",
                    "openSettings",
                    "runAuthLogin",
                    "webviewLayoutChanged");

    private BridgeMessageValidator() {}

    static boolean isValid(JsonNode node) {
        if (node == null
                || !node.isObject()
                || node.path("protocolVersion").asInt(-1) != PROTOCOL_VERSION
                || !node.path("type").isTextual()) {
            return false;
        }
        String type = node.path("type").asText();
        if (!TYPES.contains(type)) {
            return false;
        }
        return switch (type) {
            case "refreshPRs" ->
                    optionalEnum(node.get("state"), Set.of("open", "closed", "all"))
                            && optionalEnum(
                                    node.get("searchScope"),
                                    Set.of(
                                            "currentRepo",
                                            "authored",
                                            "assigned",
                                            "reviewRequested"))
                            && optionalBoolean(node.get("assignedToMe"))
                            && optionalBoolean(node.get("reviewRequested"));
            case "cancelReview", "openSettings", "clearChat", "runAuthLogin" -> true;
            case "openUrl" -> boundedText(node.get("url"), 4_096);
            case "webviewLayoutChanged" -> boundedText(node.get("reason"), 4_096);
            case "askClaude" ->
                    boundedText(node.get("question"), MAX_TEXT)
                            && optionalText(node.get("context"), MAX_TEXT);
            case "selectPR", "deleteDraft" -> hasValidPrIdentity(node);
            case "generateReview" ->
                    hasValidPrIdentity(node)
                            && optionalText(node.get("focusAreas"), 10_000)
                            && optionalText(node.get("customInstructions"), 20_000);
            case "saveDraft" ->
                    hasValidPrIdentity(node)
                            && (node.get("result") == null || validReview(node.get("result")))
                            && validOptionalComments(node.get("orphans"));
            case "submitReview" ->
                    hasValidPrIdentity(node)
                            && enumText(
                                    node.get("verdict"),
                                    Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT"))
                            && boundedText(node.get("comment"), MAX_TEXT);
            default -> false;
        };
    }

    private static boolean hasValidPrIdentity(JsonNode node) {
        return node.path("number").canConvertToInt()
                && node.path("number").asInt() > 0
                && boundedText(node.get("owner"), 256)
                && StringUtils.isNotBlank(node.path("owner").asText())
                && boundedText(node.get("repo"), 256)
                && StringUtils.isNotBlank(node.path("repo").asText());
    }

    private static boolean validReview(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode comments = node.get("lineComments");
        return boundedText(node.get("summary"), MAX_TEXT)
                && enumText(node.get("verdict"), Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT"))
                && comments != null
                && comments.isArray()
                && comments.size() <= MAX_COMMENTS
                && allCommentsValid(comments);
    }

    private static boolean validOptionalComments(JsonNode comments) {
        return comments == null
                || (comments.isArray()
                        && comments.size() <= MAX_COMMENTS
                        && allCommentsValid(comments));
    }

    private static boolean allCommentsValid(JsonNode comments) {
        for (JsonNode comment : comments) {
            if (!validComment(comment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validComment(JsonNode node) {
        return node != null
                && node.isObject()
                && boundedText(node.get("file"), 4_096)
                && node.path("line").canConvertToInt()
                && node.path("line").asInt() > 0
                && enumText(node.get("type"), Set.of("issue", "suggestion", "note"))
                && boundedText(node.get("body"), MAX_TEXT)
                && optionalEnum(node.get("severity"), Set.of("blocker", "major", "minor", "nit"))
                && optionalEnum(
                        node.get("category"),
                        Set.of(
                                "correctness",
                                "security",
                                "performance",
                                "tests",
                                "maintainability",
                                "style"))
                && optionalEnum(node.get("confidence"), Set.of("low", "medium", "high"))
                && optionalText(node.get("rationale"), MAX_TEXT);
    }

    private static boolean boundedText(JsonNode node, int maxLength) {
        return node != null && node.isTextual() && node.textValue().length() <= maxLength;
    }

    private static boolean optionalText(JsonNode node, int maxLength) {
        return node == null || boundedText(node, maxLength);
    }

    private static boolean enumText(JsonNode node, Set<String> allowed) {
        return node != null && node.isTextual() && allowed.contains(node.textValue());
    }

    private static boolean optionalEnum(JsonNode node, Set<String> allowed) {
        return node == null || enumText(node, allowed);
    }

    private static boolean optionalBoolean(JsonNode node) {
        return node == null || node.isBoolean();
    }
}
