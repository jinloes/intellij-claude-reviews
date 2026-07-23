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
}
