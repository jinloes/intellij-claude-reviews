package com.jinloes.prpilot.sidecar.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the actual git metadata directory (the directory containing {@code config}, {@code
 * HEAD}, etc.) for a given starting directory.
 *
 * <p>Ascends from the starting directory looking for a {@code .git} entry. Supports both a standard
 * {@code .git} directory and a linked-worktree {@code .git} <em>file</em> containing a {@code
 * gitdir: <path>} line (the path may be relative to the file's parent directory, or absolute) —
 * neither the IntelliJ nor the VS Code host currently understands the linked-worktree case.
 */
final class GitDirectoryResolver {
    /** Safety bound on ancestor traversal to avoid pathological filesystem loops. */
    private static final int MAX_ANCESTORS = 1024;

    private static final String GITDIR_PREFIX = "gitdir:";

    enum Status {
        RESOLVED,
        NOT_GIT,
        GITDIR_MALFORMED,
        GITDIR_UNREADABLE
    }

    record Resolution(Status status, Path gitDir) {
        static Resolution resolved(Path gitDir) {
            return new Resolution(Status.RESOLVED, gitDir);
        }

        static Resolution of(Status status) {
            return new Resolution(status, null);
        }
    }

    Resolution resolve(Path startDir) {
        Path dir = startDir;
        int steps = 0;
        while (dir != null && steps++ < MAX_ANCESTORS) {
            Path gitEntry = dir.resolve(".git");
            if (Files.isDirectory(gitEntry)) {
                return Resolution.resolved(gitEntry);
            }
            if (Files.isRegularFile(gitEntry)) {
                return resolveGitdirFile(gitEntry);
            }
            dir = dir.getParent();
        }
        return Resolution.of(Status.NOT_GIT);
    }

    private Resolution resolveGitdirFile(Path gitFile) {
        String content;
        try {
            content = Files.readString(gitFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return Resolution.of(Status.GITDIR_UNREADABLE);
        }

        String trimmed = content.strip();
        if (!trimmed.startsWith(GITDIR_PREFIX)) {
            return Resolution.of(Status.GITDIR_MALFORMED);
        }

        String rawPath = trimmed.substring(GITDIR_PREFIX.length()).strip();
        if (rawPath.isEmpty()) {
            return Resolution.of(Status.GITDIR_MALFORMED);
        }

        Path target = Path.of(rawPath);
        Path resolved =
                target.isAbsolute()
                        ? target.normalize()
                        : gitFile.getParent().resolve(target).normalize();

        if (!Files.isDirectory(resolved)) {
            return Resolution.of(Status.GITDIR_UNREADABLE);
        }
        return Resolution.resolved(resolved);
    }
}
