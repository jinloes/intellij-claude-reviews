package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubApiClient;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CheckRunServiceTest {

    private static final String SHA = "a".repeat(40);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class ResultSnapshots {

        @Test
        void copiesCallerOwnedListsAndExposesImmutableAccessors() {
            List<CheckRunSummary> checkRuns =
                    new ArrayList<>(
                            List.of(new CheckRunSummary("build", "completed", "success", "")));
            List<CheckAnnotation> annotations =
                    new ArrayList<>(
                            List.of(
                                    new CheckAnnotation(
                                            "src/Main.java", 1, 1, "warning", "message")));

            CheckStatusResult result =
                    new CheckStatusResult("ok", "loaded", "complete", checkRuns, annotations, "");
            checkRuns.clear();
            annotations.clear();

            assertThat(result.checkRuns()).hasSize(1);
            assertThat(result.annotations()).hasSize(1);
            assertThatThrownBy(() -> result.checkRuns().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.annotations().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void normalizesNullListsToEmpty() {
            CheckStatusResult result =
                    new CheckStatusResult("api_failed", "failed", "none", null, null, "");

            assertThat(result.checkRuns()).isEmpty();
            assertThat(result.annotations()).isEmpty();
        }
    }

    private static CheckRunService service(FakeClient client) {
        return new CheckRunService(
                hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                client,
                new ObjectMapper());
    }

    private static CheckRunService.Params params() {
        return new CheckRunService.Params("https://github.com", "acme", "widgets", SHA);
    }

    private static GitHubResponse ok(String body) {
        return new GitHubResponse(200, body);
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode checkRun(String name, String status, String conclusion, long id) {
        return MAPPER.createObjectNode()
                .put("id", id)
                .put("name", name)
                .put("status", status)
                .put("conclusion", conclusion)
                .set(
                        "output",
                        MAPPER.createObjectNode()
                                .put("title", "3 failed")
                                .put("summary", "see log"));
    }

    /** A completed check run whose output advertises {@code annotations_count}. */
    private static ObjectNode checkRunWithAnnotations(
            String name, String conclusion, long id, int annotationsCount) {
        return MAPPER.createObjectNode()
                .put("id", id)
                .put("name", name)
                .put("status", "completed")
                .put("conclusion", conclusion)
                .set(
                        "output",
                        MAPPER.createObjectNode()
                                .put("title", "analysis")
                                .put("summary", "see report")
                                .put("annotations_count", annotationsCount));
    }

    private static String checkRunsPayload(ObjectNode... runs) {
        return json(MAPPER.createObjectNode().set("check_runs", arrayOf(runs)));
    }

    private static ObjectNode annotation(
            String path, int startLine, int endLine, String level, String message) {
        return MAPPER.createObjectNode()
                .put("path", path)
                .put("start_line", startLine)
                .put("end_line", endLine)
                .put("annotation_level", level)
                .put("message", message);
    }

    private static String annotationsPayload(ObjectNode... annotations) {
        return json(arrayOf(annotations));
    }

    private static ObjectNode statusEntry(String context, String state, String description) {
        return MAPPER.createObjectNode()
                .put("context", context)
                .put("state", state)
                .put("description", description);
    }

    private static String statusesPayload(ObjectNode... statuses) {
        return json(MAPPER.createObjectNode().set("statuses", arrayOf(statuses)));
    }

    private static ArrayNode arrayOf(ObjectNode... nodes) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ObjectNode node : nodes) {
            array.add(node);
        }
        return array;
    }

    @Nested
    class CheckStatus {
        @Test
        void reportsFailingChecksWithTheirAnnotations() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(checkRunsPayload(checkRun("build", "completed", "failure", 7))));
            client.responses.add(
                    ok(
                            annotationsPayload(
                                    annotation(
                                            "src/Foo.java",
                                            42,
                                            42,
                                            "failure",
                                            "expected true but was false"))));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.state()).isEqualTo("complete");
            assertThat(result.checkRuns())
                    .singleElement()
                    .returns(true, CheckRunSummary::isFailing);
            assertThat(result.annotations())
                    .singleElement()
                    .returns("src/Foo.java:42", CheckAnnotation::location);
            assertThat(result.summary()).contains("FAILING: build", "expected true but was false");
        }

        @Test
        void tellsTheModelExplicitlyThatPendingChecksAreNotKnownToPass() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(
                            checkRunsPayload(
                                    checkRun("lint", "in_progress", null, 1),
                                    checkRun("test", "completed", "success", 2))));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.state()).isEqualTo("in_progress");
            assertThat(result.summary())
                    .contains("1 still running", "do not assume", "STILL RUNNING: lint");
        }

        @Test
        void doesNotSpendRequestsOnAnnotationsForPassingChecks() {
            FakeClient client = new FakeClient();
            client.responses.add(ok(checkRunsPayload(checkRun("test", "completed", "success", 2))));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.annotations()).isEmpty();
            assertThat(client.paths).hasSize(1);
            assertThat(result.summary()).startsWith("0 of 1 checks failing.");
        }

        @Test
        void readsAnnotationsFromAnAdvisoryStaticAnalysisCheckThatPassed() {
            // Qodana/CodeQL/ktlint are commonly advisory: they conclude "success" while still
            // reporting file-anchored findings. Those are the most review-shaped evidence CI
            // produces, so a passing conclusion must not discard them.
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(checkRunsPayload(checkRunWithAnnotations("Qodana", "success", 11, 2))));
            client.responses.add(
                    ok(
                            annotationsPayload(
                                    annotation("src/A.java", 4, 4, "warning", "unused import"))));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.annotations()).hasSize(1);
            assertThat(result.annotations().get(0).message()).isEqualTo("unused import");
            assertThat(result.summary()).contains("CI reported these specific locations");
        }

        @Test
        void readsAnnotationsFromANeutralCheck() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(checkRunsPayload(checkRunWithAnnotations("lint", "neutral", 12, 1))));
            client.responses.add(
                    ok(annotationsPayload(annotation("src/B.java", 9, 9, "notice", "style"))));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.annotations()).hasSize(1);
        }

        @Test
        void stillProbesAFailingCheckThatReportsNoAnnotationCount() {
            // Preserves pre-existing behavior: the annotations_count test is a union with
            // isFailing(), not a replacement, so a provider that omits the field does not regress.
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(checkRunsPayload(checkRun("build", "completed", "failure", 7))));
            client.responses.add(
                    ok(annotationsPayload(annotation("src/C.java", 1, 1, "failure", "boom"))));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.annotations()).hasSize(1);
            assertThat(client.paths).hasSize(2);
        }

        @Test
        void prioritizesFailingChecksOverAdvisoryOnesForTheAnnotationBudget() {
            // More annotated checks than MAX_ANNOTATED_CHECKS: the failing one must not be
            // crowded out by advisory lint checks listed ahead of it.
            ArrayNode runs = MAPPER.createArrayNode();
            for (int i = 0; i < CheckRunService.MAX_ANNOTATED_CHECKS; i++) {
                runs.add(checkRunWithAnnotations("lint-" + i, "success", 100 + i, 1));
            }
            runs.add(checkRunWithAnnotations("build", "failure", 999, 1));

            FakeClient client = new FakeClient();
            client.responses.add(ok(json(MAPPER.createObjectNode().set("check_runs", runs))));
            for (int i = 0; i < CheckRunService.MAX_ANNOTATED_CHECKS; i++) {
                client.responses.add(
                        ok(annotationsPayload(annotation("src/X.java", 1, 1, "warning", "m"))));
            }

            CheckStatusResult result = service(client).checkStatus(params());

            // The failing check's annotations were requested first.
            assertThat(client.paths.get(1)).contains("/check-runs/999/annotations");
            assertThat(result.summary()).startsWith("1 of 6 checks failing.");
        }

        @Test
        void fallsBackToTheLegacyStatusApiWhenNoCheckRunsExist() {
            FakeClient client = new FakeClient();
            client.responses.add(ok(checkRunsPayload()));
            client.responses.add(
                    ok(statusesPayload(statusEntry("jenkins", "failure", "build broke"))));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(client.paths.get(1)).endsWith("/commits/" + SHA + "/status");
            assertThat(result.state()).isEqualTo("complete");
            assertThat(result.summary()).contains("FAILING: jenkins", "build broke");
        }

        @Test
        void reportsNoCiRatherThanAnErrorWhenNothingIsConfigured() {
            FakeClient client = new FakeClient();
            client.responses.add(ok(checkRunsPayload()));
            client.responses.add(ok(statusesPayload()));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.state()).isEqualTo("none");
            assertThat(result.summary()).isEmpty();
        }

        @Test
        void keepsCheckConclusionsWhenAnnotationLookupFails() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(checkRunsPayload(checkRun("build", "completed", "failure", 7))));
            client.responses.add(new GitHubResponse(500, ""));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.checkRuns()).hasSize(1);
            assertThat(result.annotations()).isEmpty();
        }

        @Test
        void boundsAnnotationCountAndMessageLength() {
            ArrayNode annotations = MAPPER.createArrayNode();
            for (int i = 0; i < 40; i++) {
                annotations.add(annotation("F.java", 1, 1, "failure", "x".repeat(500)));
            }
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(checkRunsPayload(checkRun("build", "completed", "failure", 7))));
            client.responses.add(ok(json(annotations)));

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.annotations()).hasSize(CheckRunService.MAX_ANNOTATIONS);
            assertThat(result.annotations().get(0).message().length())
                    .isLessThanOrEqualTo(CheckRunService.MAX_MESSAGE_CHARS + 1);
        }

        @Test
        void rejectsInvalidIdentityBeforeResolvingCredentials() {
            int[] tokenCalls = {0};
            CheckRunService service =
                    new CheckRunService(
                            hostname -> {
                                tokenCalls[0]++;
                                return GitHubAuthService.TokenResolution.resolved("t");
                            },
                            new FakeClient(),
                            new ObjectMapper());

            assertThat(
                            service.checkStatus(
                                            new CheckRunService.Params(
                                                    "https://github.com", "../etc", "widgets", SHA))
                                    .status())
                    .isEqualTo("invalid_request");
            assertThat(
                            service.checkStatus(
                                            new CheckRunService.Params(
                                                    "https://github.com",
                                                    "acme",
                                                    "widgets",
                                                    "main"))
                                    .status())
                    .isEqualTo("invalid_request");
            assertThat(tokenCalls[0]).isZero();
        }

        @Test
        void surfacesTransportFailuresWithoutLeakingTheToken() {
            FakeClient client = new FakeClient();
            client.responses.add(GitHubResponse.networkError());

            CheckStatusResult result = service(client).checkStatus(params());

            assertThat(result.status()).isEqualTo("network_error");
            assertThat(result.state()).isEqualTo("none");
            assertThat(result.toString()).doesNotContain("secret-token");
        }

        @Test
        void rejectsAMalformedCheckRunsPayload() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(
                            json(
                                    MAPPER.createObjectNode()
                                            .set("check_runs", MAPPER.createObjectNode()))));

            assertThat(service(client).checkStatus(params()).status()).isEqualTo("api_failed");
        }
    }

    @Nested
    class Render {
        @Test
        void omitsTheLocationsBlockWhenThereAreNoAnnotations() {
            String rendered =
                    CheckRunService.render(
                            "complete",
                            List.of(new CheckRunSummary("t", "completed", "success", "")),
                            List.of());

            assertThat(rendered).doesNotContain("specific locations");
        }

        @Test
        void reportsAnnotationsWithoutLineNumbersByPathAlone() {
            String rendered =
                    CheckRunService.render(
                            "complete",
                            List.of(new CheckRunSummary("t", "completed", "failure", "")),
                            List.of(new CheckAnnotation("F.java", 0, 0, "warning", "hmm")));

            assertThat(rendered).contains("[warning] F.java: hmm").doesNotContain("F.java:0");
        }
    }

    private static final class FakeClient implements GitHubApiClient {
        private final Deque<GitHubResponse> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();

        @Override
        public GitHubResponse get(String apiBaseUrl, String token, String path) {
            paths.add(path);
            GitHubResponse response = responses.poll();
            return response == null ? new GitHubResponse(404, "") : response;
        }
    }
}
