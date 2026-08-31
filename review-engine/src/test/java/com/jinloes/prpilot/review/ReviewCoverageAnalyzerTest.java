package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewCoverageAnalyzerTest {
    private final ReviewCoverageAnalyzer analyzer = new ReviewCoverageAnalyzer();

    @Nested
    class FindGaps {
        @Test
        void findsOnlyUninspectedHighRiskHunks() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/Api.java b/src/Api.java
                            --- a/src/Api.java
                            +++ b/src/Api.java
                            @@ -1 +1 @@
                            -private void oldApi() {}
                            +public void newApi() {}
                            @@ -10 +10 @@
                            -int value = 1;
                            +int value = 2;
                            """);
            String inspected = manifest.files().get(0).hunks().get(1).id();

            List<CoverageGap> gaps =
                    analyzer.findGaps(
                            manifest, new InspectionLedger(true, Set.of(inspected), List.of()));

            assertThat(gaps)
                    .singleElement()
                    .extracting(CoverageGap::targetId)
                    .isEqualTo(manifest.files().get(0).hunks().get(0).id());
        }

        @Test
        void doesNotGuessCoverageWhenTheProviderOmittedTheLedger() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/api.proto b/api.proto
                            --- a/api.proto
                            +++ b/api.proto
                            @@ -1 +1 @@
                            -string old = 1;
                            +string current = 2;
                            """);

            assertThat(analyzer.findGaps(manifest, InspectionLedger.missing())).isEmpty();
        }

        @Test
        void doesNotCreateGapsForDeletedFiles() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/schema.sql b/schema.sql
                            --- a/schema.sql
                            +++ /dev/null
                            @@ -1 +0,0 @@
                            -CREATE TABLE users (id BIGINT);
                            """);

            assertThat(analyzer.findGaps(manifest, new InspectionLedger(true, Set.of(), List.of())))
                    .isEmpty();
        }
    }
}
