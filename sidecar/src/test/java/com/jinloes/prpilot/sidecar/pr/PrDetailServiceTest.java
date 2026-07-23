package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PrDetailServiceTest {
    @Test
    void loadsDetailsUsingTheGitHubDotComApiWithoutAHostnameArgument() {
        AtomicReference<String> hostname = new AtomicReference<>("not-called");
        AtomicReference<String> apiBaseUrl = new AtomicReference<>();
        PrDetailService service =
                new PrDetailService(
                        value -> {
                            hostname.set(value);
                            return GitHubAuthService.TokenResolution.resolved("secret-token");
                        },
                        (baseUrl, token, owner, repo, number) -> {
                            apiBaseUrl.set(baseUrl);
                            assertThat(token).isEqualTo("secret-token");
                            assertThat(owner).isEqualTo("acme");
                            assertThat(repo).isEqualTo("widgets");
                            assertThat(number).isEqualTo(42);
                            return PrDetailService.DetailResponse.success(detail());
                        });

        PrDetailResult result = service.get(params("https://github.com/"));

        assertThat(hostname.get()).isNull();
        assertThat(apiBaseUrl.get()).isEqualTo("https://api.github.com");
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.detail()).isEqualTo(detail());
        assertThat(result.toString()).doesNotContain("secret-token");
    }

    @Test
    void usesEnterpriseHostnameAndApiPrefix() {
        AtomicReference<String> hostname = new AtomicReference<>();
        AtomicReference<String> apiBaseUrl = new AtomicReference<>();
        PrDetailService service =
                new PrDetailService(
                        value -> {
                            hostname.set(value);
                            return GitHubAuthService.TokenResolution.resolved("token");
                        },
                        (baseUrl, token, owner, repo, number) -> {
                            apiBaseUrl.set(baseUrl);
                            return PrDetailService.DetailResponse.success(detail());
                        });

        PrDetailResult result = service.get(params("https://github.example.test"));

        assertThat(hostname.get()).isEqualTo("github.example.test");
        assertThat(apiBaseUrl.get()).isEqualTo("https://github.example.test/api/v3");
        assertThat(result.status()).isEqualTo("ok");
    }

    @Test
    void mapsCliAndGitHubFailuresWithoutCallingTheDetailApiWhenUnavailable() {
        PrDetailService notInstalled =
                new PrDetailService(
                        ignored -> GitHubAuthService.TokenResolution.notInstalled(),
                        (baseUrl, token, owner, repo, number) -> {
                            throw new AssertionError("detail API must not be called");
                        });
        PrDetailService notAuthenticated =
                new PrDetailService(
                        ignored -> GitHubAuthService.TokenResolution.notAuthenticated(),
                        (baseUrl, token, owner, repo, number) -> {
                            throw new AssertionError("detail API must not be called");
                        });
        PrDetailService rateLimited =
                new PrDetailService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("token"),
                        (baseUrl, token, owner, repo, number) ->
                                PrDetailService.DetailResponse.of(
                                        PrDetailService.DetailStatus.RATE_LIMITED));

        assertThat(notInstalled.get(params("https://github.com")).status())
                .isEqualTo("not_installed");
        assertThat(notAuthenticated.get(params("https://github.com")).status())
                .isEqualTo("not_authenticated");
        assertThat(rateLimited.get(params("https://github.com")).status())
                .isEqualTo("rate_limited");
    }

    @Test
    void rejectsUnsafeUrlsAndInvalidRepositoryIdentityBeforeResolvingAToken() {
        AtomicReference<Boolean> resolverCalled = new AtomicReference<>(false);
        PrDetailService service =
                new PrDetailService(
                        ignored -> {
                            resolverCalled.set(true);
                            return GitHubAuthService.TokenResolution.resolved("token");
                        },
                        (baseUrl, token, owner, repo, number) ->
                                PrDetailService.DetailResponse.success(detail()));

        assertThat(service.get(params("https://github.com/api/v3")).status())
                .isEqualTo("invalid_base_url");
        assertThat(
                        service.get(
                                        new PrDetailService.PrDetailParams(
                                                "https://github.com", "acme/x", "widgets", 42))
                                .status())
                .isEqualTo("invalid_request");
        assertThat(
                        service.get(
                                        new PrDetailService.PrDetailParams(
                                                "https://github.com", "acme", "widgets", 0))
                                .status())
                .isEqualTo("invalid_request");
        assertThat(resolverCalled.get()).isFalse();
    }

    @Test
    void parsesNullableRepositoriesAndRejectsMalformedDetailPayloads() {
        PrDetailService.DetailResponse nullableRepositories =
                PrDetailService.parseDetailResponse(
                        """
                        {"merged":false,"title":null,"body":null,
                         "head":{"sha":"abc","ref":"feature","repo":null},
                         "base":{"repo":null}}
                        """);

        assertThat(nullableRepositories.status()).isEqualTo(PrDetailService.DetailStatus.OK);
        assertThat(nullableRepositories.detail().title()).isEmpty();
        assertThat(nullableRepositories.detail().body()).isEmpty();
        assertThat(nullableRepositories.detail().head().repoFullName()).isNull();
        assertThat(nullableRepositories.detail().baseRepoFullName()).isEmpty();
        assertThat(PrDetailService.parseDetailResponse("{\"merged\":true}").status())
                .isEqualTo(PrDetailService.DetailStatus.API_FAILED);
        assertThat(
                        PrDetailService.parseDetailResponse(
                                        "{\"merged\":true,\"title\":\"x\",\"body\":\"y\",\"head\":{}}")
                                .status())
                .isEqualTo(PrDetailService.DetailStatus.API_FAILED);
    }

    private PrDetailService.PrDetailParams params(String baseUrl) {
        return new PrDetailService.PrDetailParams(baseUrl, "acme", "widgets", 42);
    }

    private PrDetail detail() {
        return new PrDetail(
                false,
                "Example",
                "Description",
                new PrDetail.Head(
                        "abc", "feature", "acme/widgets", "https://github.com/acme/widgets.git"),
                "acme/widgets");
    }
}
