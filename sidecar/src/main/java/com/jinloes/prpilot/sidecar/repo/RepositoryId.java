package com.jinloes.prpilot.sidecar.repo;

/** An owner/repo pair identifying a GitHub repository, e.g. {@code jinloes/pr-pilot}. */
public record RepositoryId(String owner, String repo) {
    public String slug() {
        return owner + "/" + repo;
    }
}
