package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DraftReviewCodecTest {
    private final DraftReviewCodec codec = new DraftReviewCodec(new ObjectMapper());

    @Test
    void decodesEmbeddedPrPilotMetadata() {
        DraftReviewCodec.DecodedReview review =
                codec.decode(
                        "<!-- claude-summary: summary -->\n<!-- claude-verdict: APPROVE -->\n<!-- claude-comments: [{\"f\":\"a.java\",\"l\":2,\"t\":\"issue\",\"b\":\"body\"}] -->",
                        List.of());
        assertThat(review.summary()).isEqualTo("summary");
        assertThat(review.verdict()).isEqualTo("APPROVE");
        assertThat(review.importedFromGitHub()).isFalse();
        assertThat(review.lineComments()).hasSize(1);
    }

    @Test
    void recoversGitHubCommentsWhenMetadataIsMissing() {
        DraftReviewCodec.DecodedReview review =
                codec.decode(
                        "<!-- claude-summary: summary -->",
                        List.of(
                                new DraftReviewCodec.ApiComment(
                                        "a.java", null, 3, "[ISSUE] body")));
        assertThat(review.importedFromGitHub()).isTrue();
        assertThat(review.lineComments().get(0).line()).isEqualTo(3);
        assertThat(review.lineComments().get(0).type()).isEqualTo("issue");
    }

    @Test
    void recoversGitHubCommentsWhenEmbeddedMetadataIsInvalid() {
        DraftReviewCodec.DecodedReview review =
                codec.decode(
                        "<!-- claude-summary: summary -->\n"
                                + "<!-- claude-verdict: BOGUS -->\n"
                                + "<!-- claude-comments: [{\"f\":\"a.java\",\"l\":2,\"t\":\"invalid\",\"b\":\"body\"}] -->",
                        List.of(new DraftReviewCodec.ApiComment("a.java", 2, null, "[NOTE] api")));

        assertThat(review.importedFromGitHub()).isTrue();
        assertThat(review.verdict()).isEqualTo("COMMENT");
        assertThat(review.lineComments()).hasSize(1);
        assertThat(review.lineComments().get(0).body()).isEqualTo("api");
    }

    @Test
    void encodeBodyRoundTripsThroughDecode() {
        List<DraftReviewCodec.LineComment> comments =
                List.of(
                        new DraftReviewCodec.LineComment(
                                "a.java",
                                4,
                                "issue",
                                "fix this",
                                "major",
                                "correctness",
                                "high",
                                "because"),
                        new DraftReviewCodec.LineComment(
                                "", 0, "note", "general note", null, null, null, null));

        String body = codec.encodeBody("overall summary", "REQUEST_CHANGES", comments);

        assertThat(body).contains("<!-- claude-summary: overall summary -->");
        assertThat(body).contains("<!-- claude-verdict: REQUEST_CHANGES -->");
        assertThat(body).contains("**General Notes:**");
        assertThat(body).contains("- general note");

        DraftReviewCodec.DecodedReview decoded = codec.decode(body, List.of());
        assertThat(decoded.summary()).isEqualTo("overall summary");
        assertThat(decoded.verdict()).isEqualTo("REQUEST_CHANGES");
        assertThat(decoded.importedFromGitHub()).isFalse();
        assertThat(decoded.lineComments()).hasSize(2);
        assertThat(decoded.lineComments().get(0).body()).isEqualTo("fix this");
        assertThat(decoded.lineComments().get(0).severity()).isEqualTo("major");
    }

    @Test
    void encodeBodyEscapesEmbeddedTagTerminator() {
        String body = codec.encodeBody("has --> inside", "COMMENT", List.of());
        assertThat(body).doesNotContain("summary: has --> inside -->");
        assertThat(body).contains("has -- > inside");
    }

    @Test
    void buildCommentArrayDedupesAndExcludesOrphansAndBlankEntries() {
        DraftReviewCodec.LineComment orphan =
                new DraftReviewCodec.LineComment(
                        "b.java", 9, "note", "orphaned", null, null, null, null);
        List<DraftReviewCodec.LineComment> comments =
                List.of(
                        new DraftReviewCodec.LineComment(
                                "a/x.java", 1, "issue", "dup", null, null, null, null),
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "issue", "dup", null, null, null, null),
                        orphan,
                        new DraftReviewCodec.LineComment(
                                "", 0, "note", "general", null, null, null, null));

        var array = codec.buildCommentArray(comments, List.of(orphan));

        assertThat(array).hasSize(1);
        assertThat(array.get(0).path("path").asText()).isEqualTo("x.java");
        assertThat(array.get(0).path("line").asInt()).isEqualTo(1);
    }

    @Test
    void buildCommentArrayCollapsesCommentsDifferingOnlyByType() {
        // type is not part of the posted payload (path/line/side/body), so two findings that
        // differ only by type would post as two byte-identical GitHub comments. Collapsing them
        // is intentional and must not be "unified" with the webview's type-aware merge key.
        List<DraftReviewCodec.LineComment> comments =
                List.of(
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "issue", "same body", null, null, null, null),
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "suggestion", "same body", null, null, null, null));

        var array = codec.buildCommentArray(comments, List.of());

        assertThat(array).hasSize(1);
        assertThat(array.get(0).path("body").asText()).isEqualTo("same body");
    }

    @Test
    void buildCommentArrayKeepsCommentsOnTheSameLineWithDifferentBodies() {
        List<DraftReviewCodec.LineComment> comments =
                List.of(
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "issue", "first", null, null, null, null),
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "issue", "second", null, null, null, null));

        var array = codec.buildCommentArray(comments, List.of());

        assertThat(array).hasSize(2);
    }

    @Test
    void buildCommentArrayOrphanExclusionDistinguishesByType() {
        // orphanKey includes type, so an orphaned "note" must not suppress a same-line "issue".
        DraftReviewCodec.LineComment orphan =
                new DraftReviewCodec.LineComment(
                        "x.java", 1, "note", "same body", null, null, null, null);
        DraftReviewCodec.LineComment kept =
                new DraftReviewCodec.LineComment(
                        "x.java", 1, "issue", "same body", null, null, null, null);

        var array = codec.buildCommentArray(List.of(orphan, kept), List.of(orphan));

        assertThat(array).hasSize(1);
        assertThat(array.get(0).path("body").asText()).isEqualTo("same body");
    }

    @Test
    void buildCommentArrayNormalizesOrphanPathPrefixes() {
        DraftReviewCodec.LineComment comment =
                new DraftReviewCodec.LineComment(
                        "b/x.java", 1, "note", "same body", null, null, null, null);
        DraftReviewCodec.LineComment orphan =
                new DraftReviewCodec.LineComment(
                        "x.java", 1, "note", "same body", null, null, null, null);

        assertThat(codec.buildCommentArray(List.of(comment), List.of(orphan))).isEmpty();
    }

    @Test
    void withoutDroppedCommentsMatchesNormalizedPostedPayloadIdentity() {
        DraftReviewCodec.LineComment dropped =
                new DraftReviewCodec.LineComment(
                        "b/x.java", 4, "issue", "rejected", null, null, null, null);
        DraftReviewCodec.LineComment accepted =
                new DraftReviewCodec.LineComment(
                        "x.java", 5, "note", "accepted", null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        var payload = mapper.createObjectNode();
        payload.put("path", "x.java");
        payload.put("line", 4);
        payload.put("body", "rejected");

        assertThat(codec.withoutDroppedComments(List.of(dropped, accepted), List.of(payload)))
                .containsExactly(accepted);
    }

    @Test
    void buildOrphanAndDroppedSectionsFormatDetachedComments() {
        DraftReviewCodec.LineComment orphan =
                new DraftReviewCodec.LineComment(
                        "a.java", 5, "note", "no position", null, null, null, null);
        String orphanSection = codec.buildOrphanSection(List.of(orphan));
        assertThat(orphanSection)
                .contains("**Comments not attached inline (invalid diff positions):**");
        assertThat(orphanSection).contains("- `a.java:5`: no position");

        ObjectMapper mapper = new ObjectMapper();
        var dropped = mapper.createObjectNode();
        dropped.put("path", "b.java");
        dropped.put("line", 7);
        dropped.put("body", "dropped comment");
        String droppedSection = codec.buildDroppedSection(List.of(dropped));
        assertThat(droppedSection).contains("- `b.java:7`: dropped comment");
    }
}
