package com.jinloes.prpilot.sidecar.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GitHubSessionTest {

    private static GitHubAuthService.TokenResolver resolved(String token) {
        return hostname -> GitHubAuthService.TokenResolution.resolved(token);
    }

    @Nested
    class Open {
        @Test
        void resolvesGithubDotComWithoutAHostnameArgument() {
            String[] seenHostname = {"unset"};
            GitHubSession session =
                    GitHubSession.open(
                            hostname -> {
                                seenHostname[0] = hostname;
                                return GitHubAuthService.TokenResolution.resolved("t");
                            },
                            "https://github.com");

            assertThat(session.isOpen()).isTrue();
            assertThat(session.apiBaseUrl()).isEqualTo("https://api.github.com");
            assertThat(seenHostname[0]).isNull();
        }

        @Test
        void resolvesEnterpriseHostsThroughTheirV3ApiPath() {
            String[] seenHostname = {null};
            GitHubSession session =
                    GitHubSession.open(
                            hostname -> {
                                seenHostname[0] = hostname;
                                return GitHubAuthService.TokenResolution.resolved("t");
                            },
                            "https://git.example.com");

            assertThat(session.apiBaseUrl()).isEqualTo("https://git.example.com/api/v3");
            assertThat(seenHostname[0]).isEqualTo("git.example.com");
        }

        @Test
        void defaultsABlankOriginToGithubDotCom() {
            assertThat(GitHubSession.open(resolved("t"), "").apiBaseUrl())
                    .isEqualTo("https://api.github.com");
            assertThat(GitHubSession.open(resolved("t"), null).apiBaseUrl())
                    .isEqualTo("https://api.github.com");
        }

        @Test
        void rejectsNonOriginUrlsBeforeResolvingCredentials() {
            int[] tokenCalls = {0};
            GitHubSession session =
                    GitHubSession.open(
                            hostname -> {
                                tokenCalls[0]++;
                                return GitHubAuthService.TokenResolution.resolved("t");
                            },
                            "https://github.com/acme/widgets");

            assertThat(session.isOpen()).isFalse();
            assertThat(session.failure()).isEqualTo(GitHubFailure.INVALID_BASE_URL);
            assertThat(tokenCalls[0]).isZero();
        }

        @Test
        void distinguishesAMissingCliFromAMissingLogin() {
            assertThat(
                            GitHubSession.open(
                                            hostname ->
                                                    GitHubAuthService.TokenResolution
                                                            .notInstalled(),
                                            "https://github.com")
                                    .failure())
                    .isEqualTo(GitHubFailure.NOT_INSTALLED);
            assertThat(
                            GitHubSession.open(
                                            hostname ->
                                                    GitHubAuthService.TokenResolution
                                                            .notAuthenticated(),
                                            "https://github.com")
                                    .failure())
                    .isEqualTo(GitHubFailure.NOT_AUTHENTICATED);
        }

        @Test
        void leavesNoTokenOnAFailedSession() {
            GitHubSession session = GitHubSession.open(resolved("t"), "http://github.com");

            assertThat(session.token()).isNull();
            assertThat(session.apiBaseUrl()).isNull();
        }
    }
}
