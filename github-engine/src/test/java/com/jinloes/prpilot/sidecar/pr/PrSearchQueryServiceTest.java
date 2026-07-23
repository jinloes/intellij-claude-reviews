package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrSearchQueryServiceTest {
    private final PrSearchQueryService service = new PrSearchQueryService();

    @Test
    void buildsCurrentRepositoryQueriesForEachState() {
        assertThat(query("open", "currentRepo", "acme/platform"))
                .isEqualTo("is:pr is:open repo:acme/platform");
        assertThat(query("closed", "currentRepo", "acme/platform"))
                .isEqualTo("is:pr is:closed repo:acme/platform");
        assertThat(query("all", "currentRepo", "acme/platform"))
                .isEqualTo("is:pr repo:acme/platform");
    }

    @Test
    void fallsBackToAuthoredPullRequestsWithoutACurrentRepository() {
        assertThat(query("open", "currentRepo", null)).isEqualTo("is:pr is:open author:@me");
        assertThat(query("open", "currentRepo", "   ")).isEqualTo("is:pr is:open author:@me");
    }

    @Test
    void buildsNonRepositoryScopedQueries() {
        assertThat(query("open", "reviewRequested", "acme/platform"))
                .isEqualTo("is:pr is:open review-requested:@me");
        assertThat(query("open", "assigned", "acme/platform"))
                .isEqualTo("is:pr is:open assignee:@me");
        assertThat(query("open", "authored", "acme/platform"))
                .isEqualTo("is:pr is:open author:@me");
    }

    @Test
    void normalizesUnknownOrMissingStateAndScope() {
        assertThat(query(null, null, "acme/platform"))
                .isEqualTo("is:pr is:open repo:acme/platform");
        assertThat(query("merged", "not-a-scope", "acme/platform"))
                .isEqualTo("is:pr is:open repo:acme/platform");
    }

    private String query(String state, String scope, String currentRepo) {
        return service.build(new PrSearchQueryService.QueryParams(state, scope, currentRepo))
                .query();
    }
}
