package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages temporary git worktrees for PR branch reviews.
 *
 * <p>A worktree lets Claude/Copilot read source files at the PR branch state rather than the user's
 * currently checked-out branch, improving review accuracy for type lookups and cross-file
 * references. The shared git object store means no re-clone is needed — only the working tree files
 * are written for the new worktree.
 *
 * <p>Worktrees are pinned to the PR's head commit rather than its branch tip, so the tree the agent
 * reads matches the diff being reviewed even if the contributor pushes mid-review.
 */
public class GitWorktreeService {

    private static final Logger log = LoggerFactory.getLogger(GitWorktreeService.class);

    /** Abbreviated or full hex object name; anything else is never passed to git as a revision. */
    private static final Pattern HEX_OBJECT_NAME = Pattern.compile("[0-9a-fA-F]{7,64}");

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
     * Creates a git worktree at {@code worktreeDir} pinned to {@code headSha}, the commit the
     * reviewed diff was rendered at.
     *
     * <p>Runs {@code git fetch origin <branch>} first so the commit is available, then {@code git
     * worktree add --detach <worktreeDir> <headSha>}, falling back to {@code origin/<branch>} when
     * the SHA is unusable (see {@link #pinnedCommitish}).
     *
     * @param repoDir git repository root (must contain {@code .git})
     * @param branch branch name on the origin remote (without the {@code origin/} prefix)
     * @param headSha PR head commit the diff was rendered at; blank falls back to the branch tip
     * @param worktreeDir destination path for the worktree; must not exist
     * @throws IOException if a git command fails or times out
     */
    public void createWorktree(File repoDir, String branch, String headSha, File worktreeDir)
            throws IOException {
        log.info("Fetching branch {} from origin in {}", branch, repoDir);
        runGit(repoDir, 60, "fetch", "origin", branch);
        String commitish = pinnedCommitish(repoDir, headSha, "origin/" + branch);
        log.info("Creating worktree at {} for {}", worktreeDir, commitish);
        runGit(
                repoDir,
                30,
                "worktree",
                "add",
                "--detach",
                worktreeDir.getAbsolutePath(),
                commitish);
    }

    /**
     * Creates a git worktree at {@code worktreeDir} by fetching a branch from a fork's remote URL.
     * Use this for fork PRs where the branch is not available on {@code origin}.
     *
     * <p>Runs {@code git fetch <forkCloneUrl> <branch>} then pins to {@code headSha}, falling back
     * to {@code FETCH_HEAD}. Forks need the same pinning as origin branches: {@code FETCH_HEAD} is
     * the fork branch's tip at fetch time, which is exactly the moving target being avoided.
     *
     * @param repoDir git repository root
     * @param forkCloneUrl HTTPS or SSH clone URL of the fork
     * @param branch branch name on the fork
     * @param headSha PR head commit the diff was rendered at; blank falls back to {@code
     *     FETCH_HEAD}
     * @param worktreeDir destination path for the worktree; must not exist
     * @throws IOException if a git command fails or times out
     */
    public void createWorktreeFromFork(
            File repoDir, String forkCloneUrl, String branch, String headSha, File worktreeDir)
            throws IOException {
        log.info("Fetching branch {} from fork {} in {}", branch, forkCloneUrl, repoDir);
        runGit(repoDir, 120, "fetch", forkCloneUrl, branch);
        String commitish = pinnedCommitish(repoDir, headSha, "FETCH_HEAD");
        log.info("Creating worktree at {} from {}", worktreeDir, commitish);
        runGit(
                repoDir,
                30,
                "worktree",
                "add",
                "--detach",
                worktreeDir.getAbsolutePath(),
                commitish);
    }

    /**
     * Returns {@code headSha} when the fetch made that exact commit available locally, otherwise
     * {@code branchTipRef}.
     *
     * <p>The reviewed diff was rendered at {@code headSha}, but a branch tip is a *moving* target:
     * a push between rendering the diff and building the worktree would otherwise leave the agent
     * grepping code that is not under review. Normally the tip is a descendant of the reviewed
     * commit, so the fetch brings the commit along and pinning succeeds.
     *
     * <p>Falls back rather than failing. A force-push can orphan the reviewed commit, and a
     * slightly-stale worktree is still far better than the alternative — callers treat worktree
     * creation failure as "use the user's own checkout", which is a much worse tree to read.
     */
    String pinnedCommitish(File repoDir, String headSha, String branchTipRef) {
        if (StringUtils.isBlank(headSha)) return branchTipRef;
        if (!HEX_OBJECT_NAME.matcher(headSha).matches()) {
            // headSha comes from the GitHub API response; never hand git an argument that could
            // be read as an option or a different revision.
            log.warn("Ignoring malformed head SHA '{}'; using {}", headSha, branchTipRef);
            return branchTipRef;
        }
        if (commitExists(repoDir, headSha)) return headSha;
        log.warn(
                "Head commit {} unavailable after fetch (force-push?); using {} instead",
                headSha,
                branchTipRef);
        return branchTipRef;
    }

    private boolean commitExists(File repoDir, String sha) {
        try {
            return execGit(repoDir, 15, "cat-file", "-e", sha + "^{commit}").exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns a unique temp path for a PR's worktree. The directory must not exist when the
     * worktree is created, so the name carries both a timestamp and randomness — rapid consecutive
     * calls for the same PR would otherwise collide within a millisecond.
     *
     * <p>Lives here rather than in each host because the two hosts had drifted to different name
     * formats, and the cleanup path matches on the {@code pr-pilot-wt-} prefix.
     */
    public File newWorktreePath(int prNumber) {
        String unique =
                prNumber
                        + "-"
                        + System.currentTimeMillis()
                        + "-"
                        + Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
        return new File(System.getProperty("java.io.tmpdir"), "pr-pilot-wt-" + unique);
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
        GitResult result = execGit(dir, timeoutSeconds, args);
        if (result.exitCode() != 0) {
            String trimmed = result.output().trim();
            throw new IOException(
                    "git "
                            + args[0]
                            + " failed (exit "
                            + result.exitCode()
                            + "): "
                            + trimmed.substring(0, Math.min(300, trimmed.length())));
        }
    }

    /** Exit code and combined output of a git invocation. */
    private record GitResult(int exitCode, String output) {}

    /**
     * Runs git and returns its exit code instead of throwing, so callers can probe for a condition
     * (such as whether a commit exists) without treating a non-zero exit as an error.
     */
    private GitResult execGit(File dir, long timeoutSeconds, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("HOME", System.getProperty("user.home", "/"));
        String existingPath = pb.environment().getOrDefault("PATH", "");
        pb.environment().put("PATH", "/opt/homebrew/bin:/usr/local/bin:" + existingPath);
        Process process = startGitProcess(pb);
        CompletableFuture<String> outputFuture =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return IOUtils.toString(
                                        process.getInputStream(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            outputFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("git " + args[0] + " interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            outputFuture.cancel(true);
            throw new IOException("git " + args[0] + " timed out after " + timeoutSeconds + "s");
        }
        try {
            return new GitResult(process.exitValue(), outputFuture.get(5, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.io.UncheckedIOException unchecked) {
                throw unchecked.getCause();
            }
            throw new IOException("Failed to read git " + args[0] + " output", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git " + args[0] + " interrupted", e);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            throw new IOException("Timed out reading git " + args[0] + " output", e);
        }
    }

    Process startGitProcess(ProcessBuilder processBuilder) throws IOException {
        return processBuilder.start();
    }
}
