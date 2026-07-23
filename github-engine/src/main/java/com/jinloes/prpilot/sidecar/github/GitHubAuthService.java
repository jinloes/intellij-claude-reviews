package com.jinloes.prpilot.sidecar.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Performs a token-safe GitHub CLI and API authentication check. */
public final class GitHubAuthService {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String DEFAULT_BASE_URL = "https://github.com";
    private static final String DEFAULT_API_URL = "https://api.github.com";

    private final TokenResolver tokenResolver;
    private final UserLookup userLookup;

    public GitHubAuthService() {
        this(new ProcessTokenResolver(), new HttpUserLookup());
    }

    GitHubAuthService(TokenResolver tokenResolver, UserLookup userLookup) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.userLookup = Objects.requireNonNull(userLookup);
    }

    public CheckAuthResult check(String githubBaseUrl) {
        BaseUrls baseUrls;
        try {
            baseUrls = BaseUrls.from(githubBaseUrl);
        } catch (IllegalArgumentException exception) {
            return CheckAuthResult.invalidBaseUrl();
        }

        TokenResolution tokenResolution = tokenResolver.resolve(baseUrls.hostnameArgument());
        if (tokenResolution.status() == TokenStatus.NOT_INSTALLED) {
            return CheckAuthResult.notInstalled();
        }
        if (tokenResolution.status() != TokenStatus.RESOLVED) {
            return CheckAuthResult.notAuthenticated();
        }

        UserResolution userResolution =
                userLookup.lookup(baseUrls.apiBaseUrl(), tokenResolution.token());
        return switch (userResolution.status()) {
            case AUTHENTICATED -> CheckAuthResult.authenticated(userResolution.username());
            case NOT_AUTHENTICATED -> CheckAuthResult.notAuthenticated();
            case API_FAILED -> CheckAuthResult.apiFailed();
        };
    }

    public interface TokenResolver {
        TokenResolution resolve(String hostnameArgument);
    }

    interface UserLookup {
        UserResolution lookup(String apiBaseUrl, String token);
    }

    public record TokenResolution(TokenStatus status, String token) {
        public static TokenResolution resolved(String token) {
            return new TokenResolution(TokenStatus.RESOLVED, token);
        }

        public static TokenResolution notInstalled() {
            return new TokenResolution(TokenStatus.NOT_INSTALLED, null);
        }

        public static TokenResolution notAuthenticated() {
            return new TokenResolution(TokenStatus.NOT_AUTHENTICATED, null);
        }
    }

    public enum TokenStatus {
        RESOLVED,
        NOT_INSTALLED,
        NOT_AUTHENTICATED
    }

    record UserResolution(UserStatus status, String username) {
        static UserResolution authenticated(String username) {
            return new UserResolution(UserStatus.AUTHENTICATED, username);
        }

        static UserResolution notAuthenticated() {
            return new UserResolution(UserStatus.NOT_AUTHENTICATED, null);
        }

        static UserResolution apiFailed() {
            return new UserResolution(UserStatus.API_FAILED, null);
        }
    }

    enum UserStatus {
        AUTHENTICATED,
        NOT_AUTHENTICATED,
        API_FAILED
    }

    public static final class ProcessTokenResolver implements TokenResolver {
        private static final List<Path> GH_CANDIDATES =
                List.of(
                        Path.of("/opt/homebrew/bin/gh"),
                        Path.of("/usr/local/bin/gh"),
                        Path.of("/usr/bin/gh"),
                        Path.of("/home/linuxbrew/.linuxbrew/bin/gh"));

        @Override
        public TokenResolution resolve(String hostnameArgument) {
            List<String> command = new java.util.ArrayList<>();
            command.add(findGhBinary());
            command.add("auth");
            command.add("token");
            if (hostnameArgument != null) {
                command.add("--hostname");
                command.add(hostnameArgument);
            }

            Process process;
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.environment().put("HOME", System.getProperty("user.home", ""));
                processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
                process = processBuilder.start();
            } catch (IOException exception) {
                return TokenResolution.notInstalled();
            }

            try {
                if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return TokenResolution.notAuthenticated();
                }
                String token =
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                                .trim();
                return process.exitValue() == 0 && !token.isEmpty()
                        ? TokenResolution.resolved(token)
                        : TokenResolution.notAuthenticated();
            } catch (IOException exception) {
                return TokenResolution.notAuthenticated();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return TokenResolution.notAuthenticated();
            }
        }

        private String findGhBinary() {
            return GH_CANDIDATES.stream()
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .map(Path::toString)
                    .orElse("gh");
        }
    }

    private static final class HttpUserLookup implements UserLookup {
        private final HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public UserResolution lookup(String apiBaseUrl, String token) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(apiBaseUrl + "/user"))
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "application/vnd.github.v3+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header("User-Agent", "pr-pilot-sidecar/0.1")
                            .GET()
                            .build();
            try {
                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    return UserResolution.notAuthenticated();
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return UserResolution.apiFailed();
                }
                JsonNode login = objectMapper.readTree(response.body()).path("login");
                return login.isTextual() && !login.textValue().isBlank()
                        ? UserResolution.authenticated(login.textValue())
                        : UserResolution.apiFailed();
            } catch (IOException exception) {
                return UserResolution.apiFailed();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return UserResolution.apiFailed();
            }
        }
    }

    private record BaseUrls(String apiBaseUrl, String hostnameArgument) {
        private static BaseUrls from(String value) {
            String candidate = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
            URI uri;
            try {
                uri = URI.create(candidate);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid GitHub base URL", exception);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || !"/".equals(uri.getPath()) && !uri.getPath().isEmpty()
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("Invalid GitHub base URL");
            }
            String normalized = "https://" + uri.getHost().toLowerCase();
            return DEFAULT_BASE_URL.equals(normalized)
                    ? new BaseUrls(DEFAULT_API_URL, null)
                    : new BaseUrls(normalized + "/api/v3", uri.getHost());
        }
    }
}
