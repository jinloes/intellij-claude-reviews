package com.jinloes.prpilot.sidecar.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemoteUrlParserTest {
    private final RemoteUrlParser parser = new RemoteUrlParser();

    @Test
    void parsesAnHttpsUrl() {
        assertThat(parser.parse("https://github.com/acme/widgets.git"))
                .contains(new RepositoryId("acme", "widgets"));
    }

    @Test
    void parsesAnHttpsUrlOnAGitHubEnterpriseHost() {
        assertThat(parser.parse("https://github.example.com/acme/widgets"))
                .contains(new RepositoryId("acme", "widgets"));
    }

    @Test
    void parsesAnScpStyleSshUrl() {
        assertThat(parser.parse("git@github.com:acme/widgets.git"))
                .contains(new RepositoryId("acme", "widgets"));
    }

    @Test
    void parsesAnSshUriWithAPort() {
        assertThat(parser.parse("ssh://git@github.example.com:2222/acme/widgets.git"))
                .contains(new RepositoryId("acme", "widgets"));
    }

    @Test
    void rejectsScpStyleUrlsWithExtraPathSegments() {
        assertThat(parser.parse("git@github.com:acme/widgets/extra.git")).isEmpty();
    }

    @Test
    void rejectsUrlsMissingAnOwnerOrRepoSegment() {
        assertThat(parser.parse("https://github.com/acme")).isEmpty();
        assertThat(parser.parse("https://github.com/")).isEmpty();
    }

    @Test
    void rejectsBlankOrNullInput() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    void rejectsUnparseableStrings() {
        Optional<RepositoryId> result = parser.parse("not-a-url");
        assertThat(result).isEmpty();
    }
}
