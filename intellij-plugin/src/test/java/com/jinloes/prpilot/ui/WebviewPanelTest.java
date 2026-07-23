package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.PullRequest;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import javax.swing.JPanel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewPanelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class WorktreeKey {

        @Test
        void normalizesOwnerAndRepoCase() {
            assertThat(WebviewPanel.worktreeKey(42, "JinLoes", "PR-Pilot"))
                    .isEqualTo("jinloes/pr-pilot#42");
        }

        @Test
        void keyIncludesPrNumber() {
            assertThat(WebviewPanel.worktreeKey(1, "a", "b"))
                    .isNotEqualTo(WebviewPanel.worktreeKey(2, "a", "b"));
        }
    }

    @Nested
    class IsSamePr {

        @Test
        void matchesByNumberAndRepoIgnoringCase() {
            PullRequest left = new PullRequest("t", "", "OwNeR", "RePo", 7, "", "a", "");
            PullRequest right = new PullRequest("t2", "", "owner", "repo", 7, "", "b", "");

            assertThat(WebviewPanel.isSamePr(left, right)).isTrue();
        }

        @Test
        void rejectsDifferentNumberOrRepo() {
            PullRequest base = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");
            PullRequest differentNumber = new PullRequest("t", "", "owner", "repo", 8, "", "a", "");
            PullRequest differentRepo = new PullRequest("t", "", "owner", "other", 7, "", "a", "");

            assertThat(WebviewPanel.isSamePr(base, differentNumber)).isFalse();
            assertThat(WebviewPanel.isSamePr(base, differentRepo)).isFalse();
        }

        @Test
        void returnsFalseWhenEitherIsNull() {
            PullRequest pr = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");

            assertThat(WebviewPanel.isSamePr(pr, null)).isFalse();
            assertThat(WebviewPanel.isSamePr(null, pr)).isFalse();
        }
    }

    @Nested
    class IsCurrentSession {

        @Test
        void acceptsTheActivePrAtTheSameRevision() {
            PullRequest pr = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");

            assertThat(WebviewPanel.isCurrentSession(pr, 3, pr, 3)).isTrue();
        }

        @Test
        void rejectsAnOlderRevisionOrDifferentPr() {
            PullRequest active = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");
            PullRequest previous = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");

            assertThat(WebviewPanel.isCurrentSession(active, 4, active, 3)).isFalse();
            assertThat(WebviewPanel.isCurrentSession(active, 4, previous, 4)).isFalse();
        }
    }

    @Nested
    class MatchesPrRequest {

        @Test
        void matchesByIdentityFieldsIgnoringCase() {
            PullRequest pr = new PullRequest("t", "", "OwNeR", "RePo", 7, "", "a", "");

            assertThat(WebviewPanel.matchesPrRequest(pr, 7, "owner", "repo")).isTrue();
        }

        @Test
        void rejectsNullAndFieldMismatches() {
            PullRequest pr = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");

            assertThat(WebviewPanel.matchesPrRequest(null, 7, "owner", "repo")).isFalse();
            assertThat(WebviewPanel.matchesPrRequest(pr, 8, "owner", "repo")).isFalse();
            assertThat(WebviewPanel.matchesPrRequest(pr, 7, "other", "repo")).isFalse();
            assertThat(WebviewPanel.matchesPrRequest(pr, 7, "owner", "other")).isFalse();
        }
    }

    @Nested
    class ResolveResourcePath {

        @Test
        void rootMapsToIndexHtml() {
            assertThat(WebviewPanel.resolveResourcePath("/")).isEqualTo("/webview/index.html");
        }

        @Test
        void normalAssetIsAllowed() {
            assertThat(WebviewPanel.resolveResourcePath("/assets/index.js"))
                    .isEqualTo("/webview/assets/index.js");
        }

        @Test
        void parentSegmentIsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("/../META-INF/plugin.xml")).isNull();
        }

        @Test
        void nestedTraversalIsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("/assets/../../etc/passwd")).isNull();
        }

        @Test
        void pathThatNormalizesBackInsideWebviewIsAllowed() {
            assertThat(WebviewPanel.resolveResourcePath("/assets/../index.html"))
                    .isEqualTo("/webview/index.html");
        }

        @Test
        void pathWithoutLeadingSlashIsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("index.html")).isNull();
        }

        @Test
        void blankPathIsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("")).isNull();
            assertThat(WebviewPanel.resolveResourcePath(null)).isNull();
        }

        @Test
        void multipleParentSegmentsRejected() {
            assertThat(WebviewPanel.resolveResourcePath("/../../foo")).isNull();
        }
    }

    @Nested
    class IncomingMessageValidation {

        @Test
        void acceptsKnownMessageWithValidPrIdentity() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"generateReview\",\"number\":7,\"owner\":\"acme\",\"repo\":\"platform\",\"diff\":\"diff --git a/a b/a\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isTrue();
        }

        @Test
        void rejectsInvalidOrOversizedReviewDiff() throws Exception {
            var nonText =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"generateReview\",\"number\":7,\"owner\":\"acme\",\"repo\":\"platform\",\"diff\":42}");
            var oversized =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "generateReview")
                            .put("number", 7)
                            .put("owner", "acme")
                            .put("repo", "platform")
                            .put("diff", "x".repeat(1_100_001));

            assertThat(WebviewPanel.isValidIncomingMessage(nonText)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(oversized)).isFalse();
        }

        @Test
        void rejectsPrMessageWithoutOwnerRepoOrNumber() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"selectPR\",\"number\":0,\"owner\":\"\",\"repo\":\"\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void rejectsUnknownMessageType() throws Exception {
            var node = MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"surprise\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void acceptsRunAuthLoginMessage() throws Exception {
            var node = MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"runAuthLogin\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isTrue();
        }

        @Test
        void validatesWebviewLayoutChangedReason() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\",\"reason\":\"chat-panel\"}");
            var missing =
                    MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\"}");
            var nonText =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\",\"reason\":42}");
            var oversized =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "webviewLayoutChanged")
                            .put("reason", "x".repeat(4_097));

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(missing)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(nonText)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(oversized)).isFalse();
        }

        @Test
        void rejectsMalformedNestedReview() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"result\":{"
                                    + "\"summary\":\"s\",\"verdict\":\"INVALID\",\"lineComments\":[]}}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void validatesRefreshCompatibilityBooleans() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"refreshPRs\",\"assignedToMe\":true,\"reviewRequested\":false}");
            var invalid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"refreshPRs\",\"assignedToMe\":\"yes\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(invalid)).isFalse();
        }

        @Test
        void rejectsOversizedPrIdentity() {
            var node =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "selectPR")
                            .put("number", 7)
                            .put("owner", "x".repeat(257))
                            .put("repo", "platform");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void rejectsInvalidRichCommentMetadata() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"result\":{"
                                    + "\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[{"
                                    + "\"file\":\"a.java\",\"line\":1,\"type\":\"note\",\"body\":\"b\","
                                    + "\"severity\":\"urgent\"}]}}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }
    }

    @Nested
    class DraftLoadedSerialization {

        @Test
        void omitsAbsentOptionalFieldsFromNoDraftMessage() {
            var message =
                    new WebviewPanel.DraftLoadedMsg(
                            "draftLoaded",
                            "acme/platform#42",
                            "NO_DRAFT",
                            null,
                            null,
                            null,
                            "diff",
                            false,
                            false,
                            "",
                            new WebviewPanel.ProviderReadinessDto("claude", true, "Ready"));

            var json = MAPPER.valueToTree(message);

            assertThat(json.has("reviewId")).isFalse();
            assertThat(json.has("result")).isFalse();
            assertThat(json.has("diff")).isFalse();
            assertThat(json.path("validationDiff").asText()).isEqualTo("diff");
        }

        @Test
        void omitsAbsentOptionalFieldsFromMergedMessage() {
            var message =
                    new WebviewPanel.DraftLoadedMsg(
                            "draftLoaded",
                            "acme/platform#42",
                            "MERGED",
                            null,
                            null,
                            null,
                            null,
                            false,
                            false,
                            "PR is merged.",
                            new WebviewPanel.ProviderReadinessDto("copilot", true, "Ready"));

            var json = MAPPER.valueToTree(message);

            assertThat(json.has("reviewId")).isFalse();
            assertThat(json.has("result")).isFalse();
            assertThat(json.has("diff")).isFalse();
            assertThat(json.has("validationDiff")).isFalse();
        }
    }

    @Nested
    class BrowserHostLayout {

        @Test
        void browserTracksSuccessiveToolWindowSizes() {
            JPanel parent = new JPanel(new BorderLayout());
            JPanel browser = new JPanel();
            JPanel host = WebviewPanel.createBrowserHostPanel(browser);
            parent.add(host, BorderLayout.CENTER);

            layoutAt(parent, host, 640, 160);
            assertThat(host.getBounds()).isEqualTo(new Rectangle(0, 0, 640, 160));
            assertThat(browser.getBounds()).isEqualTo(new Rectangle(0, 0, 640, 160));

            layoutAt(parent, host, 640, 800);
            assertThat(host.getBounds()).isEqualTo(new Rectangle(0, 0, 640, 800));
            assertThat(browser.getBounds()).isEqualTo(new Rectangle(0, 0, 640, 800));
        }

        private static void layoutAt(JPanel parent, JPanel host, int width, int height) {
            parent.setSize(width, height);
            parent.doLayout();
            host.doLayout();
        }
    }
}
