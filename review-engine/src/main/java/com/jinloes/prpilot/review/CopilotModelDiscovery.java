package com.jinloes.prpilot.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code copilot help config} once per session and extracts the list of accepted {@code
 * --model} IDs. The result is cached in memory; call {@link #invalidate()} to force a re-probe
 * (e.g., after the user updates the Copilot CLI in another terminal).
 *
 * <p>The CLI's help text is the source of truth — Copilot's model catalog changes frequently and we
 * intentionally do not pin a list in code. If probing fails (binary missing, policy-blocked
 * account, schema drift in the help output), {@link #listModels()} returns an empty list and the
 * caller is expected to fall back to its own hardcoded suggestions.
 */
public final class CopilotModelDiscovery {

    private static final Logger log = LoggerFactory.getLogger(CopilotModelDiscovery.class);

    // Leading whitespace is a formatting artifact, not a signal — match with or without it.
    private static final Pattern SECTION_START = Pattern.compile("^\\s*`model`:.*");
    private static final Pattern QUOTED_ITEM = Pattern.compile("^\\s*-\\s+\"([^\"]+)\"\\s*$");

    /**
     * Null when no probe has run yet; an empty list after a failed probe (so we don't retry every
     * call).
     */
    private static final AtomicReference<List<String>> cache = new AtomicReference<>(null);

    private CopilotModelDiscovery() {}

    /**
     * Returns the cached model list, probing the CLI synchronously on the first call. Subsequent
     * calls return the cached result instantly. Callers should run this off the EDT — the first
     * call can take up to 10 seconds.
     */
    public static List<String> listModels() {
        List<String> cached = cache.get();
        if (cached != null) return cached;
        List<String> probed = probe();
        // Compare-and-set: another caller racing us only wins once; both threads return the same
        // immutable list either way.
        cache.compareAndSet(null, probed);
        List<String> result = cache.get();
        return result != null ? result : probed;
    }

    /** Drops the cached result so the next {@link #listModels()} call re-probes. */
    public static void invalidate() {
        cache.set(null);
    }

    private static List<String> probe() {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(CopilotService.findCopilotBinary(), "help", "config");
            pb.environment().put("HOME", System.getProperty("user.home", "/"));
            String existingPath = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", "/opt/homebrew/bin:/usr/local/bin:" + existingPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("copilot help config timed out after 10s — skipping model discovery.");
                return List.of();
            }
            if (process.exitValue() != 0) {
                String[] lines = output.split("\n", 4);
                StringBuilder preview = new StringBuilder();
                for (int i = 0; i < Math.min(3, lines.length); i++) {
                    if (i > 0) preview.append(" | ");
                    preview.append(lines[i]);
                }
                String previewText =
                        preview.length() > 300 ? preview.substring(0, 300) : preview.toString();
                log.warn(
                        "copilot help config exited {} — skipping model discovery. Output: {}",
                        process.exitValue(),
                        previewText);
                return List.of();
            }
            List<String> models = parseModelsFromHelp(output);
            if (models.isEmpty()) {
                log.warn(
                        "copilot help config produced no recognized model entries — schema may have changed.");
            } else {
                log.info(
                        "Discovered {} Copilot model IDs from `copilot help config`.",
                        models.size());
            }
            return models;
        } catch (IOException e) {
            log.warn("Failed to probe copilot models: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while probing copilot models");
            return List.of();
        }
    }

    /**
     * Parses model IDs from the output of {@code copilot help config}. Looks for the {@code
     * `model`:} section header and then collects every subsequent line matching {@code -
     * "model-id"} until a blank line ends the section. Returns an empty list if the section is not
     * present or no matching items are found — leaving the caller to fall back to its own
     * suggestion list.
     *
     * <p>Package-private for unit tests so the parser can be exercised without spawning a real CLI.
     */
    static List<String> parseModelsFromHelp(String helpText) {
        List<String> models = new ArrayList<>();
        boolean inSection = false;

        for (String line : helpText.split("\n", -1)) {
            if (!inSection) {
                if (SECTION_START.matcher(line).matches()) inSection = true;
                continue;
            }
            Matcher match = QUOTED_ITEM.matcher(line);
            if (match.matches()) {
                models.add(match.group(1));
            } else if (line.isBlank() && !models.isEmpty()) {
                return models;
            }
            // Otherwise (continuation of the section's description, etc.) keep scanning.
        }
        return Collections.unmodifiableList(models);
    }
}
