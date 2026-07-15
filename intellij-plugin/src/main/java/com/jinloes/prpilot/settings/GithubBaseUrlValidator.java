package com.jinloes.prpilot.settings;

import java.net.URI;
import java.net.URISyntaxException;
import org.apache.commons.lang3.StringUtils;

final class GithubBaseUrlValidator {
    private GithubBaseUrlValidator() {}

    static String normalize(String value) {
        String candidate = StringUtils.stripEnd(StringUtils.trimToEmpty(value), "/");
        if (candidate.isEmpty()) {
            return "https://github.com";
        }
        try {
            URI uri = new URI(candidate);
            String authority = uri.getRawAuthority();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || StringUtils.isBlank(uri.getHost())
                    || StringUtils.endsWith(authority, ":")
                    || uri.getPort() > 65535
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (StringUtils.isNotEmpty(uri.getPath()) && !"/".equals(uri.getPath()))) {
                throw invalid();
            }
            return "https://" + authority.toLowerCase(java.util.Locale.ROOT);
        } catch (URISyntaxException e) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "GitHub base URL must be an HTTPS origin without credentials, a path, query, or fragment.");
    }
}
