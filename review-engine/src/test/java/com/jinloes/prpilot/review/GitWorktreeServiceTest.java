package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for GitWorktreeService. */
class GitWorktreeServiceTest {

    private final GitWorktreeService service = new GitWorktreeService();

    private static void git(File dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes(); // drain
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + args[0] + " failed in " + dir);
        }
    }

    /** Returns the resolved HEAD commit of a repository or worktree. */
    private static String headSha(File dir) throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder("git", "rev-parse", "HEAD")
                        .directory(dir)
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git rev-parse failed in " + dir + ": " + output);
        }
        return output;
    }

    /** Initializes a minimal git repository with one commit on `main`. */
    private static void initBareRepo(File dir) throws IOException, InterruptedException {
        git(dir, "init", "-b", "main");
        git(dir, "config", "user.email", "test@test.com");
        git(dir, "config", "user.name", "Test");
        Files.writeString(new File(dir, "hello.txt").toPath(), "hello");
        git(dir, "add", ".");
        git(dir, "commit", "-m", "initial");
        // Point origin at the repo itself so `git fetch origin main` works locally.
        git(dir, "remote", "add", "origin", dir.getAbsolutePath());
    }

    @Nested
    class FindGitRoot {

        private File tmpDir;

        @BeforeEach
        void setUp() throws IOException {
            tmpDir = Files.createTempDirectory("gw-test-root").toFile();
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(tmpDir);
        }

        @Test
        void returnsNullForDirectoryWithNoGitAncestor() {
            assertThat(service.findGitRoot(tmpDir)).isNull();
        }

        @Test
        void returnsTheDirectoryItselfWhenGitIsAChild() throws IOException {
            new File(tmpDir, ".git").mkdirs();
            assertThat(service.findGitRoot(tmpDir)).isEqualTo(tmpDir.getCanonicalFile());
        }

        @Test
        void findsGitInAParentDirectory() throws IOException {
            new File(tmpDir, ".git").mkdirs();
            File nested = new File(tmpDir, "a/b/c");
            nested.mkdirs();
            assertThat(service.findGitRoot(nested)).isEqualTo(tmpDir.getCanonicalFile());
        }

        @Test
        void acceptsGitAsAFileGitWorktreeMetadata() throws IOException {
            Files.writeString(
                    new File(tmpDir, ".git").toPath(), "gitdir: ../../.git/worktrees/foo");
            assertThat(service.findGitRoot(tmpDir)).isEqualTo(tmpDir.getCanonicalFile());
        }
    }

    @Nested
    class CreateWorktree {

        private File repoDir;
        private File worktreeDir;

        @BeforeEach
        void setUp() throws Exception {
            repoDir = Files.createTempDirectory("gw-repo").toFile();
            worktreeDir = Files.createTempDirectory("gw-wt").toFile();
            worktreeDir.delete();
            initBareRepo(repoDir);
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(worktreeDir);
            FileUtils.deleteDirectory(repoDir);
        }

        @Test
        void createsAWorktreeOnAnExistingBranch() throws IOException {
            service.createWorktree(repoDir, "main", "", worktreeDir);
            assertThat(worktreeDir).exists();
            assertThat(new File(worktreeDir, "hello.txt")).exists();
        }

        @Test
        void throwsIOExceptionForANonExistentBranch() {
            assertThatThrownBy(
                            () ->
                                    service.createWorktree(
                                            repoDir, "does-not-exist", "", worktreeDir))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("git fetch");
        }

        /**
         * The point of pinning: the reviewed commit is checked out even after the branch moves on,
         * so the agent greps the code the diff was rendered from.
         */
        @Test
        void checksOutTheReviewedCommitEvenAfterTheBranchTipMoves() throws Exception {
            String reviewed = headSha(repoDir);
            Files.writeString(new File(repoDir, "pushed-later.txt").toPath(), "later");
            git(repoDir, "add", ".");
            git(repoDir, "commit", "-m", "pushed after review started");

            service.createWorktree(repoDir, "main", reviewed, worktreeDir);

            assertThat(new File(worktreeDir, "hello.txt")).exists();
            assertThat(new File(worktreeDir, "pushed-later.txt")).doesNotExist();
            assertThat(headSha(worktreeDir)).isEqualTo(reviewed);
        }

        @Test
        void fallsBackToTheBranchTipWhenTheReviewedCommitIsNotAvailable() throws Exception {
            git(repoDir, "commit", "--allow-empty", "-m", "tip");
            String tip = headSha(repoDir);

            service.createWorktree(
                    repoDir, "main", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", worktreeDir);

            assertThat(headSha(worktreeDir)).isEqualTo(tip);
        }

        @Test
        void fallsBackToTheBranchTipWhenNoShaIsSupplied() throws Exception {
            git(repoDir, "commit", "--allow-empty", "-m", "tip");
            String tip = headSha(repoDir);

            service.createWorktree(repoDir, "main", "  ", worktreeDir);

            assertThat(headSha(worktreeDir)).isEqualTo(tip);
        }
    }

    @Nested
    class PinnedCommitish {

        private File repoDir;

        @BeforeEach
        void setUp() throws Exception {
            repoDir = Files.createTempDirectory("gw-pin").toFile();
            initBareRepo(repoDir);
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(repoDir);
        }

        @Test
        void returnsTheShaWhenTheCommitIsPresent() throws Exception {
            String sha = headSha(repoDir);
            assertThat(service.pinnedCommitish(repoDir, sha, "origin/main")).isEqualTo(sha);
        }

        @Test
        void acceptsAnAbbreviatedSha() throws Exception {
            String abbreviated = headSha(repoDir).substring(0, 8);
            assertThat(service.pinnedCommitish(repoDir, abbreviated, "origin/main"))
                    .isEqualTo(abbreviated);
        }

        @Test
        void fallsBackWhenTheShaIsBlank() {
            assertThat(service.pinnedCommitish(repoDir, "", "origin/main"))
                    .isEqualTo("origin/main");
            assertThat(service.pinnedCommitish(repoDir, null, "FETCH_HEAD"))
                    .isEqualTo("FETCH_HEAD");
        }

        @Test
        void fallsBackWhenTheCommitIsUnknownLocally() {
            assertThat(
                            service.pinnedCommitish(
                                    repoDir,
                                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                    "origin/main"))
                    .isEqualTo("origin/main");
        }

        /**
         * headSha arrives from the GitHub API, so a value that git could read as an option or a
         * different revision must never reach the command line.
         */
        @Test
        void refusesRevisionsThatAreNotHexObjectNames() throws Exception {
            // A real ref that resolves, but is not an object name — must still be rejected.
            assertThat(service.pinnedCommitish(repoDir, "main", "origin/main"))
                    .isEqualTo("origin/main");
            assertThat(
                            service.pinnedCommitish(
                                    repoDir, "--upload-pack=touch /tmp/x", "origin/main"))
                    .isEqualTo("origin/main");
            assertThat(service.pinnedCommitish(repoDir, "HEAD", "origin/main"))
                    .isEqualTo("origin/main");
            assertThat(service.pinnedCommitish(repoDir, "abc", "origin/main"))
                    .isEqualTo("origin/main");
        }
    }

    @Nested
    class RemoveWorktree {

        private File repoDir;
        private File worktreeDir;

        @BeforeEach
        void setUp() throws Exception {
            repoDir = Files.createTempDirectory("gw-repo-rm").toFile();
            worktreeDir = Files.createTempDirectory("gw-wt-rm").toFile();
            worktreeDir.delete();
            initBareRepo(repoDir);
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(worktreeDir);
            FileUtils.deleteDirectory(repoDir);
        }

        @Test
        void removesAWorktreeThatWasCreated() throws IOException {
            service.createWorktree(repoDir, "main", "", worktreeDir);
            assertThat(worktreeDir).exists();
            service.removeWorktree(repoDir, worktreeDir);
            assertThat(worktreeDir).doesNotExist();
        }

        @Test
        void doesNotThrowWhenWorktreeDirectoryDoesNotExist() {
            File nonExistent = new File(repoDir, "no-such-wt");
            service.removeWorktree(repoDir, nonExistent);
        }
    }

    @Nested
    class RunGit {

        private File tmpDir;

        @BeforeEach
        void setUp() throws IOException {
            tmpDir = Files.createTempDirectory("gw-run").toFile();
        }

        @AfterEach
        void tearDown() throws IOException {
            FileUtils.deleteDirectory(tmpDir);
        }

        @Test
        void throwsIOExceptionWithExitCodeOnNonZeroGitCommand() {
            assertThatThrownBy(
                            () ->
                                    service.runGit(
                                            tmpDir,
                                            10,
                                            "rev-parse",
                                            "--verify",
                                            "nonexistent-ref-xyz"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("git rev-parse");
        }
    }
}
