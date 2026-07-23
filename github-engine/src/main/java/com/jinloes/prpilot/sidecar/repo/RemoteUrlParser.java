package com.jinloes.prpilot.sidecar.repo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a git remote url into an owner/repo pair.
 *
 * <p>Supports three forms:
 *
 * <ul>
 *   <li>HTTPS/HTTP: {@code https://github.example.com/owner/repo.git}
 *   <li>SSH URI: {@code ssh://git@host:port/owner/repo.git}
 *   <li>SCP-style SSH: {@code git@github.com:owner/repo.git}
 * </ul>
 *
 * <p>Deliberately stricter than either existing host implementation: exactly two non-blank path
 * segments are required (owner and repo), so urls with extra path segments or missing components
 * are rejected rather than silently truncated.
 */
final class RemoteUrlParser {
    // user@host:owner/repo — excludes ssh:// URIs (handled separately, they contain "//").
    private static final Pattern SCP_STYLE = Pattern.compile("^[^@/]+@[^:/]+:(.+)$");

    Optional<RepositoryId> parse(String remoteUrl) {
        if (remoteUrl == null) {
            return Optional.empty();
        }
        String url = remoteUrl.strip();
        if (url.isEmpty()) {
            return Optional.empty();
        }
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }

        if (!url.startsWith("http://")
                && !url.startsWith("https://")
                && !url.startsWith("ssh://")) {
            Matcher scp = SCP_STYLE.matcher(url);
            return scp.matches() ? ownerRepoFromPath(scp.group(1)) : Optional.empty();
        }

        try {
            String path = new URI(url).getPath();
            if (path != null && path.startsWith("/")) {
                path = path.substring(1);
            }
            return ownerRepoFromPath(path);
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
    }

    private Optional<RepositoryId> ownerRepoFromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String[] parts = path.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryId(parts[0], parts[1]));
    }
}
