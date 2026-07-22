package com.jinloes.prpilot.sidecar.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoDetectorTest {
    private final RepoDetector detector = new RepoDetector();

    @Test
    void detectsAnHttpsOriginFromAStandardGitDirectory(@TempDir Path repoRoot) throws IOException {
        writeConfig(gitDir(repoRoot), "https://github.com/acme/widgets.git");

        DetectResult result = detector.detect(repoRoot.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.FOUND);
        assertThat(result.repository()).isEqualTo(new RepositoryId("acme", "widgets"));
    }

    @Test
    void findsTheRepoRootWhenStartingFromANestedSubdirectory(@TempDir Path repoRoot)
            throws IOException {
        writeConfig(gitDir(repoRoot), "git@github.com:acme/widgets.git");
        Path nested = repoRoot.resolve("src/main/java");
        Files.createDirectories(nested);

        DetectResult result = detector.detect(nested.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.FOUND);
        assertThat(result.repository()).isEqualTo(new RepositoryId("acme", "widgets"));
    }

    @Test
    void resolvesARelativeGitdirLinkedWorktreeFile(@TempDir Path root) throws IOException {
        Path mainRepo = root.resolve("main");
        Path actualGitDir = mainRepo.resolve(".git");
        writeConfig(actualGitDir, "https://github.com/acme/widgets.git");

        Path worktree = root.resolve("worktrees/feature-branch");
        Files.createDirectories(worktree);
        Files.writeString(worktree.resolve(".git"), "gitdir: ../../main/.git\n");

        DetectResult result = detector.detect(worktree.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.FOUND);
        assertThat(result.repository()).isEqualTo(new RepositoryId("acme", "widgets"));
    }

    @Test
    void resolvesAnAbsoluteGitdirLinkedWorktreeWithAnSshUriOrigin(@TempDir Path root)
            throws IOException {
        Path mainRepo = root.resolve("main");
        Path actualGitDir = mainRepo.resolve(".git");
        writeConfig(actualGitDir, "ssh://git@github.example.com:2222/acme/widgets.git");

        Path worktree = root.resolve("worktrees/feature-branch");
        Files.createDirectories(worktree);
        Files.writeString(
                worktree.resolve(".git"), "gitdir: " + actualGitDir.toAbsolutePath() + "\n");

        DetectResult result = detector.detect(worktree.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.FOUND);
        assertThat(result.repository()).isEqualTo(new RepositoryId("acme", "widgets"));
    }

    @Test
    void returnsInvalidPathForARelativeOrMissingDirectory() {
        assertThat(detector.detect("relative/path").status()).isEqualTo(DetectStatus.INVALID_PATH);
        assertThat(detector.detect(null).status()).isEqualTo(DetectStatus.INVALID_PATH);
        assertThat(detector.detect("").status()).isEqualTo(DetectStatus.INVALID_PATH);
    }

    @Test
    void returnsInvalidPathWhenTheDirectoryDoesNotExist(@TempDir Path root) {
        DetectResult result = detector.detect(root.resolve("does-not-exist").toString());

        assertThat(result.status()).isEqualTo(DetectStatus.INVALID_PATH);
    }

    @Test
    void returnsNotGitWhenNoAncestorHasAGitEntry(@TempDir Path root) throws IOException {
        Path plainDir = root.resolve("not-a-repo");
        Files.createDirectories(plainDir);

        DetectResult result = detector.detect(plainDir.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.NOT_GIT);
    }

    @Test
    void returnsConfigMissingWhenTheGitDirectoryHasNoConfigFile(@TempDir Path repoRoot)
            throws IOException {
        Files.createDirectories(gitDir(repoRoot));

        DetectResult result = detector.detect(repoRoot.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.CONFIG_MISSING);
    }

    @Test
    void returnsOriginMissingWhenConfigHasNoOriginUrl(@TempDir Path repoRoot) throws IOException {
        Path gitDir = gitDir(repoRoot);
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("config"), "[core]\n\tbare = false\n");

        DetectResult result = detector.detect(repoRoot.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.ORIGIN_MISSING);
    }

    @Test
    void returnsOriginUrlMalformedForAnUnparseableUrl(@TempDir Path repoRoot) throws IOException {
        writeConfig(gitDir(repoRoot), "not-a-valid-remote-url");

        DetectResult result = detector.detect(repoRoot.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.ORIGIN_URL_MALFORMED);
    }

    @Test
    void returnsGitdirMalformedWhenTheGitFileHasNoGitdirLine(@TempDir Path repoRoot)
            throws IOException {
        Files.writeString(repoRoot.resolve(".git"), "not a gitdir line\n");

        DetectResult result = detector.detect(repoRoot.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.GITDIR_MALFORMED);
    }

    @Test
    void returnsGitdirUnreadableWhenTheTargetDirectoryDoesNotExist(@TempDir Path repoRoot)
            throws IOException {
        Files.writeString(repoRoot.resolve(".git"), "gitdir: ../does-not-exist\n");

        DetectResult result = detector.detect(repoRoot.toString());

        assertThat(result.status()).isEqualTo(DetectStatus.GITDIR_UNREADABLE);
    }

    private Path gitDir(Path repoRoot) {
        return repoRoot.resolve(".git");
    }

    private void writeConfig(Path gitDir, String originUrl) throws IOException {
        Files.createDirectories(gitDir);
        Files.writeString(
                gitDir.resolve("config"),
                "[remote \"origin\"]\n\turl = " + originUrl + "\n",
                StandardCharsets.UTF_8);
    }
}
