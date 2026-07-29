package com.jinloes.prpilot.sidecar.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GitHubHttpClientTest {

    /** Records each attempt so retry behavior is asserted without sleeping. */
    private static final class RecordingBackoff implements GitHubHttpClient.Backoff {
        private final List<Integer> pauses = new ArrayList<>();

        @Override
        public void pause(int attempt) {
            pauses.add(attempt);
        }
    }

    /**
     * Replays a scripted sequence of outcomes: a {@link GitHubResponse} is returned, a {@link
     * Throwable} is thrown. The last entry repeats once the script is exhausted, so a one-element
     * script models "always fails this way".
     */
    private static final class ScriptedTransport implements GitHubHttpClient.Transport {
        private final Deque<Object> script;
        private final Object last;
        private final List<HttpRequest> requests = new ArrayList<>();
        private InputStream streamBody = InputStream.nullInputStream();
        private final int streamStatus = 200;

        ScriptedTransport(Object... outcomes) {
            this.script = new ArrayDeque<>(List.of(outcomes));
            this.last = outcomes[outcomes.length - 1];
        }

        @Override
        public GitHubResponse send(HttpRequest request) throws IOException, InterruptedException {
            requests.add(request);
            Object outcome = script.isEmpty() ? last : script.poll();
            if (outcome instanceof IOException ioException) throw ioException;
            if (outcome instanceof InterruptedException interrupted) throw interrupted;
            return (GitHubResponse) outcome;
        }

        @Override
        public <T> T stream(HttpRequest request, GitHubHttpClient.BodyReader<T> reader)
                throws IOException {
            requests.add(request);
            return reader.read(streamStatus, streamBody);
        }
    }

    private static GitHubResponse ok(String body) {
        return new GitHubResponse(200, body);
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        void returnsStatusAndBodyOnSuccess() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(new ScriptedTransport(ok("{\"a\":1}")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).isEqualTo("{\"a\":1}");
            assertThat(result.isSuccess()).isTrue();
            assertThat(backoff.pauses).as("a successful call must not back off").isEmpty();
        }

        @Test
        void doesNotRetryUnauthorized() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(401, "")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.isUnauthenticated()).isTrue();
            assertThat(backoff.pauses).isEmpty();
        }

        @Test
        void doesNotRetryNotFound() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(404, "")), backoff);

            assertThat(client.get("https://api.github.com/user", "t").statusCode()).isEqualTo(404);
            assertThat(backoff.pauses).isEmpty();
        }

        @Test
        void retriesRateLimitingThenSucceeds() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(429, ""), ok("done")),
                            backoff);

            assertThat(client.get("https://api.github.com/user", "t").body()).isEqualTo("done");
            assertThat(backoff.pauses).containsExactly(1);
        }

        @Test
        void retriesServerErrorsThenSucceeds() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(503, ""), ok("done")),
                            backoff);

            assertThat(client.get("https://api.github.com/user", "t").body()).isEqualTo("done");
            assertThat(backoff.pauses).containsExactly(1);
        }

        @Test
        void surfacesTheLastStatusWhenRetriesAreExhausted() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(429, "")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.isRateLimited())
                    .as("caller still learns it was rate limited")
                    .isTrue();
            assertThat(backoff.pauses)
                    .as("backs off between attempts but not after the final one")
                    .containsExactly(1, 2);
        }

        @Test
        void retriesIoFailuresThenSucceeds() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new IOException("boom"), ok("done")), backoff);

            assertThat(client.get("https://api.github.com/user", "t").body()).isEqualTo("done");
            assertThat(backoff.pauses).containsExactly(1);
        }

        @Test
        void reportsNetworkErrorWhenEveryAttemptThrows() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(new ScriptedTransport(new IOException("boom")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.isNetworkError()).isTrue();
            assertThat(result.statusCode()).isEqualTo(GitHubResponse.NETWORK_ERROR);
            assertThat(backoff.pauses).containsExactly(1, 2);
        }

        @Test
        void stopsAtTheConfiguredAttemptCeiling() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(500, "")), backoff);

            client.get("https://api.github.com/user", "t");

            assertThat(backoff.pauses).hasSize(GitHubHttpClient.MAX_ATTEMPTS - 1);
        }

        @Test
        void reportsNetworkErrorForAMalformedUrlRatherThanThrowing() {
            GitHubHttpClient client =
                    new GitHubHttpClient(new ScriptedTransport(ok("x")), new RecordingBackoff());

            assertThat(client.get("not a url", "t").isNetworkError()).isTrue();
        }

        @Test
        void stopsImmediatelyAndRestoresTheFlagWhenInterrupted() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new InterruptedException("stop")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.isNetworkError()).isTrue();
            assertThat(backoff.pauses).isEmpty();
            assertThat(Thread.interrupted()).as("interrupt flag is restored").isTrue();
        }
    }

    @Nested
    @DisplayName("request")
    class Request {

        @Test
        void sendsBearerTokenAndJsonAcceptByDefault() {
            ScriptedTransport transport = new ScriptedTransport(ok("x"));
            new GitHubHttpClient(transport, new RecordingBackoff())
                    .get("https://api.github.com/user", "secret-token");

            HttpRequest request = transport.requests.get(0);
            assertThat(request.headers().firstValue("Authorization"))
                    .contains("Bearer secret-token");
            assertThat(request.headers().firstValue("Accept"))
                    .contains(GitHubHttpClient.ACCEPT_JSON);
            assertThat(request.headers().firstValue("X-GitHub-Api-Version")).contains("2022-11-28");
            assertThat(request.headers().firstValue("User-Agent")).isPresent();
            assertThat(request.method()).isEqualTo("GET");
        }

        @Test
        void honoursAnExplicitAcceptMediaType() {
            ScriptedTransport transport = new ScriptedTransport(ok("diff"));
            new GitHubHttpClient(transport, new RecordingBackoff())
                    .get("https://api.github.com/x", "t", GitHubHttpClient.ACCEPT_DIFF);

            assertThat(transport.requests.get(0).headers().firstValue("Accept"))
                    .contains(GitHubHttpClient.ACCEPT_DIFF);
        }
    }

    @Nested
    @DisplayName("stream")
    class Stream {

        @Test
        void handsTheStatusAndBodyToTheReader() throws Exception {
            ScriptedTransport transport = new ScriptedTransport(ok("unused"));
            transport.streamBody =
                    new ByteArrayInputStream("diff --git a b".getBytes(StandardCharsets.UTF_8));

            String read =
                    new GitHubHttpClient(transport, new RecordingBackoff())
                            .stream(
                                    "https://api.github.com/x",
                                    "t",
                                    GitHubHttpClient.ACCEPT_DIFF,
                                    (status, body) ->
                                            status
                                                    + ":"
                                                    + new String(
                                                            body.readAllBytes(),
                                                            StandardCharsets.UTF_8));

            assertThat(read).isEqualTo("200:diff --git a b");
        }

        @Test
        void letsTheReaderBoundHowManyBytesItConsumes() throws Exception {
            ScriptedTransport transport = new ScriptedTransport(ok("unused"));
            transport.streamBody =
                    new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8));

            String read =
                    new GitHubHttpClient(transport, new RecordingBackoff())
                            .stream(
                                    "https://api.github.com/x",
                                    "t",
                                    GitHubHttpClient.ACCEPT_DIFF,
                                    (status, body) ->
                                            new String(body.readNBytes(4), StandardCharsets.UTF_8));

            assertThat(read).isEqualTo("0123");
        }
    }
}
