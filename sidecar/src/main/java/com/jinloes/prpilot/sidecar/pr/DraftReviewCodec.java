package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes PR Pilot review metadata, falling back to GitHub inline comments when absent or invalid.
 */
final class DraftReviewCodec {
    private static final String VERDICT_TAG = "<!-- claude-verdict: ";
    private static final String SUMMARY_TAG = "<!-- claude-summary: ";
    private static final String COMMENTS_TAG = "<!-- claude-comments: ";
    private static final String TAG_END = " -->";
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

    record ApiComment(String path, Integer line, Integer originalLine, String body) {}

    record LineComment(
            String file,
            int line,
            String type,
            String body,
            String severity,
            String category,
            String confidence,
            String rationale) {}

    record DecodedReview(
            String summary,
            String verdict,
            List<LineComment> lineComments,
            boolean importedFromGitHub) {}

    private record EncodedComment(
            String f, int l, String t, String b, String s, String c, String cf, String r) {}
}
