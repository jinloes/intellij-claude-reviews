package com.jinloes.prpilot.sidecar.repo;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects a repository's language and build system from marker files at the root of a working tree.
 *
 * <p>Deliberately shallow: it stats a fixed list of well-known filenames in the top directory only.
 * A recursive scan would be slower, would need ignore-file handling, and would not meaningfully
 * improve the answer for the question being asked ("what stack is this?").
 *
 * <p>Reads nothing but file existence, so it never executes project code and cannot be influenced
 * by file contents.
 */
public final class RepoFingerprint {

    /** Marker file to the (language, build tool) it implies. */
    private static final List<Marker> MARKERS =
            List.of(
                    new Marker("pom.xml", "Java", "Maven"),
                    new Marker("build.gradle", "Java", "Gradle"),
                    new Marker("build.gradle.kts", "Kotlin", "Gradle"),
                    new Marker("settings.gradle", "Java", "Gradle"),
                    new Marker("settings.gradle.kts", "Kotlin", "Gradle"),
                    new Marker("package.json", "JavaScript/TypeScript", "npm"),
                    new Marker("deno.json", "TypeScript", "Deno"),
                    new Marker("tsconfig.json", "TypeScript", null),
                    new Marker("go.mod", "Go", "Go modules"),
                    new Marker("Cargo.toml", "Rust", "Cargo"),
                    new Marker("pyproject.toml", "Python", "PEP 517"),
                    new Marker("setup.py", "Python", "setuptools"),
                    new Marker("requirements.txt", "Python", "pip"),
                    new Marker("Gemfile", "Ruby", "Bundler"),
                    new Marker("composer.json", "PHP", "Composer"),
                    new Marker("Package.swift", "Swift", "SwiftPM"),
                    new Marker("pubspec.yaml", "Dart", "pub"),
                    new Marker("mix.exs", "Elixir", "Mix"),
                    new Marker("CMakeLists.txt", "C/C++", "CMake"),
                    new Marker("Makefile", null, "Make"),
                    new Marker("Dockerfile", null, "Docker"));

    /** Detects the profile of the working tree rooted at {@code path}. */
    public RepoProfileResult profile(String path) {
        if (path == null || path.isBlank()) {
            return RepoProfileResult.none("No project directory was provided.");
        }
        Path root;
        try {
            root = Path.of(path);
        } catch (InvalidPathException exception) {
            return RepoProfileResult.none("Project directory path is invalid.");
        }
        if (!Files.isDirectory(root)) {
            return RepoProfileResult.none("Project directory does not exist.");
        }

        Set<String> languages = new LinkedHashSet<>();
        Set<String> buildTools = new LinkedHashSet<>();
        List<String> found = new ArrayList<>();
        for (Marker marker : MARKERS) {
            if (!Files.isRegularFile(root.resolve(marker.file()))) continue;
            found.add(marker.file());
            if (marker.language() != null) languages.add(marker.language());
            if (marker.buildTool() != null) buildTools.add(marker.buildTool());
        }
        if (found.isEmpty()) {
            return RepoProfileResult.none("No recognized build or manifest files were found.");
        }

        List<String> languageList = List.copyOf(languages);
        List<String> buildToolList = List.copyOf(buildTools);
        return RepoProfileResult.success(
                languageList, buildToolList, render(languageList, buildToolList, found));
    }

    private static String render(
            List<String> languages, List<String> buildTools, List<String> markerFiles) {
        StringBuilder text = new StringBuilder();
        if (!languages.isEmpty()) {
            text.append("Languages: ").append(String.join(", ", languages));
        }
        if (!buildTools.isEmpty()) {
            if (text.length() > 0) text.append("\n");
            text.append("Build tooling: ").append(String.join(", ", buildTools));
        }
        if (text.length() > 0) text.append("\n");
        text.append("Detected from: ").append(String.join(", ", markerFiles));
        return text.toString();
    }

    /** A root-level filename and what its presence implies; either implication may be absent. */
    private record Marker(String file, String language, String buildTool) {}
}
