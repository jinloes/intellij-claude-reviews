package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewPassParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    class Parse {
        @Test
        void keepsOnlyManifestTargetsAndConfinedRelatedPaths() throws Exception {
            InspectionManifest manifest = InspectionManifest.fromDiff(diff());
            String hunkId = manifest.files().get(0).hunks().get(0).id();
            String raw =
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "summary",
                                    "verdict",
                                    "REQUEST_CHANGES",
                                    "lineComments",
                                    List.of(comment()),
                                    "inspection",
                                    Map.of(
                                            "inspectedTargets",
                                            List.of(hunkId, "H-invented"),
                                            "evidence",
                                            List.of(
                                                    Map.of(
                                                            "findingIndex",
                                                            0,
                                                            "targetIds",
                                                            List.of(hunkId, "H-invented"),
                                                            "relatedFiles",
                                                            List.of(
                                                                    "src/Caller.java",
                                                                    "../../secret"))))));

            ReviewPassResult result = ReviewPassParser.parse(raw, manifest, null);

            assertThat(result.ledger().reported()).isTrue();
            assertThat(result.ledger().inspectedTargetIds()).containsExactly(hunkId);
            assertThat(result.ledger().evidence())
                    .singleElement()
                    .satisfies(
                            evidence -> {
                                assertThat(evidence.targetIds()).containsExactly(hunkId);
                                assertThat(evidence.relatedFiles())
                                        .containsExactly("src/Caller.java");
                            });
        }

        @Test
        void distinguishesAnOmittedLedgerFromAReportedEmptyLedger() throws Exception {
            String withoutLedger =
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "summary",
                                    "verdict",
                                    "APPROVE",
                                    "lineComments",
                                    List.of()));
            String emptyLedger =
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "summary",
                                    "verdict",
                                    "APPROVE",
                                    "lineComments",
                                    List.of(),
                                    "inspection",
                                    Map.of(
                                            "inspectedTargets", List.of(),
                                            "evidence", List.of())));
            InspectionManifest manifest = InspectionManifest.fromDiff(diff());

            assertThat(ReviewPassParser.parse(withoutLedger, manifest, null).ledger().reported())
                    .isFalse();
            assertThat(ReviewPassParser.parse(emptyLedger, manifest, null).ledger().reported())
                    .isTrue();
        }

        @Test
        void rejectsEvidenceThatNamesTheWrongValidHunk() throws Exception {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/Auth.java b/src/Auth.java
                            --- a/src/Auth.java
                            +++ b/src/Auth.java
                            @@ -1 +1 @@
                            -private void check() {}
                            +public void check() {}
                            @@ -10 +10 @@
                            -private void authorize() {}
                            +public void authorize() {}
                            """);
            String wrongHunkId = manifest.files().get(0).hunks().get(1).id();
            String raw =
                    JSON.writeValueAsString(
                            Map.of(
                                    "summary",
                                    "summary",
                                    "verdict",
                                    "REQUEST_CHANGES",
                                    "lineComments",
                                    List.of(comment()),
                                    "inspection",
                                    Map.of(
                                            "inspectedTargets",
                                            List.of(wrongHunkId),
                                            "evidence",
                                            List.of(
                                                    Map.of(
                                                            "findingIndex",
                                                            0,
                                                            "targetIds",
                                                            List.of(wrongHunkId),
                                                            "relatedFiles",
                                                            List.of())))));

            ReviewPassResult result = ReviewPassParser.parse(raw, manifest, null);

            assertThat(result.ledger().evidence()).isEmpty();
        }
    }

    private static Map<String, Object> comment() {
        return Map.of(
                "file", "src/Auth.java",
                "line", 1,
                "type", "issue",
                "severity", "major",
                "category", "security",
                "confidence", "high",
                "body", "Authorization is bypassed.",
                "rationale", "The changed method no longer checks authorization.");
    }

    private static String diff() {
        return """
                diff --git a/src/Auth.java b/src/Auth.java
                --- a/src/Auth.java
                +++ b/src/Auth.java
                @@ -1 +1 @@
                -private void check() {}
                +public void check() {}
                """;
    }
}
