package com.jinloes.prpilot.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.PullRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists the set of PR IDs that have already triggered a notification, so we don't spam the user
 * across restarts.
 *
 * <p>Stored as a JSON array of {@code "owner/repo#number"} strings at {@code
 * ~/.pr-pilot/seen-prs.json}.
 */
public final class SeenPRSet {
    private static final Logger log = LoggerFactory.getLogger(SeenPRSet.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Entries older than this are dropped during trim() even if still live.
    public static final int MAX_SIZE = 500;

    private final Path file;

    // LinkedHashSet preserves insertion order so trim() drops the oldest entries.
    private final LinkedHashSet<String> seen;

    // True once the initial seed poll has been persisted (first run should not notify).
    private boolean seeded;

    public SeenPRSet() {
        this(Path.of(System.getProperty("user.home"), ".pr-pilot", "seen-prs.json"));
    }

    public SeenPRSet(Path file) {
        this.file = file;
        Loaded loaded = load(file);
        this.seen = loaded.seen();
        this.seeded = loaded.seeded();
    }

    private record Loaded(LinkedHashSet<String> seen, boolean seeded) {}

    private static Loaded load(Path file) {
        if (!Files.exists(file)) return new Loaded(new LinkedHashSet<>(), false);
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<String> decoded = MAPPER.readValue(json, new TypeReference<List<String>>() {});
            return new Loaded(new LinkedHashSet<>(decoded), true);
        } catch (Exception e) {
            log.warn("Corrupt seen-PR JSON; resetting", e);
            return new Loaded(new LinkedHashSet<>(), false);
        }
    }

    private static String key(PullRequest pr) {
        return pr.getOwner() + "/" + pr.getRepo() + "#" + pr.getNumber();
    }

    public synchronized boolean isSeeded() {
        return seeded;
    }

    public synchronized boolean contains(PullRequest pr) {
        return seen.contains(key(pr));
    }

    public synchronized void add(PullRequest pr) {
        seen.add(key(pr));
    }

    public synchronized void markSeeded() {
        seeded = true;
    }

    public synchronized void reset() {
        seen.clear();
        seeded = false;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to reset seen PR set", e);
        }
    }

    /**
     * Removes entries for PRs that are no longer in {@code livePrs}. Call after each poll to drop
     * closed/merged PRs and PRs where the review request was fulfilled.
     */
    public synchronized void retain(Collection<PullRequest> livePrs) {
        Set<String> liveKeys = new HashSet<>();
        for (PullRequest pr : livePrs) liveKeys.add(key(pr));
        seen.retainAll(liveKeys);
    }

    /** Drops the oldest entries if the set exceeds {@link #MAX_SIZE}. */
    public synchronized void trim() {
        trim(MAX_SIZE);
    }

    /**
     * Drops the oldest entries if the set exceeds {@code maxSize}. The oldest entries are the ones
     * added first ({@link LinkedHashSet} insertion order).
     */
    public synchronized void trim(int maxSize) {
        int excess = seen.size() - maxSize;
        if (excess <= 0) return;
        Iterator<String> iter = seen.iterator();
        for (int i = 0; i < excess && iter.hasNext(); i++) {
            iter.next();
            iter.remove();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(
                    tmp, MAPPER.writeValueAsString(List.copyOf(seen)), StandardCharsets.UTF_8);
            Files.move(
                    tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to save seen PR set", e);
        }
    }
}
