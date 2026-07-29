package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Resolves and reads a repository's review-guidance docs from a working directory (the PR-branch
 * worktree or the open project) so the model can weight findings against the project's own
 * conventions.
 *
 * <p>The set of files is <b>configurable</b> rather than hardcoded: each entry is either a literal
 * relative path (e.g. {@code AGENTS.md}, {@code .linkedin/ai-agent/coding-pattern.md}) or a glob
 * (e.g. {@code **}{@code /style.md}, {@code .linkedin/**}{@code /*.md}). This lets teams surface
 * repo-specific guidance without a code change. Shared by both hosts: IntelliJ calls it directly;
 * the VS Code extension mirrors the logic in {@code guidelines.ts}.
 */
public final class RepoGuidelinesReader {

    /** Default guidance files scanned when the user has not configured their own list. */
    public static final List<String> DEFAULT_GUIDANCE_GLOBS =
            List.of(
                    "AGENTS.md",
                    "CONTRIBUTING.md",
                    ".github/CONTRIBUTING.md",
                    "docs/CONTRIBUTING.md",
                    ".github/pull_request_template.md");

    /** Cap on total guidance bytes fed to the prompt so a large doc can't blow up the context. */
    static final int MAX_GUIDELINES_BYTES = 6000;

    private static final int MAX_FILES_SCANNED = 5000;
    private static final int MAX_DEPTH = 8;
    private static final Set<String> SKIP_DIRS =
            Set.of(
                    ".git",
                    "node_modules",
                    "build",
                    "dist",
                    "target",
                    "out",
                    ".gradle",
                    ".idea",
                    ".venv",
                    "venv");

    private RepoGuidelinesReader() {}

    /**
     * Reads the guidance files matching {@code globs} under {@code dir}, concatenated and capped at
     * {@link #MAX_GUIDELINES_BYTES}. Returns an empty string when {@code dir} is null/missing or
     * nothing matches. A blank/empty {@code globs} falls back to {@link #DEFAULT_GUIDANCE_GLOBS}.
     */
    public static String read(File dir, List<String> globs) {
        if (dir == null || !dir.isDirectory()) {
            return "";
        }
        List<String> patterns = (globs == null || globs.isEmpty()) ? DEFAULT_GUIDANCE_GLOBS : globs;
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (String rel : resolvePaths(dir, patterns)) {
            if (total >= MAX_GUIDELINES_BYTES) {
                break;
            }
            File f = new File(dir, rel);
            if (!f.isFile()) {
                continue;
            }
            try {
                String content = Files.readString(f.toPath()).trim();
                if (content.isEmpty()) {
                    continue;
                }
                int remaining = MAX_GUIDELINES_BYTES - total;
                if (content.length() > remaining) {
                    content = content.substring(0, remaining) + "\n...(truncated)";
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("## ").append(rel).append("\n").append(content);
                total += content.length();
            } catch (IOException e) {
                // unreadable — skip
            }
        }
        return sb.toString();
    }

    /**
     * Resolves {@code patterns} to a de-duplicated, priority-ordered list of relative paths under
     * {@code dir}. Literal paths are checked directly; glob patterns are matched against a bounded
     * walk of the directory tree (matches sorted for determinism).
     */
    static List<String> resolvePaths(File dir, List<String> patterns) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        List<String> allFiles = null;
        for (String raw : patterns) {
            String pattern = StringUtils.strip(raw);
            if (StringUtils.isBlank(pattern)) {
                continue;
            }
            pattern = pattern.replace('\\', '/');
            if (isGlob(pattern)) {
                if (allFiles == null) {
                    allFiles = collectRelativeFiles(dir);
                }
                Pattern re = Pattern.compile(globToRegex(pattern));
                allFiles.stream()
                        .filter(f -> re.matcher(f).matches())
                        .sorted()
                        .forEach(ordered::add);
            } else if (new File(dir, pattern).isFile()) {
                ordered.add(pattern);
            }
        }
        return new ArrayList<>(ordered);
    }

    static boolean isGlob(String pattern) {
        return StringUtils.containsAny(pattern, '*', '?', '[', '{');
    }

    /**
     * Bounded breadth-first walk returning '/'-joined relative paths, skipping heavy directories.
     */
    private static List<String> collectRelativeFiles(File root) {
        List<String> files = new ArrayList<>();
        Deque<Entry> queue = new ArrayDeque<>();
        queue.add(new Entry(root, "", 0));
        while (!queue.isEmpty() && files.size() < MAX_FILES_SCANNED) {
            Entry entry = queue.poll();
            File[] children = entry.dir.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                String rel =
                        entry.prefix.isEmpty()
                                ? child.getName()
                                : entry.prefix + "/" + child.getName();
                if (child.isDirectory()) {
                    if (entry.depth < MAX_DEPTH && !SKIP_DIRS.contains(child.getName())) {
                        queue.add(new Entry(child, rel, entry.depth + 1));
                    }
                } else if (child.isFile()) {
                    files.add(rel);
                    if (files.size() >= MAX_FILES_SCANNED) {
                        break;
                    }
                }
            }
        }
        return files;
    }

    /**
     * Translates a minimal glob ({@code **}, {@code *}, {@code ?}) into a regex matched against a
     * '/'-joined relative path. {@code **}{@code /} matches zero or more leading segments so {@code
     * **}{@code /style.md} also matches {@code style.md} at the root.
     */
    static String globToRegex(String glob) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < n && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    i += 2;
                    if (i < n && glob.charAt(i) == '/') {
                        i++;
                        re.append("(?:.*/)?");
                    } else {
                        re.append(".*");
                    }
                    continue;
                }
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                re.append('\\').append(c);
            } else {
                re.append(c);
            }
            i++;
        }
        return re.append('$').toString();
    }

    private record Entry(File dir, String prefix, int depth) {}
}
