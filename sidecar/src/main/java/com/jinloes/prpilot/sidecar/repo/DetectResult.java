package com.jinloes.prpilot.sidecar.repo;

/**
 * Result of a repo-detection attempt. {@code repository} is non-null only when {@code status} is
 * {@link DetectStatus#FOUND}.
 */
public record DetectResult(DetectStatus status, RepositoryId repository) {
    static DetectResult found(RepositoryId repository) {
        return new DetectResult(DetectStatus.FOUND, repository);
    }

    static DetectResult of(DetectStatus status) {
        return new DetectResult(status, null);
    }
}
