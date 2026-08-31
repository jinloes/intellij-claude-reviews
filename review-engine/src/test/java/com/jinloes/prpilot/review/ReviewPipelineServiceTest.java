package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewPipelineServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    class Review {
        @Test
        void disabledSupervisorPreservesSinglePassBehavior() throws Exception {
            FakeProvider provider = new FakeProvider();
            ReviewResult baseline = new ReviewResult("baseline", "APPROVE", List.of());
            provider.primaryResult = ReviewPassResult.withoutLedger(baseline);
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(
                            request(oneRiskyHunk()), false, false, false, ignored -> {}, null);

            assertThat(result).isSameAs(baseline);
            assertThat(provider.completeCalls).isEmpty();
        }

        @Test
        void followsUpOnAnUninspectedHighRiskHunkAndMergesTheFinding() throws Exception {
            String diff = oneRiskyHunk();
            InspectionManifest manifest = InspectionManifest.fromDiff(diff);
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
            provider.completions.add(reviewJsonWithFinding("src/Api.java", 1));
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(request(diff), false, false, true, ignored -> {}, null);

            assertThat(result.getSummary()).isEqualTo("baseline");
            assertThat(result.getVerdict()).isEqualTo("REQUEST_CHANGES");
            assertThat(result.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getFile)
                    .isEqualTo("src/Api.java");
            assertThat(provider.completeCalls)
                    .singleElement()
                    .satisfies(
                            call -> {
                                assertThat(call.allowReadTools()).isTrue();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(6L * 60L * 1000L);
                                assertThat(call.prompt())
                                        .contains(manifest.files().get(0).hunks().get(0).id());
                            });
        }

        @Test
        void cleanLowRiskControlDoesNotTriggerAnAdditionalProviderCall() throws Exception {
            FakeProvider provider = new FakeProvider();
            ReviewResult baseline = new ReviewResult("baseline", "APPROVE", List.of());
            provider.primaryResult =
                    new ReviewPassResult(baseline, new InspectionLedger(true, Set.of(), List.of()));
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());
            String diff =
                    """
                    diff --git a/src/Formatting.java b/src/Formatting.java
                    --- a/src/Formatting.java
                    +++ b/src/Formatting.java
                    @@ -1 +1 @@
                    -int spacing = 1;
                    +int spacing = 2;
                    """;

            ReviewResult result =
                    pipeline.review(request(diff), false, false, true, ignored -> {}, null);

            assertThat(result).isSameAs(baseline);
            assertThat(provider.completeCalls).isEmpty();
        }

        @Test
        void usesOneToolFreePrioritizationCallBeforeOneFollowUpWhenMoreThanThreeGapsExist()
                throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    new ReviewPassResult(
                            new ReviewResult("baseline", "APPROVE", List.of()),
                            new InspectionLedger(true, Set.of(), List.of()));
            provider.completions.add(
                    JSON.writeValueAsString(Map.of("selectedGapIds", List.of("G004", "G002"))));
            provider.completions.add(emptyReviewJson());
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            pipeline.review(request(fourRiskyHunks()), false, false, true, ignored -> {}, null);

            assertThat(provider.completeCalls).hasSize(2);
            assertThat(provider.completeCalls.get(0))
                    .satisfies(
                            call -> {
                                assertThat(call.allowReadTools()).isFalse();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(90_000);
                            });
            assertThat(provider.completeCalls.get(1))
                    .satisfies(
                            call -> {
                                assertThat(call.allowReadTools()).isTrue();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(6L * 60L * 1000L);
                            });
        }

        @Test
        void keepsTheBaselineWhenTheTargetedFollowUpFails() throws Exception {
            FakeProvider provider = new FakeProvider();
            ReviewResult baseline = new ReviewResult("baseline", "APPROVE", List.of());
            provider.primaryResult =
                    new ReviewPassResult(baseline, new InspectionLedger(true, Set.of(), List.of()));
            provider.completionFailure = new IOException("follow-up unavailable");
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            ReviewResult result =
                    pipeline.review(
                            request(oneRiskyHunk()), false, false, true, ignored -> {}, null);

            assertThat(result).isSameAs(baseline);
        }

        @Test
        void runsFinalCritiqueOnlyOnceAfterChunkReconciliation() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    ReviewPassResult.withoutLedger(
                            new ReviewResult("primary", "APPROVE", List.of()));
            provider.completions.add(emptyReviewJson());
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            pipeline.review(request(sevenFileDiff()), true, true, false, ignored -> {}, null);

            assertThat(provider.primaryCalls).hasValue(3);
            assertThat(provider.completeCalls)
                    .singleElement()
                    .extracting(PromptCall::prompt)
                    .asString()
                    .contains(
                            "<draft_review>", "Changed files and contract-relevant changed lines.")
                    .doesNotContain("diff --git");
        }

        @Test
        void propagatesCancellationInsteadOfReturningFallbackSuccess() {
            FakeProvider provider = new FakeProvider();
            provider.primaryResult =
                    ReviewPassResult.withoutLedger(
                            new ReviewResult("primary", "APPROVE", List.of()));
            provider.cancelAfterPrimary = true;
            ReviewPipelineService pipeline =
                    new ReviewPipelineService(
                            provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());

            assertThatThrownBy(
                            () ->
                                    pipeline.review(
                                            request(oneRiskyHunk()),
                                            false,
                                            false,
                                            true,
                                            ignored -> {},
                                            null))
                    .isInstanceOf(InterruptedException.class);
        }
    }

    private static final class FakeProvider implements ReviewPipelineService.ProviderExecutor {
        private ReviewPassResult primaryResult;
        private final AtomicInteger primaryCalls = new AtomicInteger();
        private final List<String> completions = new ArrayList<>();
        private final List<PromptCall> completeCalls = new ArrayList<>();
        private IOException completionFailure;
        private boolean cancelAfterPrimary;

        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk) {
            primaryCalls.incrementAndGet();
            return primaryResult;
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException {
            completeCalls.add(new PromptCall(prompt, timeoutMillis, allowReadTools, allowMcp));
            if (completionFailure != null) {
                throw completionFailure;
            }
            return completions.remove(0);
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            if (cancelAfterPrimary && primaryCalls.get() > 0) {
                throw new InterruptedException("cancelled");
            }
        }
    }

    private record PromptCall(
            String prompt, long timeoutMillis, boolean allowReadTools, boolean allowMcp) {}

    private static PRReviewRequest request(String diff) {
        PullRequest pr =
                new PullRequest(
                        "Change API",
                        "https://example.test/pr/1",
                        "acme",
                        "repo",
                        1,
                        "",
                        "author",
                        "",
                        false);
        return PRReviewRequest.builder(pr, diff).build();
    }

    private static String reviewJsonWithFinding(String file, int line) throws Exception {
        return JSON.writeValueAsString(
                Map.of(
                        "summary",
                        "follow-up",
                        "verdict",
                        "REQUEST_CHANGES",
                        "lineComments",
                        List.of(
                                Map.of(
                                        "file", file,
                                        "line", line,
                                        "type", "issue",
                                        "severity", "major",
                                        "category", "correctness",
                                        "confidence", "high",
                                        "body", "The changed contract breaks its caller.",
                                        "rationale",
                                                "The new signature no longer accepts the required value."))));
    }

    private static String emptyReviewJson() throws Exception {
        return JSON.writeValueAsString(
                Map.of(
                        "summary", "reviewed",
                        "verdict", "APPROVE",
                        "lineComments", List.of()));
    }

    private static String oneRiskyHunk() {
        return """
                diff --git a/src/Api.java b/src/Api.java
                --- a/src/Api.java
                +++ b/src/Api.java
                @@ -1 +1 @@
                -private void call(String value) {}
                +public void call() {}
                """;
    }

    private static String fourRiskyHunks() {
        return """
                diff --git a/src/Api.java b/src/Api.java
                --- a/src/Api.java
                +++ b/src/Api.java
                @@ -1 +1 @@
                -private void one(String value) {}
                +public void one() {}
                @@ -10 +10 @@
                -private void two(String value) {}
                +public void two() {}
                @@ -20 +20 @@
                -private void three(String value) {}
                +public void three() {}
                @@ -30 +30 @@
                -private void four(String value) {}
                +public void four() {}
                """;
    }

    private static String sevenFileDiff() {
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            diff.append("diff --git a/F")
                    .append(index)
                    .append(".java b/F")
                    .append(index)
                    .append(".java\n")
                    .append("--- a/F")
                    .append(index)
                    .append(".java\n")
                    .append("+++ b/F")
                    .append(index)
                    .append(".java\n")
                    .append("@@ -1 +1 @@\n-old\n+new\n");
        }
        return diff.toString();
    }
}
