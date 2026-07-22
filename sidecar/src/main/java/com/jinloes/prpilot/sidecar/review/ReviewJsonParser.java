package com.jinloes.prpilot.sidecar.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ReviewJsonParser {
    private static final Set<String> VERDICTS = Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT");
    private static final Set<String> COMMENT_TYPES = Set.of("issue", "suggestion", "note");
    private static final Set<String> SEVERITIES = Set.of("blocker", "major", "minor", "nit");
    private static final Set<String> CATEGORIES =
            Set.of("correctness", "security", "performance", "tests", "maintainability");
    private static final Set<String> CONFIDENCES = Set.of("low", "medium", "high");
    private static final Set<String> NOTE_FIELDS =
            Set.of("file", "line", "type", "severity", "category", "confidence", "body");
    private static final Set<String> COMMENT_FIELDS =
            Set.of(
                    "file",
                    "line",
                    "type",
                    "severity",
                    "category",
                    "confidence",
                    "rationale",
                    "body");
    private static final Set<String> ROOT_FIELDS = Set.of("summary", "verdict", "lineComments");

    private final ObjectMapper objectMapper;

    public ReviewJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReviewParseResult parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            return ReviewParseResult.valid(parseReview(root));
        } catch (ReviewValidationException exception) {
            return ReviewParseResult.invalid(exception.getMessage());
        } catch (JsonProcessingException exception) {
            return ReviewParseResult.invalid("review JSON is not valid JSON");
        }
    }

    private String extractJson(String raw) {
        String json = raw.trim();
        if (json.startsWith("```")) {
            int newline = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (newline > 0 && closing > newline) {
                json = json.substring(newline + 1, closing).trim();
            }
        }

        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return json.substring(start, end + 1);
        }
        return json;
    }

    private ReviewResult parseReview(JsonNode root) {
        if (!root.isObject()) {
            throw invalid("review JSON is not an object");
        }
        if (!fieldNames(root).equals(ROOT_FIELDS)) {
            throw invalid("review JSON has unexpected or missing top-level fields");
        }

        String summary = requiredString(root, "summary");
        if (summary.length() > 800) {
            throw invalid("review JSON summary exceeds 800 characters");
        }
        String verdict = requiredString(root, "verdict");
        if (!VERDICTS.contains(verdict)) {
            throw invalid("review JSON has invalid verdict");
        }

        JsonNode commentsNode = root.get("lineComments");
        if (!commentsNode.isArray()) {
            throw invalid("review JSON missing lineComments array");
        }
        if (commentsNode.size() > 20) {
            throw invalid("review JSON has more than 20 line comments");
        }

        List<ReviewLineComment> comments = new ArrayList<>();
        boolean hasIssue = false;
        for (JsonNode comment : commentsNode) {
            ReviewLineComment parsedComment = parseComment(comment);
            comments.add(parsedComment);
            hasIssue = hasIssue || "issue".equals(parsedComment.type());
        }
        if (("REQUEST_CHANGES".equals(verdict)) != hasIssue) {
            throw invalid("review verdict does not match issue comments");
        }
        return new ReviewResult(summary, verdict, List.copyOf(comments));
    }

    private ReviewLineComment parseComment(JsonNode comment) {
        if (!comment.isObject()) {
            throw invalid("review JSON line comment is not an object");
        }

        String type = requiredString(comment, "type");
        Set<String> expectedFields = "note".equals(type) ? NOTE_FIELDS : COMMENT_FIELDS;
        if (!fieldNames(comment).equals(expectedFields)) {
            throw invalid("review JSON line comment has invalid fields");
        }

        String file = requiredString(comment, "file");
        if (file.isBlank()) {
            throw invalid("review JSON line comment has blank file");
        }
        int line = requiredPositiveInt(comment, "line");
        if (!COMMENT_TYPES.contains(type)) {
            throw invalid("review JSON line comment has invalid type");
        }

        String severity = requiredString(comment, "severity");
        if (!SEVERITIES.contains(severity)) {
            throw invalid("review JSON line comment has invalid severity");
        }
        String category = requiredString(comment, "category");
        if (!CATEGORIES.contains(category)) {
            throw invalid("review JSON line comment has invalid category");
        }
        String confidence = requiredString(comment, "confidence");
        if (!CONFIDENCES.contains(confidence)) {
            throw invalid("review JSON line comment has invalid confidence");
        }
        if ("issue".equals(type) && "low".equals(confidence)) {
            throw invalid("review JSON cannot contain a low-confidence issue");
        }

        String body = requiredString(comment, "body");
        if (body.isBlank()
                || body.length() > 300
                || body.indexOf('\n') >= 0
                || body.indexOf('\r') >= 0) {
            throw invalid("review JSON line comment has invalid body");
        }

        String rationale = null;
        if (!"note".equals(type)) {
            rationale = requiredString(comment, "rationale");
            if (rationale.isBlank() || rationale.length() > 200) {
                throw invalid("review JSON line comment has invalid rationale");
            }
        }
        return new ReviewLineComment(
                file, line, type, severity, category, confidence, rationale, body);
    }

    private Set<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private String requiredString(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("review JSON missing string " + field);
        }
        return value.textValue();
    }

    private int requiredPositiveInt(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() <= 0) {
            throw invalid("review JSON line comment has invalid line");
        }
        return value.intValue();
    }

    private ReviewValidationException invalid(String message) {
        return new ReviewValidationException(message);
    }

    private static final class ReviewValidationException extends RuntimeException {
        private ReviewValidationException(String message) {
            super(message);
        }
    }
}
