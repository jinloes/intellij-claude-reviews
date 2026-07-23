package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages temporary git worktrees for PR branch reviews.
 *
 * <p>A worktree lets Claude/Copilot read source files at the PR branch state rather than the user's
 * currently checked-out branch, improving review accuracy for type lookups and cross-file
 * references. The shared git object store means no re-clone is needed — only the working tree files
 * are written for the new worktree.
 */
public class GitWorktreeService {

    private static final Logger log = LoggerFactory.getLogger(GitWorktreeService.class);

    /**
     * Walks up from {@code startDir} to find the git repository root — the closest ancestor
     * (inclusive) that contains a {@code .git} entry. Returns null if no git root is found.
     *
     * <p>Wraps a canonicalization failure in {@link java.io.UncheckedIOException} rather than
     * declaring a checked {@link IOException}, matching the unchecked propagation callers relied on
     * from the former Kotlin implementation.
     */
    public File findGitRoot(File startDir) {
        File dir;
        try {
            dir = startDir.getCanonicalFile();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        while (dir != null) {
            if (new File(dir, ".git").exists()) return dir;
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * Creates a git worktree at {@code worktreeDir} checked out to {@code origin/<branch>}.
     *
     * <p>Runs {@code git fetch origin <branch>} first so the ref is current, then {@code git
     * worktree add --detach <worktreeDir> origin/<branch>}.
     *
     * @param repoDir git repository root (must contain {@code .git})
     * @param branch branch name on the origin remote (without the {@code origin/} prefix)
     * @param worktreeDir destination path for the worktree; must not exist
     * @throws IOException if a git command fails or times out
     */
    public void createWorktree(File repoDir, String branch, File worktreeDir) throws IOException {
        log.info("Fetching branch {} from origin in {}", branch, repoDir);
        runGit(repoDir, 60, "fetch", "origin", branch);
        log.info("Creating worktree at {} for origin/{}", worktreeDir, branch);
        runGit(
                repoDir,
                30,
                "worktree",
                "add",
                "--detach",
                worktreeDir.getAbsolutePath(),
                "origin/" + branch);
    }

    /**
     * Creates a git worktree at {@code worktreeDir} by fetching a branch from a fork's remote URL.
     * Use this for fork PRs where the branch is not available on {@code origin}.
     *
     * <p>Runs {@code git fetch <forkCloneUrl> <branch>} then {@code git worktree add --detach
     * <worktreeDir> FETCH_HEAD}.
     *
     * @param repoDir git repository root
     * @param forkCloneUrl HTTPS or SSH clone URL of the fork
     * @param branch branch name on the fork
     * @param worktreeDir destination path for the worktree; must not exist
     * @throws IOException if a git command fails or times out
     */
    public void createWorktreeFromFork(
            File repoDir, String forkCloneUrl, String branch, File worktreeDir) throws IOException {
        log.info("Fetching branch {} from fork {} in {}", branch, forkCloneUrl, repoDir);
        runGit(repoDir, 120, "fetch", forkCloneUrl, branch);
        log.info("Creating worktree at {} from FETCH_HEAD", worktreeDir);
        runGit(
                repoDir,
                30,
                "worktree",
                "add",
                "--detach",
                worktreeDir.getAbsolutePath(),
                "FETCH_HEAD");
    }

    /**
     * Removes a previously created worktree. Uses {@code --force} to tolerate a missing directory.
     * Logs a warning on failure but does not rethrow — cleanup failures are non-fatal.
     *
     * @param repoDir git repository root
     * @param worktreeDir the worktree directory to remove
     */
    public void removeWorktree(File repoDir, File worktreeDir) {
        try {
            runGit(repoDir, 30, "worktree", "remove", "--force", worktreeDir.getAbsolutePath());
            log.info("Removed worktree at {}", worktreeDir);
        } catch (Exception e) {
            log.warn("Failed to remove worktree at {}: {}", worktreeDir, e.getMessage());
        }
    }

    void runGit(File dir, long timeoutSeconds, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("HOME", System.getProperty("user.home", "/"));
        String existingPath = pb.environment().getOrDefault("PATH", "");
        pb.environment().put("PATH", "/opt/homebrew/bin:/usr/local/bin:" + existingPath);
        Process process = pb.start();
        String output =
                IOUtils.toString(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git " + args[0] + " interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " timed out after " + timeoutSeconds + "s");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String trimmed = output.trim();
            throw new IOException(
                    "git "
                            + args[0]
                            + " failed (exit "
                            + exitCode
                            + "): "
                            + trimmed.substring(0, Math.min(300, trimmed.length())));
        }
    }
}
