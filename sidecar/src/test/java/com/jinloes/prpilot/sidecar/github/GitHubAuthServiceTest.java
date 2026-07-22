package com.jinloes.prpilot.sidecar.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GitHubAuthServiceTest {
    @Test
    void authenticatesGitHubDotComWithoutAHostnameArgument() {
        AtomicReference<String> hostname = new AtomicReference<>("not-called");
        AtomicReference<String> apiBaseUrl = new AtomicReference<>();
        GitHubAuthService service =
                new GitHubAuthService(
                        value -> {
                            hostname.set(value);
                            return GitHubAuthService.TokenResolution.resolved("secret-token");
                        },
                        (baseUrl, token) -> {
                            apiBaseUrl.set(baseUrl);
                            assertThat(token).isEqualTo("secret-token");
                            return GitHubAuthService.UserResolution.authenticated("octocat");
                        });

        CheckAuthResult result = service.check("https://github.com/");

        assertThat(hostname.get()).isNull();
        assertThat(apiBaseUrl.get()).isEqualTo("https://api.github.com");
        assertThat(result.status()).isEqualTo("authenticated");
        assertThat(result.username()).isEqualTo("octocat");
        assertThat(result.toString()).doesNotContain("secret-token");
    }

    @Test
    void usesEnterpriseHostnameAndApiPrefix() {
        AtomicReference<String> hostname = new AtomicReference<>();
        AtomicReference<String> apiBaseUrl = new AtomicReference<>();
        GitHubAuthService service =
                new GitHubAuthService(
                        value -> {
                            hostname.set(value);
                            return GitHubAuthService.TokenResolution.resolved("token");
                        },
                        (baseUrl, ignored) -> {
                            apiBaseUrl.set(baseUrl);
                            return GitHubAuthService.UserResolution.authenticated(
                                    "enterprise-user");
                        });

        CheckAuthResult result = service.check("https://github.example.test");

        assertThat(hostname.get()).isEqualTo("github.example.test");
        assertThat(apiBaseUrl.get()).isEqualTo("https://github.example.test/api/v3");
        assertThat(result.username()).isEqualTo("enterprise-user");
    }

    @Test
    void classifiesUnavailableOrUnauthenticatedCliWithoutCallingTheApi() {
        AtomicReference<Boolean> apiCalled = new AtomicReference<>(false);
        GitHubAuthService notInstalled =
                new GitHubAuthService(
                        ignored -> GitHubAuthService.TokenResolution.notInstalled(),
                        (baseUrl, token) -> {
                            apiCalled.set(true);
                            return GitHubAuthService.UserResolution.authenticated("unused");
                        });
        GitHubAuthService notAuthenticated =
                new GitHubAuthService(
                        ignored -> GitHubAuthService.TokenResolution.notAuthenticated(),
                        (baseUrl, token) -> {
                            apiCalled.set(true);
                            return GitHubAuthService.UserResolution.authenticated("unused");
                        });

        assertThat(notInstalled.check("https://github.com").status()).isEqualTo("not_installed");
        assertThat(notAuthenticated.check("https://github.com").status())
                .isEqualTo("not_authenticated");
        assertThat(apiCalled.get()).isFalse();
    }

    @Test
    void classifiesUnauthorizedAndTransientApiFailures() {
        GitHubAuthService unauthorized =
                new GitHubAuthService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                        (baseUrl, token) -> GitHubAuthService.UserResolution.notAuthenticated());
        GitHubAuthService apiFailure =
                new GitHubAuthService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                        (baseUrl, token) -> GitHubAuthService.UserResolution.apiFailed());

        assertThat(unauthorized.check("https://github.com").status())
                .isEqualTo("not_authenticated");
        assertThat(apiFailure.check("https://github.com").status()).isEqualTo("api_failed");
    }

    @Test
    void rejectsUnsafeBaseUrlsBeforeResolvingAToken() {
        AtomicReference<Boolean> tokenResolverCalled = new AtomicReference<>(false);
        GitHubAuthService service =
                new GitHubAuthService(
                        ignored -> {
                            tokenResolverCalled.set(true);
                            return GitHubAuthService.TokenResolution.resolved("token");
                        },
                        (baseUrl, token) ->
                                GitHubAuthService.UserResolution.authenticated("unused"));

        CheckAuthResult result = service.check("https://github.com/api/v3?unsafe=true");

        assertThat(result.status()).isEqualTo("invalid_base_url");
        assertThat(tokenResolverCalled.get()).isFalse();
    }
}
