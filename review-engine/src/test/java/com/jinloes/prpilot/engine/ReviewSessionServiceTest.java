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
}
