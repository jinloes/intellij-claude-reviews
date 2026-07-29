package com.jinloes.prpilot.sidecar.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GitHubApiBaseTest {

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        void defaultsToPublicGitHubWhenValueIsNull() {
            GitHubApiBase base = GitHubApiBase.parse(null);

            assertThat(base).isNotNull();
            assertThat(base.apiBaseUrl()).isEqualTo("https://api.github.com");
            assertThat(base.hostnameArgument()).isNull();
        }

        @Test
        void defaultsToPublicGitHubWhenValueIsBlank() {
            assertThat(GitHubApiBase.parse("   ")).isEqualTo(GitHubApiBase.parse(null));
        }

        @Test
        void mapsPublicGitHubToItsApiHost() {
            GitHubApiBase base = GitHubApiBase.parse("https://github.com");

            assertThat(base.apiBaseUrl()).isEqualTo("https://api.github.com");
        }

        @Test
        void omitsHostnameArgumentForPublicGitHubSoGhUsesItsDefault() {
            assertThat(GitHubApiBase.parse("https://github.com").hostnameArgument()).isNull();
        }

        @Test
        void mapsEnterpriseHostToItsApiV3Path() {
            GitHubApiBase base = GitHubApiBase.parse("https://github.mycompany.com");

            assertThat(base.apiBaseUrl()).isEqualTo("https://github.mycompany.com/api/v3");
            assertThat(base.hostnameArgument()).isEqualTo("github.mycompany.com");
        }

        @Test
        void lowercasesTheHost() {
            assertThat(GitHubApiBase.parse("https://GitHub.MyCompany.COM").apiBaseUrl())
                    .isEqualTo("https://github.mycompany.com/api/v3");
        }

        @Test
        void treatsUppercasedPublicGitHubAsPublicGitHub() {
            assertThat(GitHubApiBase.parse("https://GITHUB.COM").apiBaseUrl())
                    .isEqualTo("https://api.github.com");
        }

        @Test
        void trimsSurroundingWhitespace() {
            assertThat(GitHubApiBase.parse("  https://github.com  ").apiBaseUrl())
                    .isEqualTo("https://api.github.com");
        }

        @Test
        void acceptsATrailingSlash() {
            assertThat(GitHubApiBase.parse("https://github.com/").apiBaseUrl())
                    .isEqualTo("https://api.github.com");
        }

        @Test
        void rejectsPlainHttpSoTheTokenIsNeverSentInTheClear() {
            assertThat(GitHubApiBase.parse("http://github.com")).isNull();
        }

        @Test
        void rejectsEmbeddedCredentials() {
            assertThat(GitHubApiBase.parse("https://user:pass@github.com")).isNull();
        }

        @Test
        void rejectsAnExplicitPort() {
            assertThat(GitHubApiBase.parse("https://github.com:8443")).isNull();
        }

        @Test
        void rejectsAPathBeyondRoot() {
            assertThat(GitHubApiBase.parse("https://github.com/enterprise")).isNull();
        }

        @Test
        void rejectsAQueryString() {
            assertThat(GitHubApiBase.parse("https://github.com?a=b")).isNull();
        }

        @Test
        void rejectsAFragment() {
            assertThat(GitHubApiBase.parse("https://github.com#frag")).isNull();
        }

        @Test
        void rejectsAValueWithNoHost() {
            assertThat(GitHubApiBase.parse("https://")).isNull();
        }

        @Test
        void rejectsAMalformedUri() {
            assertThat(GitHubApiBase.parse("https://exa mple.com")).isNull();
        }

        @Test
        void rejectsANonHttpScheme() {
            assertThat(GitHubApiBase.parse("ftp://github.com")).isNull();
        }
    }

    @Nested
    @DisplayName("require")
    class Require {

        @Test
        void returnsTheSameResultAsParseForAValidOrigin() {
            assertThat(GitHubApiBase.require("https://github.mycompany.com"))
                    .isEqualTo(GitHubApiBase.parse("https://github.mycompany.com"));
        }

        @Test
        void throwsInsteadOfReturningNullForAnInvalidOrigin() {
            assertThatThrownBy(() -> GitHubApiBase.require("http://github.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid GitHub base URL");
        }

        @Test
        void throwsForAMalformedUri() {
            assertThatThrownBy(() -> GitHubApiBase.require("https://exa mple.com"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void defaultsToPublicGitHubWhenValueIsNull() {
            assertThat(GitHubApiBase.require(null).apiBaseUrl())
                    .isEqualTo("https://api.github.com");
        }
    }
}
