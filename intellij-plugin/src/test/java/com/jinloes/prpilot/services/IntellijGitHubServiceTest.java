package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.sidecar.pr.PullRequestSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntellijGitHubServiceTest {

    @Test
    void toPullRequestsMapsSharedEngineResultsToHostModels() {
        List<PullRequest> result =
                IntellijGitHubService.toPullRequests(
                        List.of(
                                new PullRequestSummary(
                                        42,
                                        "Fix the race",
                                        "acme",
                                        "widgets",
                                        "octocat",
                                        "2026-07-22T12:00:00Z",
                                        "https://github.com/acme/widgets/pull/42",
                                        true)));

        assertThat(result).hasSize(1);
        PullRequest pullRequest = result.get(0);
        assertThat(pullRequest.getNumber()).isEqualTo(42);
        assertThat(pullRequest.getTitle()).isEqualTo("Fix the race");
        assertThat(pullRequest.getOwner()).isEqualTo("acme");
        assertThat(pullRequest.getRepo()).isEqualTo("widgets");
        assertThat(pullRequest.getAuthor()).isEqualTo("octocat");
        assertThat(pullRequest.getCreatedAt()).isEqualTo("2026-07-22T12:00:00Z");
        assertThat(pullRequest.getHtmlUrl()).isEqualTo("https://github.com/acme/widgets/pull/42");
        assertThat(pullRequest.isDraft()).isTrue();
    }
}
