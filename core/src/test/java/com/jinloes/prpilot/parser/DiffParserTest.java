package com.jinloes.prpilot.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.parser.DiffParser.DiffFile;
import com.jinloes.prpilot.parser.DiffParser.DiffLine;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for DiffParser. */
class DiffParserTest {

    private static final String TWO_FILE_DIFF =
            """
            diff --git a/src/Foo.java b/src/Foo.java
            index abc..def 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -1,3 +1,4 @@
             context line
            +added line
            -deleted line
             another context
            diff --git a/src/Bar.java b/src/Bar.java
            index 111..222 100644
            --- a/src/Bar.java
            +++ b/src/Bar.java
            @@ -10,2 +10,2 @@
             bar context
            +bar added
            """;

    @Nested
    class ParseDiff {

        @Test
        void twoFilesCorrectFileCount() {
            assertThat(DiffParser.parseDiff(TWO_FILE_DIFF)).hasSize(2);
        }

        @Test
        void twoFilesCorrectFileNames() {
            List<DiffFile> files = DiffParser.parseDiff(TWO_FILE_DIFF);
            assertThat(files.get(0).getName()).isEqualTo("src/Foo.java");
            assertThat(files.get(1).getName()).isEqualTo("src/Bar.java");
        }

        @Test
        void addedLinesGetIncrementingLineNumbers() {
            List<DiffFile> files = DiffParser.parseDiff(TWO_FILE_DIFF);
            DiffLine added =
                    files.get(0).getLines().stream()
                            .filter(l -> l.type() == '+')
                            .findFirst()
                            .orElseThrow();
            assertThat(added.newLineNum()).isEqualTo(2);
            assertThat(added.content()).isEqualTo("added line");
        }

        @Test
        void deletedLinesGetMinusOneLineNumber() {
            List<DiffFile> files = DiffParser.parseDiff(TWO_FILE_DIFF);
            DiffLine deleted =
                    files.get(0).getLines().stream()
                            .filter(l -> l.type() == '-')
                            .findFirst()
                            .orElseThrow();
            assertThat(deleted.newLineNum()).isEqualTo(-1);
            assertThat(deleted.content()).isEqualTo("deleted line");
        }

        @Test
        void contextLinesGetIncrementingLineNumbersAndDeletedLinesDoNotAdvanceCounter() {
            List<DiffFile> files = DiffParser.parseDiff(TWO_FILE_DIFF);
            List<DiffLine> ctx =
                    files.get(0).getLines().stream().filter(l -> l.type() == ' ').toList();
            assertThat(ctx).hasSize(2);
            assertThat(ctx.get(0).newLineNum()).isEqualTo(1);
            assertThat(ctx.get(1).newLineNum()).isEqualTo(3);
        }

        @Test
        void firstLineAfterHunkHeaderHasHunkStartTrue() {
            List<DiffFile> files = DiffParser.parseDiff(TWO_FILE_DIFF);
            assertThat(files.get(0).getLines().get(0).hunkStart()).isTrue();
        }

        @Test
        void subsequentLinesInHunkHaveHunkStartFalse() {
            List<DiffFile> files = DiffParser.parseDiff(TWO_FILE_DIFF);
            List<DiffLine> lines = files.get(0).getLines();
            assertThat(lines.subList(1, lines.size())).allMatch(l -> !l.hunkStart());
        }

        @Test
        void emptyDiffReturnsEmptyList() {
            assertThat(DiffParser.parseDiff("")).isEmpty();
        }

        @Test
        void crlfLineEndingsParsedSameAsLf() {
            String crlfDiff =
                    "diff --git a/src/Foo.java b/src/Foo.java\r\n"
                            + "--- a/src/Foo.java\r\n"
                            + "+++ b/src/Foo.java\r\n"
                            + "@@ -1 +1 @@\r\n"
                            + "+added line\r\n";
            List<DiffFile> files = DiffParser.parseDiff(crlfDiff);
            assertThat(files).hasSize(1);
            assertThat(files.get(0).getName()).isEqualTo("src/Foo.java");
            assertThat(files.get(0).getLines().get(0).content()).isEqualTo("added line");
        }

        @Test
        void deletionOnlyHunkAllDeletedLinesHaveMinusOneLineNumber() {
            String diff =
                    "diff --git a/src/Foo.java b/src/Foo.java\n"
                            + "--- a/src/Foo.java\n"
                            + "+++ b/src/Foo.java\n"
                            + "@@ -5,3 +5,0 @@\n"
                            + "-deleted one\n"
                            + "-deleted two\n"
                            + "-deleted three\n";
            List<DiffFile> files = DiffParser.parseDiff(diff);
            assertThat(files).hasSize(1);
            assertThat(files.get(0).getLines()).allMatch(l -> l.newLineNum() == -1);
        }

        @Test
        void diffGitHeaderWithNoBSuffixUsesTheWholeLineAsName() {
            String diff = "diff --git a/src/Foo.java src/Foo.java\n";
            List<DiffFile> files = DiffParser.parseDiff(diff);
            assertThat(files).hasSize(1);
            // name comes from the fallback branch (no " b/" found)
            assertThat(files.get(0).getName()).doesNotStartWith(" ");
        }

        @Test
        void plusPlusPlusBHeaderRefinesTheFileName() {
            String diff =
                    "diff --git a/orig.java b/renamed.java\n"
                            + "+++ b/renamed.java\n"
                            + "@@ -1 +1 @@\n"
                            + "+line\n";
            List<DiffFile> files = DiffParser.parseDiff(diff);
            assertThat(files.get(0).getName()).isEqualTo("renamed.java");
        }

        @Test
        void plusPlusPlusWithoutBPrefixIsIgnoredForName() {
            String diff =
                    "diff --git a/src/Foo.java b/src/Foo.java\n"
                            + "+++ a/src/Foo.java\n"
                            + "@@ -1 +1 @@\n"
                            + "+line\n";
            List<DiffFile> files = DiffParser.parseDiff(diff);
            // +++ a/... does not start with "+++ b/" so name keeps the diff --git value
            assertThat(files.get(0).getName()).isEqualTo("src/Foo.java");
        }

        @Test
        void lineStartingWithBackslashNoNewlineAtEndOfFileIsSkipped() {
            String diff =
                    "diff --git a/f.txt b/f.txt\n"
                            + "@@ -1 +1 @@\n"
                            + "+content\n"
                            + "\\ No newline at end of file\n";
            List<DiffFile> files = DiffParser.parseDiff(diff);
            // the backslash line should be skipped, not added as a DiffLine
            assertThat(files.get(0).getLines()).hasSize(1);
            assertThat(files.get(0).getLines().get(0).content()).isEqualTo("content");
        }

        @Test
        void metadataHeaderLinesAreSkipped() {
            String diff =
                    "diff --git a/f.txt b/f.txt\n"
                            + "index abc..def 100644\n"
                            + "new file mode 100644\n"
                            + "deleted file mode 100644\n"
                            + "old mode 100644\n"
                            + "new mode 100755\n"
                            + "Binary files a/img.png and b/img.png differ\n"
                            + "similarity index 90%\n"
                            + "rename from foo.txt\n"
                            + "rename to bar.txt\n"
                            + "--- a/f.txt\n"
                            + "+++ b/f.txt\n"
                            + "@@ -1 +1 @@\n"
                            + "+line\n";
            List<DiffFile> files = DiffParser.parseDiff(diff);
            assertThat(files).hasSize(1);
            assertThat(files.get(0).getLines()).hasSize(1);
        }
    }

    @Nested
    class ComputeMaxColumns {

        @Test
        void shortLinesReturnMinimumOf40() {
            DiffFile file = new DiffFile("f.java");
            file.addLine(new DiffLine(1, '+', "short", false));
            assertThat(DiffParser.computeMaxColumns(List.of(file))).isEqualTo(40);
        }

        @Test
        void linesOfLength80Return80() {
            DiffFile file = new DiffFile("f.java");
            file.addLine(new DiffLine(1, '+', "x".repeat(80), false));
            assertThat(DiffParser.computeMaxColumns(List.of(file))).isEqualTo(80);
        }

        @Test
        void linesLongerThan120AreCappedAt120() {
            DiffFile file = new DiffFile("f.java");
            file.addLine(new DiffLine(1, '+', "x".repeat(200), false));
            assertThat(DiffParser.computeMaxColumns(List.of(file))).isEqualTo(120);
        }

        @Test
        void emptyFileListReturnsMinimumOf40() {
            assertThat(DiffParser.computeMaxColumns(List.of())).isEqualTo(40);
        }

        @Test
        void multipleFilesUsesMaximumAcrossAll() {
            DiffFile f1 = new DiffFile("a.java");
            f1.addLine(new DiffLine(1, '+', "x".repeat(60), false));
            DiffFile f2 = new DiffFile("b.java");
            f2.addLine(new DiffLine(1, '+', "x".repeat(90), false));
            assertThat(DiffParser.computeMaxColumns(List.of(f1, f2))).isEqualTo(90);
        }
    }
}
