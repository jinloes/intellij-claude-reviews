package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InspectionManifestTest {
    @Nested
    class FromDiff {
        @Test
        void createsStableFileAndHunkTargetsWithChangedNewLines() {
            String diff =
                    """
                    diff --git a/src/Auth.java b/src/Auth.java
                    --- a/src/Auth.java
                    +++ b/src/Auth.java
                    @@ -10,2 +20,3 @@
                     context
                    -private void oldMethod() {}
                    +public void newMethod() {}
                    +authorize();
                    """;

            InspectionManifest first = InspectionManifest.fromDiff(diff);
            InspectionManifest second = InspectionManifest.fromDiff(diff);

            assertThat(first.files())
                    .singleElement()
                    .satisfies(
                            file -> {
                                assertThat(file.path()).isEqualTo("src/Auth.java");
                                assertThat(file.highRisk()).isTrue();
                                assertThat(file.id()).isEqualTo(second.files().get(0).id());
                                assertThat(file.hunks())
                                        .singleElement()
                                        .satisfies(
                                                hunk -> {
                                                    assertThat(hunk.changedNewLines())
                                                            .containsExactly(21, 22);
                                                    assertThat(hunk.highRisk()).isTrue();
                                                    assertThat(hunk.id())
                                                            .isEqualTo(
                                                                    second.files()
                                                                            .get(0)
                                                                            .hunks()
                                                                            .get(0)
                                                                            .id());
                                                });
                            });
        }

        @Test
        void ignoresDeletedFilesThatCannotAnchorNewLineComments() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/Removed.java b/Removed.java
                            --- a/Removed.java
                            +++ /dev/null
                            @@ -1 +0,0 @@
                            -public class Removed {}
                            """);

            assertThat(manifest.files())
                    .singleElement()
                    .satisfies(
                            file -> {
                                assertThat(file.path()).isEqualTo("Removed.java");
                                assertThat(file.hunks())
                                        .singleElement()
                                        .satisfies(
                                                hunk ->
                                                        assertThat(hunk.changedNewLines())
                                                                .isEmpty());
                            });
        }
    }

    @Nested
    class ToPromptJson {
        @Test
        void compactsConsecutiveChangedLines() throws Exception {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/schema.sql b/schema.sql
                            --- a/schema.sql
                            +++ b/schema.sql
                            @@ -1 +1,4 @@
                            -old
                            +one
                            +two
                            +three
                            +four
                            """);

            String promptJson = manifest.toPromptJson();

            assertThat(promptJson)
                    .contains("\"changedNewLineRanges\":[\"1-4\"]")
                    .doesNotContain("\"changedNewLines\"");
        }
    }

    @Nested
    class IsSafeRelativePath {
        @Test
        void acceptsRepositoryRelativePaths() {
            assertThat(InspectionManifest.isSafeRelativePath("src/main/A.java")).isTrue();
        }

        @Test
        void rejectsAbsoluteAndEscapingPaths() {
            assertThat(InspectionManifest.isSafeRelativePath("/etc/passwd")).isFalse();
            assertThat(InspectionManifest.isSafeRelativePath("../../secret")).isFalse();
            assertThat(InspectionManifest.isSafeRelativePath("C:/secret")).isFalse();
        }
    }
}
