package com.jinloes.prpilot.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewSessionServiceTest {

    @Nested
    class RecordOutcome {

        private Path tmpDir;
        private Path logFile;
        private ReviewSessionService service;

        @BeforeEach
        void setUp() throws IOException {
            tmpDir = Files.createTempDirectory("session-outcome");
            logFile = tmpDir.resolve("review-outcomes.jsonl");
            service = new ReviewSessionService(new ReviewOutcomeLog(logFile));
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(tmpDir.toFile());
        }

        private static ReviewEngineApi.OutcomeCommentParam comment(String body) {
            return new ReviewEngineApi.OutcomeCommentParam(
                    "A.java", 10, "issue", body, "major", "high");
        }

        @Test
        void classifiesAndWritesOneRecordPerComment() throws IOException {
            ReviewEngineApi.RecordOutcomeResult result =
                    service.recordOutcome(
                            new ReviewEngineApi.RecordOutcomeParams(
                                    "claude",
                                    "sonnet",
                                    List.of(comment("kept"), comment("dropped")),
                                    List.of(comment("kept"))));

            assertThat(result.recorded()).isEqualTo(2);
            assertThat(Files.readAllLines(logFile, StandardCharsets.UTF_8)).hasSize(2);
        }

        /** The host cannot know which prompt this engine build ships, so the engine supplies it. */
        @Test
        void stampsTheEnginesOwnPromptVersionRatherThanTrustingTheCaller() throws IOException {
            service.recordOutcome(
                    new ReviewEngineApi.RecordOutcomeParams(
                            "copilot", "gpt-5", List.of(comment("x")), List.of()));

            String line = Files.readAllLines(logFile, StandardCharsets.UTF_8).get(0);
            assertThat(line)
                    .contains("\"promptVersion\":\"" + ClaudeService.PROMPT_VERSION + "\"")
                    .contains("\"provider\":\"copilot\"")
                    .contains("\"model\":\"gpt-5\"");
        }

        @Test
        void toleratesNullParamsAndNullCommentLists() {
            assertThat(service.recordOutcome(null).recorded()).isZero();
            assertThat(
                            service.recordOutcome(
                                            new ReviewEngineApi.RecordOutcomeParams(
                                                    "claude", "sonnet", null, null))
                                    .recorded())
                    .isZero();
        }

        @Test
        void carriesSeverityAndConfidenceThroughToTheRecord() throws IOException {
            service.recordOutcome(
                    new ReviewEngineApi.RecordOutcomeParams(
                            "claude", "sonnet", List.of(comment("x")), List.of()));

            String line = Files.readAllLines(logFile, StandardCharsets.UTF_8).get(0);
            assertThat(line)
                    .contains("\"severity\":\"major\"")
                    .contains("\"confidence\":\"high\"")
                    .contains("\"outcome\":\"deleted\"");
        }
    }

    @Nested
    class ReadGuidelines {

        private Path repoDir;
        private final ReviewSessionService service = new ReviewSessionService();

        @BeforeEach
        void setUp() throws IOException {
            repoDir = Files.createTempDirectory("session-guidelines");
        }

        @AfterEach
        void tearDown() {
            FileUtils.deleteQuietly(repoDir.toFile());
        }

        private void write(String relative, String content) throws IOException {
            Path file = repoDir.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        }

        @Test
        void readsTheDefaultFilesWhenNoGlobsAreSupplied() throws IOException {
            write("AGENTS.md", "Always add tests.");

            String guidelines =
                    service.readGuidelines(
                                    new ReviewEngineApi.ReadGuidelinesParams(
                                            repoDir.toString(), List.of()))
                            .guidelines();

            // An empty glob list must mean "engine defaults", not "match nothing" — that fallback
            // is what lets a host avoid carrying its own copy of the default file list.
            assertThat(guidelines).contains("## AGENTS.md").contains("Always add tests.");
        }

        @Test
        void treatsNullGlobsTheSameAsAnEmptyList() throws IOException {
            write("CONTRIBUTING.md", "Squash your commits.");

            String guidelines =
                    service.readGuidelines(
                                    new ReviewEngineApi.ReadGuidelinesParams(
                                            repoDir.toString(), null))
                            .guidelines();

            assertThat(guidelines).contains("Squash your commits.");
        }

        @Test
        void addsExplicitGlobsToTheDefaults() throws IOException {
            write("AGENTS.md", "default file");
            write("docs/style.md", "custom file");

            String guidelines =
                    service.readGuidelines(
                                    new ReviewEngineApi.ReadGuidelinesParams(
                                            repoDir.toString(), List.of("**/style.md")))
                            .guidelines();

            assertThat(guidelines).contains("custom file").contains("default file");
            assertThat(guidelines.indexOf("custom file"))
                    .isLessThan(guidelines.indexOf("default file"));
        }

        @Test
        void returnsEmptyForABlankOrMissingDirectoryRatherThanThrowing() {
            assertThat(service.readGuidelines(null).guidelines()).isEmpty();
            assertThat(
                            service.readGuidelines(
                                            new ReviewEngineApi.ReadGuidelinesParams("", List.of()))
                                    .guidelines())
                    .isEmpty();
            assertThat(
                            service.readGuidelines(
                                            new ReviewEngineApi.ReadGuidelinesParams(
                                                    repoDir.resolve("nope").toString(), List.of()))
                                    .guidelines())
                    .isEmpty();
        }

        @Test
        void returnsEmptyWhenNothingMatches() {
            assertThat(
                            service.readGuidelines(
                                            new ReviewEngineApi.ReadGuidelinesParams(
                                                    repoDir.toString(), List.of()))
                                    .guidelines())
                    .isEmpty();
        }
    }
}
