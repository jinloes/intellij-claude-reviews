package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GithubBaseUrlValidatorTest {
    @Test
    void normalizesBlankAndTrailingSlash() {
        assertThat(GithubBaseUrlValidator.normalize(" ")).isEqualTo("https://github.com");
        assertThat(GithubBaseUrlValidator.normalize(" https://GITHUB.EXAMPLE.COM/ "))
                .isEqualTo("https://github.example.com");
    }

    @Test
    void rejectsUnsafeOrMalformedOrigins() {
        assertThatThrownBy(() -> GithubBaseUrlValidator.normalize("http://github.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> GithubBaseUrlValidator.normalize("https://user@github.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> GithubBaseUrlValidator.normalize("https://github.example.com/path"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                GithubBaseUrlValidator.normalize(
                                        "https://github.example.com?query=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                GithubBaseUrlValidator.normalize(
                                        "https://github.example.com#fragment"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubBaseUrlValidator.normalize("https://github.example.com:"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> GithubBaseUrlValidator.normalize("https://github.example.com:8443"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without credentials, a port");
        assertThatThrownBy(
                        () -> GithubBaseUrlValidator.normalize("https://github.example.com:65536"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubBaseUrlValidator.normalize("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
