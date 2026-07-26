package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RepoGuidelinesReaderTest {

    private Path tempDir;

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
            write(".linkedin/ai-agent/coding-pattern.md", "b");
            List<String> resolved =
                    RepoGuidelinesReader.resolvePaths(
                            tempDir.toFile(),
                            List.of(".linkedin/ai-agent/coding-pattern.md", "AGENTS.md", "MISSING.md"));
            assertThat(resolved)
                    .containsExactly(".linkedin/ai-agent/coding-pattern.md", "AGENTS.md");
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
    }

    @Nested
    class Read {

        @Test
        void concatenatesMatchedFilesWithHeaders() throws IOException {
            write("AGENTS.md", "agent rules");
            write(".linkedin/ai-agent/coding-pattern.md", "pattern rules");
            String result =
                    RepoGuidelinesReader.read(
                            tempDir.toFile(),
                            List.of("AGENTS.md", ".linkedin/ai-agent/*.md"));
            assertThat(result)
                    .contains("## AGENTS.md\nagent rules")
                    .contains("## .linkedin/ai-agent/coding-pattern.md\npattern rules");
        }

        @Test
        void fallsBackToDefaultsWhenGlobsEmpty() throws IOException {
            write("AGENTS.md", "default doc");
            String result = RepoGuidelinesReader.read(tempDir.toFile(), List.of());
            assertThat(result).contains("## AGENTS.md\ndefault doc");
        }

        @Test
        void capsTotalBytes() throws IOException {
            write("AGENTS.md", "x".repeat(RepoGuidelinesReader.MAX_GUIDELINES_BYTES + 500));
            String result = RepoGuidelinesReader.read(tempDir.toFile(), List.of("AGENTS.md"));
            assertThat(result).contains("...(truncated)");
        }

        @Test
        void returnsEmptyForNullOrMissingDir() {
            assertThat(RepoGuidelinesReader.read(null, List.of("AGENTS.md"))).isEmpty();
            assertThat(RepoGuidelinesReader.read(new File(tempDir.toFile(), "nope"), List.of("AGENTS.md")))
                    .isEmpty();
        }
    }
}

