package com.jinloes.prpilot.sidecar.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewJsonParserTest {
    private ReviewJsonParser parser;

    @BeforeEach
    void setUp() {
        parser = new ReviewJsonParser(new ObjectMapper());
    }

    @Test
    void parsesFencedReviewsWithSurroundingProse() {
        ReviewParseResult result =
                parser.parse(
                        "Here is the review:\n```json\n"
                                + "{\"summary\":\"## Overview\",\"verdict\":\"REQUEST_CHANGES\","
                                + "\"lineComments\":[{\"file\":\"src/a.ts\",\"line\":5,\"type\":\"issue\","
                                + "\"severity\":\"major\",\"category\":\"security\",\"confidence\":\"high\","
                                + "\"rationale\":\"The branch returns null.\",\"body\":\"Return a value here.\"}]}\n"
                                + "```\nThanks!");

        assertThat(result.valid()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.review().verdict()).isEqualTo("REQUEST_CHANGES");
        assertThat(result.review().lineComments())
                .singleElement()
                .satisfies(
                        comment -> {
                            assertThat(comment.file()).isEqualTo("src/a.ts");
                            assertThat(comment.line()).isEqualTo(5);
                            assertThat(comment.rationale()).isEqualTo("The branch returns null.");
                        });
    }

    @Test
    void acceptsAnApprovalWithNoComments() {
        ReviewParseResult result =
                parser.parse(
                        "{\"summary\":\"Looks good\",\"verdict\":\"APPROVE\",\"lineComments\":[]}");

        assertThat(result.valid()).isTrue();
        assertThat(result.review().lineComments()).isEmpty();
    }

    @Test
    void rejectsMalformedAndNonObjectJson() {
        assertInvalid("not JSON", "review JSON is not valid JSON");
        assertInvalid("[]", "review JSON is not an object");
    }

    @Test
    void rejectsUnexpectedFieldsAndInvalidVerdicts() {
        assertInvalid(
                "{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[],\"extra\":true}",
                "review JSON has unexpected or missing top-level fields");
        assertInvalid(
                "{\"summary\":\"s\",\"verdict\":\"LGTM\",\"lineComments\":[]}",
                "review JSON has invalid verdict");
    }

    @Test
    void rejectsInvalidCommentFieldsAndLowConfidenceIssues() {
        assertInvalid(
                "{\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[{\"file\":\"a\","
                        + "\"line\":\"5\",\"type\":\"note\",\"severity\":\"minor\","
                        + "\"category\":\"tests\",\"confidence\":\"low\",\"body\":\"b\"}]}",
                "review JSON line comment has invalid line");
        assertInvalid(
                "{\"summary\":\"s\",\"verdict\":\"REQUEST_CHANGES\",\"lineComments\":[{\"file\":\"a\","
                        + "\"line\":5,\"type\":\"issue\",\"severity\":\"major\","
                        + "\"category\":\"correctness\",\"confidence\":\"low\",\"rationale\":\"r\",\"body\":\"b\"}]}",
                "review JSON cannot contain a low-confidence issue");
    }

    @Test
    void rejectsVerdictsThatDoNotMatchIssueComments() {
        assertInvalid(
                "{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[{\"file\":\"a\",\"line\":5,"
                        + "\"type\":\"issue\",\"severity\":\"major\",\"category\":\"correctness\","
                        + "\"confidence\":\"high\",\"rationale\":\"r\",\"body\":\"b\"}]}",
                "review verdict does not match issue comments");
    }

    private void assertInvalid(String raw, String expectedMessage) {
        ReviewParseResult result = parser.parse(raw);

        assertThat(result.valid()).isFalse();
        assertThat(result.review()).isNull();
        assertThat(result.error().code()).isEqualTo("invalid_review_json");
        assertThat(result.error().message()).isEqualTo(expectedMessage);
    }
}
