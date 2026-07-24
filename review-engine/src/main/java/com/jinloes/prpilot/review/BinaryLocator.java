package com.jinloes.prpilot.review;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves provider CLI binaries (`claude`, `copilot`). Probes known hard-coded install paths
 * before falling back to the bare command name, because GUI-launched IDEs often have an incomplete
 * {@code PATH}. Mirrors {@code github-engine}'s own binary-resolution convention rather than
 * reaching across module boundaries for the tiny amount of logic involved.
 */
final class BinaryLocator {

    private BinaryLocator() {}

    static String findBinary(String name, List<String> candidates) {
        return candidates.stream()
                .filter(candidate -> new File(candidate).isFile())
                .findFirst()
                .orElse(name);
    }

    /**
     * Reports whether {@code name} is resolvable without spawning it: true when one of the
     * hard-coded {@code candidates} is an existing file, or when {@code name} is found on the
     * process {@code PATH}. Mirrors the resolution a later spawn would use, so it is a faithful
     * preflight for provider CLIs.
     */
    static boolean isBinaryAvailable(String name, List<String> candidates) {
        if (candidates.stream().anyMatch(candidate -> new File(candidate).isFile())) {
            return true;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return true;
            }
        }
        return false;
    }

    static String providerPath(String userHome, String existingPath) {
        List<String> directories = new ArrayList<>();
        directories.add(userHome + "/.local/bin");
        directories.add(userHome + "/.npm-global/bin");
        directories.add(userHome + "/.volta/bin");
        directories.add("/opt/homebrew/bin");
        directories.add("/usr/local/bin");
        if (existingPath != null && !existingPath.isBlank()) {
            directories.add(existingPath);
        }
        return String.join(File.pathSeparator, directories);
    }
}
