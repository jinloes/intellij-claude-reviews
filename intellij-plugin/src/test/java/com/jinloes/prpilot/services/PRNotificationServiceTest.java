package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.services.PRNotificationService.Candidate;
import com.jinloes.prpilot.services.PRNotificationService.NotificationSource;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRNotificationServiceTest {

    private static PullRequest pr(String owner, String repo, int number) {
        return new PullRequest(
                "Title #" + number, "https://github.test", owner, repo, number, "", "octocat", "");
    }

    @Nested
    class NotificationLabeling {

        @Test
        void reviewRequestedTitleNamesTheSourceAndPr() {
            String title =
                    PRNotificationService.notificationTitle(
                            pr("acme", "foo", 7), NotificationSource.REVIEW_REQUESTED);
            assertThat(title).contains("Review requested").contains("acme/foo #7");
        }

        @Test
        void starredRepoTitleIsDistinctFromReviewRequested() {
            String title =
                    PRNotificationService.notificationTitle(
                            pr("acme", "bar", 9), NotificationSource.STARRED_REPO);
            assertThat(title).contains("Starred repo").contains("acme/bar #9");
        }

        @Test
        void reviewRequestedWinsWhenAPrMatchesBothSources() {
            PullRequest shared = pr("acme", "foo", 1);
            PullRequest starredOnly = pr("acme", "bar", 2);
            List<Candidate> merged =
                    PRNotificationService.mergeCandidates(
                            List.of(shared), List.of(shared, starredOnly));

            assertThat(merged).hasSize(2);
            assertThat(merged.get(0).source()).isEqualTo(NotificationSource.REVIEW_REQUESTED);
            assertThat(merged.get(1).pr().getNumber()).isEqualTo(2);
            assertThat(merged.get(1).source()).isEqualTo(NotificationSource.STARRED_REPO);
        }
    }

    @Nested
    class QueryBuilders {

        @Test
        void reviewRequestedQueryExcludesDrafts() {
            assertThat(PRNotificationService.REVIEW_REQUESTED_QUERY)
                    .isEqualTo("is:open is:pr draft:false review-requested:@me");
        }

        @Test
        void starredReposQueryExcludesDrafts() {
            assertThat(PRNotificationService.buildStarredReposQuery("repo:acme/foo repo:acme/bar"))
                    .isEqualTo("is:open is:pr draft:false repo:acme/foo repo:acme/bar");
        }
    }

    @Nested
    class FormatPollStatus {

        @Test
        void noPollYetReturnsNull() {
            String result = PRNotificationService.formatPollStatus(0L, null, 10_000L);
            assertThat(result).isNull();
        }

        @Test
        void successStatusUsesLastPolledPrefix() {
            String result = PRNotificationService.formatPollStatus(9_000L, null, 10_000L);
            assertThat(result).isEqualTo("Last polled: 1s ago");
        }

        @Test
        void errorStatusIncludesErrorText() {
            String result =
                    PRNotificationService.formatPollStatus(
                            120_000L, PRNotificationService.AUTH_MISSING_ERROR, 180_000L);
            assertThat(result)
                    .isEqualTo(
                            "Last poll: 1 min ago — Error: "
                                    + PRNotificationService.AUTH_MISSING_ERROR);
        }
    }

    @Nested
    class SanitizeError {

        @Test
        void plainMessageIsReturnedAsIs() {
            String result =
                    PRNotificationService.sanitizeError(new IOException("connection refused"));
            assertThat(result).isEqualTo("connection refused");
        }

        @Test
        void bearerTokenIsRedacted() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException(
                                    "401 Unauthorized: Bearer ghp_abc123XYZ token rejected"));
            assertThat(result).doesNotContain("ghp_abc123XYZ");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void tokenKeywordIsRedacted() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException("invalid token: ghs_secretvalue"));
            assertThat(result).doesNotContain("ghs_secretvalue");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void caseInsensitiveRedaction() {
            String result =
                    PRNotificationService.sanitizeError(
                            new IOException("TOKEN=abc123secret rejected"));
            assertThat(result).doesNotContain("abc123secret");
            assertThat(result).contains("[redacted]");
        }

        @Test
        void nullMessageFallsBackToUnknownError() {
            String result = PRNotificationService.sanitizeError(new IOException((String) null));
            assertThat(result).isEqualTo("unknown error");
        }

        @Test
        void blankMessageFallsBackToUnknownError() {
            String result = PRNotificationService.sanitizeError(new IOException("   "));
            assertThat(result).isEqualTo("unknown error");
        }
    }
}
