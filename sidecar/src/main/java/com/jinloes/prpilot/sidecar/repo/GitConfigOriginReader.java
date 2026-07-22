package com.jinloes.prpilot.sidecar.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the {@code [remote "origin"]} url from a git {@code config} file.
 *
 * <p>Scoped strictly to the {@code origin} section (rather than the first {@code url =} line in the
 * file) so an unrelated remote such as {@code upstream} is never picked up in multi-remote or fork
 * setups. This matches the intent of both existing host implementations.
 */
final class GitConfigOriginReader {
    private static final Pattern URL_KEY = Pattern.compile("^url\\s*=");
    private static final String ORIGIN_SECTION = "[remote \"origin\"]";

    enum Status {
        FOUND,
        CONFIG_MISSING,
        CONFIG_UNREADABLE,
        ORIGIN_MISSING
    }

    record Lookup(Status status, String url) {
        static Lookup found(String url) {
            return new Lookup(Status.FOUND, url);
        }

        static Lookup of(Status status) {
            return new Lookup(status, null);
        }
    }

    Lookup readOriginUrl(Path gitDir) {
        Path configPath = gitDir.resolve("config");
        if (!Files.isRegularFile(configPath)) {
            return Lookup.of(Status.CONFIG_MISSING);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return Lookup.of(Status.CONFIG_UNREADABLE);
        }

        boolean inOriginSection = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.equals(ORIGIN_SECTION)) {
                inOriginSection = true;
            } else if (inOriginSection && trimmed.startsWith("[")) {
                break;
            } else if (inOriginSection && URL_KEY.matcher(trimmed).find()) {
                String url = trimmed.substring(trimmed.indexOf('=') + 1).strip();
                if (!url.isEmpty()) {
                    return Lookup.found(url);
                }
            }
        }
        return Lookup.of(Status.ORIGIN_MISSING);
    }
}
