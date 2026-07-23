package com.jinloes.prpilot.sidecar.repo;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Detects the owner/repo for a project directory by reading local git metadata directly — no git
 * process is spawned.
 *
 * <p>Handles both a standard {@code .git} directory and a linked-worktree {@code .git} file
 * (containing {@code gitdir: <path>}), ascending from the given path the same way git itself
 * resolves the repository root for a subdirectory checkout.
 */
public final class RepoDetector {
    private final GitDirectoryResolver gitDirectoryResolver;
    private final GitConfigOriginReader originReader;
    private final RemoteUrlParser remoteUrlParser;

    public RepoDetector() {
        this(new GitDirectoryResolver(), new GitConfigOriginReader(), new RemoteUrlParser());
    }

    RepoDetector(
            GitDirectoryResolver gitDirectoryResolver,
            GitConfigOriginReader originReader,
            RemoteUrlParser remoteUrlParser) {
        this.gitDirectoryResolver = gitDirectoryResolver;
        this.originReader = originReader;
        this.remoteUrlParser = remoteUrlParser;
    }

    public DetectResult detect(String path) {
        if (path == null || path.isBlank()) {
            return DetectResult.of(DetectStatus.INVALID_PATH);
        }

        Path start;
        try {
            start = Path.of(path);
        } catch (InvalidPathException exception) {
            return DetectResult.of(DetectStatus.INVALID_PATH);
        }

        if (!start.isAbsolute() || !Files.isDirectory(start)) {
            return DetectResult.of(DetectStatus.INVALID_PATH);
        }

        GitDirectoryResolver.Resolution gitDirResolution =
                gitDirectoryResolver.resolve(start.toAbsolutePath().normalize());
        DetectStatus gitDirFailure = mapGitDirFailure(gitDirResolution.status());
        if (gitDirFailure != null) {
            return DetectResult.of(gitDirFailure);
        }

        GitConfigOriginReader.Lookup originLookup =
                originReader.readOriginUrl(gitDirResolution.gitDir());
        DetectStatus originFailure = mapOriginFailure(originLookup.status());
        if (originFailure != null) {
            return DetectResult.of(originFailure);
        }

        return remoteUrlParser
                .parse(originLookup.url())
                .map(DetectResult::found)
                .orElseGet(() -> DetectResult.of(DetectStatus.ORIGIN_URL_MALFORMED));
    }

    private DetectStatus mapGitDirFailure(GitDirectoryResolver.Status status) {
        return switch (status) {
            case RESOLVED -> null;
            case NOT_GIT -> DetectStatus.NOT_GIT;
            case GITDIR_MALFORMED -> DetectStatus.GITDIR_MALFORMED;
            case GITDIR_UNREADABLE -> DetectStatus.GITDIR_UNREADABLE;
        };
    }

    private DetectStatus mapOriginFailure(GitConfigOriginReader.Status status) {
        return switch (status) {
            case FOUND -> null;
            case CONFIG_MISSING -> DetectStatus.CONFIG_MISSING;
            case CONFIG_UNREADABLE -> DetectStatus.CONFIG_UNREADABLE;
            case ORIGIN_MISSING -> DetectStatus.ORIGIN_MISSING;
        };
    }
}
