package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Encodes/decodes PR Pilot review metadata embedded in a GitHub review body, falling back to GitHub
 * inline comments when absent or invalid.
 */
public final class DraftReviewCodec {
    private static final String VERDICT_TAG = "<!-- claude-verdict: ";
    private static final String SUMMARY_TAG = "<!-- claude-summary: ";
    private static final String COMMENTS_TAG = "<!-- claude-comments: ";
    private static final String TAG_END = " -->";
    private static final String DETACHED_COMMENTS_HEADER =
            "**Comments not attached inline (invalid diff positions):**";
    private final ObjectMapper mapper;

    DraftReviewCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    DecodedReview decode(String body, List<ApiComment> apiComments) {
        String safeBody = body == null ? "" : body;
        String verdict = tag(safeBody, VERDICT_TAG, "COMMENT");
        String summary = tag(safeBody, SUMMARY_TAG, "");
        String encoded = tag(safeBody, COMMENTS_TAG, null);
        if (encoded != null) {
            try {
                List<EncodedComment> comments = mapper.readValue(encoded, new TypeReference<>() {});
                List<LineComment> lines = new ArrayList<>();
                for (EncodedComment c : comments)
                    lines.add(
                            new LineComment(
                                    c.f(), c.l(), c.t(), c.b(), c.s(), c.c(), c.cf(), c.r()));
                return new DecodedReview(summary, verdict, lines, false);
            } catch (Exception ignored) {
            }
        }
        List<LineComment> lines = new ArrayList<>();
        for (ApiComment c : apiComments) {
            String text = c.body() == null ? "" : c.body();
            String type = "note";
            if (text.matches("^\\[[A-Z]+]\\s+.*")) {
                int end = text.indexOf(']');
                type = text.substring(1, end).toLowerCase();
                text = text.substring(end + 1).trim();
            }
            lines.add(
                    new LineComment(
                            c.path() == null ? "" : c.path(),
                            c.line() == null
                                    ? (c.originalLine() == null ? 0 : c.originalLine())
                                    : c.line(),
                            type,
                            text,
                            null,
                            null,
                            null,
                            null));
        }
        return new DecodedReview(summary, verdict, lines, true);
    }

    private String tag(String body, String start, String fallback) {
        int index = body.indexOf(start);
        if (index < 0) return fallback;
        int end = body.indexOf(TAG_END, index + start.length());
        return end < 0 ? fallback : body.substring(index + start.length(), end).trim();
    }

    /**
     * Encodes a summary/verdict/comment set into a GitHub review body carrying the PR Pilot
     * HTML-comment tags {@link #decode} understands, plus a trailing "General Notes" section for
     * comments with no file/line.
     */
    String encodeBody(String summary, String verdict, List<LineComment> lineComments) {
        StringBuilder sb = new StringBuilder(SUMMARY_TAG).append(escape(summary)).append(TAG_END);
        sb.append("\n").append(VERDICT_TAG).append(escape(verdict)).append(TAG_END);

        ArrayNode encoded = mapper.createArrayNode();
        for (LineComment c : lineComments) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("f", c.file());
            obj.put("l", c.line());
            obj.put("t", c.type());
            obj.put("b", c.body());
            if (c.severity() != null && !c.severity().isBlank()) obj.put("s", c.severity());
            if (c.category() != null && !c.category().isBlank()) obj.put("c", c.category());
            if (c.confidence() != null && !c.confidence().isBlank()) obj.put("cf", c.confidence());
            if (c.rationale() != null && !c.rationale().isBlank()) obj.put("r", c.rationale());
            encoded.add(obj);
        }
        sb.append("\n")
                .append(COMMENTS_TAG)
                .append(encoded.toString().replace("-->", "-- >"))
                .append(TAG_END);

        List<LineComment> general =
                lineComments.stream()
                        .filter(c -> c.file() == null || c.file().isBlank() || c.line() <= 0)
                        .toList();
        if (!general.isEmpty()) {
            sb.append("\n\n**General Notes:**");
            for (LineComment c : general) sb.append("\n- ").append(c.body());
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("-->", "-- >");
    }

    /**
     * Builds the deduplicated inline-comment array from a review's line comments, excluding any
     * pre-known {@code orphans} (comments the caller already determined have no valid diff position
     * — those belong in the body's "Comments not attached inline" section instead).
     */
    ArrayNode buildCommentArray(List<LineComment> lineComments, List<LineComment> orphans) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> orphanKeys = new HashSet<>();
        for (LineComment o : orphans) orphanKeys.add(orphanKey(o));

        ArrayNode result = mapper.createArrayNode();
        for (LineComment c : lineComments) {
            String file = c.file() == null ? "" : c.file();
            if (file.startsWith("a/") || file.startsWith("b/")) file = file.substring(2);
            if (file.isBlank() || c.line() <= 0 || c.body() == null || c.body().isBlank()) continue;
            if (orphanKeys.contains(orphanKey(c))) continue;
            String dedupeKey = file + "\u0000" + c.line() + "\u0000" + c.body();
            if (!seen.add(dedupeKey)) continue;
            ObjectNode obj = mapper.createObjectNode();
            obj.put("path", file);
            obj.put("line", c.line());
            obj.put("side", "RIGHT");
            obj.put("body", c.body());
            result.add(obj);
        }
        return result;
    }

    private static String orphanKey(LineComment c) {
        return (c.file() == null ? "" : c.file())
                + "|"
                + c.line()
                + "|"
                + c.type()
                + "|"
                + c.body();
    }

    /** Formats pre-known orphan comments into the body section GitHub renders verbatim. */
    String buildOrphanSection(List<LineComment> orphans) {
        StringBuilder sb = new StringBuilder(DETACHED_COMMENTS_HEADER).append("\n");
        for (LineComment c : orphans) {
            appendDetachedCommentLine(sb, c.file(), c.line(), c.body());
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Formats comments GitHub rejected (422) into the same body section as {@link
     * #buildOrphanSection}, reading each comment's {@code path}/{@code line}/{@code body} from the
     * JSON payload that was POSTed and dropped.
     */
    String buildDroppedSection(List<JsonNode> dropped) {
        StringBuilder sb = new StringBuilder(DETACHED_COMMENTS_HEADER).append("\n");
        for (JsonNode c : dropped) {
            appendDetachedCommentLine(
                    sb,
                    c.path("path").asText(""),
                    c.path("line").asInt(0),
                    c.path("body").asText(""));
        }
        return sb.toString().stripTrailing();
    }

    private static void appendDetachedCommentLine(
            StringBuilder sb, String path, int line, String body) {
        sb.append("- `").append(path == null ? "" : path);
        if (line > 0) sb.append(":").append(line);
        sb.append("`: ").append(body == null ? "" : body).append("\n");
    }

    record ApiComment(String path, Integer line, Integer originalLine, String body) {}

    public record LineComment(
            String file,
            int line,
            String type,
            String body,
            String severity,
            String category,
            String confidence,
            String rationale) {}

    public record DecodedReview(
            String summary,
            String verdict,
            List<LineComment> lineComments,
            boolean importedFromGitHub) {}

    private record EncodedComment(
            String f, int l, String t, String b, String s, String c, String cf, String r) {}
}
