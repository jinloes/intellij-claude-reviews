package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RepoGuidelinesReaderTest {

    private Path tempDir;
    private Path outsideFile;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("repo-guidelines-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        if (outsideFile != null) {
            Files.deleteIfExists(outsideFile);
        }
    }

    private void write(String relPath, String content) throws IOException {
        Path target = tempDir.resolve(relPath);
        Files.createDirectories(target.getParent() != null ? target.getParent() : tempDir);
        Files.writeString(target, content);
    }

    @Nested
    class GlobToRegex {

        @Test
        void singleStarStaysWithinSegment() {
            assertThat("a.md".matches(RepoGuidelinesReader.globToRegex("*.md"))).isTrue();
            assertThat("dir/a.md".matches(RepoGuidelinesReader.globToRegex("*.md"))).isFalse();
        }

        @Test
        void doubleStarSlashMatchesZeroOrMoreSegments() {
            String re = RepoGuidelinesReader.globToRegex("**/style.md");
            assertThat("style.md".matches(re)).isTrue();
            assertThat("a/style.md".matches(re)).isTrue();
            assertThat("a/b/style.md".matches(re)).isTrue();
            assertThat("a/style.md.bak".matches(re)).isFalse();
        }

        @Test
        void questionMarkMatchesSingleNonSlashChar() {
            String re = RepoGuidelinesReader.globToRegex("v?.md");
            assertThat("v1.md".matches(re)).isTrue();
            assertThat("v12.md".matches(re)).isFalse();
        }

        @Test
        void literalDotIsEscaped() {
            assertThat("axmd".matches(RepoGuidelinesReader.globToRegex("a.md"))).isFalse();
        }
    }

    @Nested
    class ResolvePaths {

        @Test
        void literalPathsResolveInPriorityOrder() throws IOException {
            write("AGENTS.md", "a");
            write(".review/ai-agent/coding-pattern.md", "b");
            List<String> resolved =
                    RepoGuidelinesReader.resolvePaths(
                            tempDir.toFile(),
                            List.of(
                                    ".review/ai-agent/coding-pattern.md",
                                    "AGENTS.md",
                                    "MISSING.md"));
            assertThat(resolved).containsExactly(".review/ai-agent/coding-pattern.md", "AGENTS.md");
        }

        @Test
        void globMatchesNestedFilesAndDeduplicates() throws IOException {
            write("docs/style.md", "s1");
            write("src/style.md", "s2");
            write("style.md", "s0");
            List<String> resolved =
                    RepoGuidelinesReader.resolvePaths(
                            tempDir.toFile(), List.of("**/style.md", "style.md"));
            assertThat(resolved).containsExactly("docs/style.md", "src/style.md", "style.md");
        }

        @Test
        void skipsHeavyDirectories() throws IOException {
            write("node_modules/pkg/style.md", "ignored");
            write("keep/style.md", "kept");
            List<String> resolved =
                    RepoGuidelinesReader.resolvePaths(tempDir.toFile(), List.of("**/style.md"));
            assertThat(resolved).containsExactly("keep/style.md");
        }

        @Test
        void rejectsTraversalAndAbsolutePaths() throws IOException {
            outsideFile = Files.createTempFile(tempDir.getParent(), "outside-guidance", ".md");
            Files.writeString(outsideFile, "secret");
            write("inside.md", "safe");

            List<String> resolved =
                    RepoGuidelinesReader.resolvePaths(
                            tempDir.toFile(),
                            List.of(
                                    "../" + outsideFile.getFileName(),
                                    outsideFile.toString(),
                                    "inside.md"));

            assertThat(resolved).containsExactly("inside.md");
        }

        @Test
        void rejectsFileAndDirectorySymlinks() throws IOException {
            outsideFile = Files.createTempFile(tempDir.getParent(), "outside-guidance", ".md");
            Files.writeString(outsideFile, "secret");
            Path outsideDirectory =
                    Files.createTempDirectory(tempDir.getParent(), "outside-guidance-dir");
            Files.writeString(outsideDirectory.resolve("AGENTS.md"), "directory secret");
            try {
                Files.createSymbolicLink(tempDir.resolve("linked.md"), outsideFile);
                Files.createSymbolicLink(tempDir.resolve("linked-dir"), outsideDirectory);
            } catch (UnsupportedOperationException | IOException | SecurityException e) {
                deleteTree(outsideDirectory);
                Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
            }

            try {
                assertThat(
                                RepoGuidelinesReader.resolvePaths(
                                        tempDir.toFile(),
                                        List.of("linked.md", "linked-dir/**/*.md", "**/*.md")))
                        .isEmpty();
            } finally {
                deleteTree(outsideDirectory);
            }
        }
    }

    @Nested
    class Read {

        @Test
        void concatenatesMatchedFilesWithHeaders() throws IOException {
            write("AGENTS.md", "agent rules");
            write(".review/ai-agent/coding-pattern.md", "pattern rules");
            String result =
                    RepoGuidelinesReader.read(
                            tempDir.toFile(), List.of("AGENTS.md", ".review/ai-agent/*.md"));
            assertThat(result)
                    .contains("## AGENTS.md\nagent rules")
                    .contains("## .review/ai-agent/coding-pattern.md\npattern rules");
        }

        @Test
        void fallsBackToDefaultsWhenGlobsEmpty() throws IOException {
            write("AGENTS.md", "default doc");
            String result = RepoGuidelinesReader.read(tempDir.toFile(), List.of());
            assertThat(result).contains("## AGENTS.md\ndefault doc");
        }

        @Test
        void defaultsIncludeStandardAgentInstructionFilesInPriorityOrder() throws IOException {
            write("AGENTS.md", "root agents");
            write("module/AGENTS.md", "module agents");
            write("CLAUDE.md", "claude");
            write(".claude/rules/java.md", "claude rule");
            write(".github/copilot-instructions.md", "copilot");
            write(".github/instructions/java.instructions.md", "copilot java");
            write(".review/ai-agent/coding-pattern.md", "team patterns");
            write("CONTRIBUTING.md", "contributing");

            List<String> resolved =
                    RepoGuidelinesReader.resolvePaths(
                            tempDir.toFile(), RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS);

            assertThat(resolved)
                    .containsExactly(
                            "AGENTS.md",
                            "module/AGENTS.md",
                            "CLAUDE.md",
                            ".claude/rules/java.md",
                            ".github/copilot-instructions.md",
                            ".github/instructions/java.instructions.md",
                            "CONTRIBUTING.md");
        }

        @Test
        void configuredPathsArePrioritizedAndAddedToDefaults() throws IOException {
            write("AGENTS.md", "default rules");
            write(".review/ai-agent/coding-pattern.md", "team rules");

            String result =
                    RepoGuidelinesReader.read(
                            tempDir.toFile(),
                            List.of(".review/ai-agent/coding-pattern.md", "AGENTS.md"));

            assertThat(result)
                    .contains("## .review/ai-agent/coding-pattern.md\nteam rules")
                    .contains("## AGENTS.md\ndefault rules");
            assertThat(result.indexOf(".review/ai-agent/coding-pattern.md"))
                    .isLessThan(result.indexOf("AGENTS.md"));
            assertThat(result).containsOnlyOnce("## AGENTS.md");
        }

        @Test
        void capsTotalBytes() throws IOException {
            write("AGENTS.md", "😀".repeat(RepoGuidelinesReader.MAX_GUIDELINES_BYTES));
            String result = RepoGuidelinesReader.read(tempDir.toFile(), List.of("AGENTS.md"));

            assertThat(result).contains("...(truncated)");
            assertThat(result).doesNotContain("�");
            assertThat(result.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(RepoGuidelinesReader.MAX_GUIDELINES_BYTES);
        }

        @Test
        void capIncludesHeadersAndSeparatorsAcrossManyFiles() throws IOException {
            for (int i = 0; i < 80; i++) {
                write(
                        "docs/"
                                + "long-directory-name-".repeat(4)
                                + i
                                + "/"
                                + "long-guidance-file-name-".repeat(3)
                                + i
                                + ".md",
                        "x");
            }

            String result = RepoGuidelinesReader.read(tempDir.toFile(), List.of("**/*.md"));

            assertThat(result.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(RepoGuidelinesReader.MAX_GUIDELINES_BYTES);
            assertThat(result).doesNotEndWith("\n## ");
        }

        @Test
        void doesNotReadOutsideContentThroughLiteralOrDefaultGlob() throws IOException {
            outsideFile = Files.createTempFile(tempDir.getParent(), "outside-guidance", ".md");
            Files.writeString(outsideFile, "outside secret");
            try {
                Files.createSymbolicLink(tempDir.resolve("AGENTS.md"), outsideFile);
            } catch (UnsupportedOperationException | IOException | SecurityException e) {
                Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
            }

            assertThat(
                            RepoGuidelinesReader.read(
                                    tempDir.toFile(),
                                    List.of("../" + outsideFile.getFileName(), "AGENTS.md")))
                    .isEmpty();
        }

        @Test
        void returnsEmptyForNullOrMissingDir() {
            assertThat(RepoGuidelinesReader.read(null, List.of("AGENTS.md"))).isEmpty();
            assertThat(
                            RepoGuidelinesReader.read(
                                    new File(tempDir.toFile(), "nope"), List.of("AGENTS.md")))
                    .isEmpty();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
