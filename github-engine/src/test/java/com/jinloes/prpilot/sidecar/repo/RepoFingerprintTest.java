package com.jinloes.prpilot.sidecar.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RepoFingerprintTest {

    private Path tempDir;
    private final RepoFingerprint fingerprint = new RepoFingerprint();

    @BeforeEach
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("repo-fingerprint");
    }

    @AfterEach
    void deleteTempDir() throws IOException {
        if (tempDir == null) return;
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private void touch(String name) throws IOException {
        Files.writeString(tempDir.resolve(name), "");
    }

    @Nested
    class Profile {
        @Test
        void detectsLanguageAndBuildToolFromAMarkerFile() throws IOException {
            touch("pom.xml");

            RepoProfileResult result = fingerprint.profile(tempDir.toString());

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.languages()).containsExactly("Java");
            assertThat(result.buildTools()).containsExactly("Maven");
            assertThat(result.summary())
                    .contains("Languages: Java", "Build tooling: Maven", "Detected from: pom.xml");
        }

        @Test
        void reportsPolyglotRepositoriesWithoutDuplicatingTooling() throws IOException {
            touch("build.gradle");
            touch("settings.gradle");
            touch("package.json");

            RepoProfileResult result = fingerprint.profile(tempDir.toString());

            assertThat(result.languages()).containsExactly("Java", "JavaScript/TypeScript");
            assertThat(result.buildTools()).containsExactly("Gradle", "npm");
        }

        @Test
        void recordsBuildToolingThatImpliesNoLanguage() throws IOException {
            touch("Dockerfile");

            RepoProfileResult result = fingerprint.profile(tempDir.toString());

            assertThat(result.languages()).isEmpty();
            assertThat(result.buildTools()).containsExactly("Docker");
            assertThat(result.summary()).doesNotContain("Languages:").contains("Docker");
        }

        @Test
        void ignoresMarkersNestedBelowTheRoot() throws IOException {
            Files.createDirectory(tempDir.resolve("sub"));
            Files.writeString(tempDir.resolve("sub").resolve("pom.xml"), "");

            assertThat(fingerprint.profile(tempDir.toString()).summary()).isEmpty();
        }

        @Test
        void ignoresADirectoryNamedLikeAMarkerFile() throws IOException {
            Files.createDirectory(tempDir.resolve("Makefile"));

            assertThat(fingerprint.profile(tempDir.toString()).buildTools()).isEmpty();
        }

        @Test
        void returnsAnEmptyProfileRatherThanFailingForUnknownTrees() {
            RepoProfileResult result = fingerprint.profile(tempDir.toString());

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.summary()).isEmpty();
            assertThat(result.message()).contains("No recognized");
        }

        @Test
        void handlesMissingBlankAndNullDirectories() {
            assertThat(fingerprint.profile(null).status()).isEqualTo("ok");
            assertThat(fingerprint.profile("  ").summary()).isEmpty();
            assertThat(fingerprint.profile(tempDir.resolve("absent").toString()).message())
                    .contains("does not exist");
        }
    }
}
