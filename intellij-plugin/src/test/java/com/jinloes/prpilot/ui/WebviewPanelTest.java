package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.sidecar.pr.PrDetail;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.io.File;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewPanelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class HealthyDraftEntries {

        @Test
        void keepsHealthyEmptyStateDistinctFromUnavailableState() {
            var healthy =
                    WebviewPanel.healthyDraftEntries(
                            new PendingReviewIndex.LoadResult(List.of(), null));
            var unavailable =
                    WebviewPanel.healthyDraftEntries(
                            new PendingReviewIndex.LoadResult(List.of(), "corrupt"));

            assertThat(healthy).isPresent();
            assertThat(healthy.orElseThrow()).isEmpty();
            assertThat(unavailable).isEmpty();
        }
    }

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
    class IsCurrentSelection {

        @Test
        void acceptsHydratedInstanceAtCapturedRevision() {
            PullRequest hydrated =
                    new PullRequest("detail", "", "acme", "platform", 7, "body", "a", "");

            assertThat(WebviewPanel.isCurrentSelection(hydrated, 4, "acme/platform#7", 4)).isTrue();
        }

        @Test
        void rejectsSamePrReselectedAtNewerRevision() {
            PullRequest reselected =
                    new PullRequest("detail", "", "acme", "platform", 7, "body", "a", "");

            assertThat(WebviewPanel.isCurrentSelection(reselected, 5, "acme/platform#7", 4))
                    .isFalse();
        }
    }

    @Nested
    class IsCurrentChat {

        private final PullRequest pr =
                new PullRequest("title", "", "acme", "platform", 7, "", "a", "");

        @Test
        void acceptsMatchingSelectionAndChatId() {
            assertThat(WebviewPanel.isCurrentChat(pr, 3, 9, "acme/platform#7", 3, 9)).isTrue();
        }

        @Test
        void rejectsOlderChatOrSelection() {
            assertThat(WebviewPanel.isCurrentChat(pr, 3, 10, "acme/platform#7", 3, 9)).isFalse();
            assertThat(WebviewPanel.isCurrentChat(pr, 4, 9, "acme/platform#7", 3, 9)).isFalse();
        }
    }

    @Nested
    class WorktreeCoordinator {

        @Test
        void concurrentSameKeyAcquiresShareOneCreation() {
            WebviewPanel.WorktreeCoordinator<String> coordinator =
                    new WebviewPanel.WorktreeCoordinator<>();

            WebviewPanel.WorktreeLease<String> owner = coordinator.acquire("acme/repo#7");
            WebviewPanel.WorktreeLease<String> waiter = coordinator.acquire("acme/repo#7");

            assertThat(owner.owner()).isTrue();
            assertThat(waiter.owner()).isFalse();
            assertThat(waiter.future()).isSameAs(owner.future());
            assertThat(coordinator.install(owner, "worktree")).isTrue();
            assertThat(waiter.future()).isCompletedWithValue("worktree");
            assertThat(coordinator.activeValue()).isEqualTo("worktree");
        }

        @Test
        void clearDuringCreationRejectsLateInstallAndFailsWaiters() {
            WebviewPanel.WorktreeCoordinator<String> coordinator =
                    new WebviewPanel.WorktreeCoordinator<>();
            WebviewPanel.WorktreeLease<String> owner = coordinator.acquire("acme/repo#7");
            WebviewPanel.WorktreeLease<String> waiter = coordinator.acquire("acme/repo#7");

            assertThat(coordinator.clear()).isNull();
            assertThat(waiter.future()).isCompletedExceptionally();

            assertThat(coordinator.install(owner, "stale-worktree")).isFalse();
            assertThat(waiter.future()).isCompletedExceptionally();
            assertThat(coordinator.activeValue()).isNull();
        }

        @Test
        void clearReturnsInstalledValueAndStartsNewEpoch() {
            WebviewPanel.WorktreeCoordinator<String> coordinator =
                    new WebviewPanel.WorktreeCoordinator<>();
            WebviewPanel.WorktreeLease<String> first = coordinator.acquire("acme/repo#7");
            coordinator.install(first, "worktree");

            assertThat(coordinator.clear()).isEqualTo("worktree");
            WebviewPanel.WorktreeLease<String> next = coordinator.acquire("acme/repo#7");
            assertThat(next.owner()).isTrue();
            assertThat(next.future()).isNotSameAs(first.future());
        }

        @Test
        void failedCreationReleasesKeyForRetry() {
            WebviewPanel.WorktreeCoordinator<String> coordinator =
                    new WebviewPanel.WorktreeCoordinator<>();
            WebviewPanel.WorktreeLease<String> failed = coordinator.acquire("acme/repo#7");

            coordinator.fail(failed);

            assertThat(failed.future()).isCompletedExceptionally();
            assertThat(coordinator.acquire("acme/repo#7").owner()).isTrue();
        }
    }

    @Nested
    class OperationServiceOwnership {

        @Test
        void createsDistinctProviderOwnersForTheSameWorktree() {
            File worktree = new File("/tmp/pr-pilot-test-worktree");

            IntellijClaudeService first = WebviewPanel.serviceForWorktree(worktree);
            IntellijClaudeService second = WebviewPanel.serviceForWorktree(worktree);

            assertThat(second).isNotSameAs(first);
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
    class HydratePullRequest {

        @Test
        void replacesDetailFieldsAndPreservesListMetadata() {
            PullRequest summary =
                    new PullRequest(
                            "List title",
                            "https://github.com/acme/platform/pull/7",
                            "acme",
                            "platform",
                            7,
                            "",
                            "octocat",
                            "2026-07-30T12:00:00Z",
                            true);
            PrDetail detail =
                    new PrDetail(
                            false,
                            "Detailed title",
                            "Closes #42",
                            new PrDetail.Head("sha", "branch", "acme/platform", "clone"),
                            "acme/platform");

            PullRequest hydrated = WebviewPanel.hydratePullRequest(summary, detail);

            assertThat(hydrated.getTitle()).isEqualTo("Detailed title");
            assertThat(hydrated.getBody()).isEqualTo("Closes #42");
            assertThat(hydrated.getHtmlUrl()).isEqualTo(summary.getHtmlUrl());
            assertThat(hydrated.getOwner()).isEqualTo("acme");
            assertThat(hydrated.getRepo()).isEqualTo("platform");
            assertThat(hydrated.getNumber()).isEqualTo(7);
            assertThat(hydrated.getAuthor()).isEqualTo("octocat");
            assertThat(hydrated.getCreatedAt()).isEqualTo("2026-07-30T12:00:00Z");
            assertThat(hydrated.isDraft()).isTrue();
        }

        @Test
        void returnsSummaryWhenDetailReadFailed() {
            PullRequest summary =
                    new PullRequest("Title", "", "acme", "platform", 7, "", "octocat", "");

            assertThat(WebviewPanel.hydratePullRequest(summary, null)).isSameAs(summary);
        }
    }

    @Nested
    class CanPersistDraft {
        @Test
        void activePrMayUseHostState() {
            assertThat(WebviewPanel.canPersistDraft(true, false)).isTrue();
        }

        @Test
        void outgoingPrRequiresExplicitResult() {
            assertThat(WebviewPanel.canPersistDraft(false, true)).isTrue();
            assertThat(WebviewPanel.canPersistDraft(false, false)).isFalse();
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
                            "{\"protocolVersion\":1,\"type\":\"generateReview\",\"operationId\":\"review-1\",\"number\":7,\"owner\":\"acme\",\"repo\":\"platform\",\"diff\":\"diff --git a/a b/a\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isTrue();
        }

        @Test
        void rejectsInvalidOrOversizedReviewDiff() throws Exception {
            var nonText =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"generateReview\",\"operationId\":\"review-1\",\"number\":7,\"owner\":\"acme\",\"repo\":\"platform\",\"diff\":42}");
            var oversized =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "generateReview")
                            .put("operationId", "review-1")
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
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"result\":{"
                                    + "\"summary\":\"s\",\"verdict\":\"INVALID\",\"lineComments\":[]}}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void validatesGeneratedReviewBaseline() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"generatedResult\":{"
                                    + "\"summary\":\"generated\",\"verdict\":\"COMMENT\",\"lineComments\":[]}}");
            var invalid = (ObjectNode) valid.deepCopy();
            ((ObjectNode) invalid.path("generatedResult")).put("verdict", "INVALID");

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(invalid)).isFalse();
        }

        @Test
        void rejectsDraftSaveWithoutPositiveCorrelationId() throws Exception {
            var missing =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,"
                                    + "\"owner\":\"acme\",\"repo\":\"widget\"}");
            var zero = ((ObjectNode) missing.deepCopy()).put("saveId", 0);
            var valid = ((ObjectNode) missing.deepCopy()).put("saveId", 1);

            assertThat(WebviewPanel.isValidIncomingMessage(missing)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(zero)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
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
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
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
        void includesBoundedAndValidationDiffsInNoDraftMessage() {
            var message =
                    new WebviewPanel.DraftLoadedMsg(
                            "draftLoaded",
                            "acme/platform#42",
                            "NO_DRAFT",
                            null,
                            null,
                            "bounded diff",
                            "full validation diff",
                            false,
                            false,
                            "",
                            new WebviewPanel.ProviderReadinessDto("claude", true, "Ready"));

            var json = MAPPER.valueToTree(message);

            assertThat(json.has("reviewId")).isFalse();
            assertThat(json.has("result")).isFalse();
            assertThat(json.path("diff").asText()).isEqualTo("bounded diff");
            assertThat(json.path("validationDiff").asText()).isEqualTo("full validation diff");
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
    class GenerationMetadata {

        @Test
        void capturesGenerationTimeProviderAndModel() {
            var metadata =
                    WebviewPanel.generationMetadata(ReviewProvider.COPILOT, "claude-sonnet-4.6");

            assertThat(metadata.promptVersion()).isNotBlank();
            assertThat(metadata.provider()).isEqualTo("copilot");
            assertThat(metadata.model()).isEqualTo("claude-sonnet-4.6");
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
