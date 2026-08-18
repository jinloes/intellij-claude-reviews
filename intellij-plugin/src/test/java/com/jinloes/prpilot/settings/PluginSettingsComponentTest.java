package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PluginSettingsComponentTest {

    @Nested
    class AuthStatus {

        @Test
        void coordinatorConstructionDoesNotCheckTheDefaultHost() {
            List<String> checkedUrls = new ArrayList<>();

            new PluginSettingsComponent.AuthCheckCoordinator(
                    url -> {
                        checkedUrls.add(url);
                        return authenticated("octocat");
                    },
                    Runnable::run,
                    Runnable::run);

            assertThat(checkedUrls).isEmpty();
        }

        @Test
        void resetLoadsThePersistedEnterpriseHostBeforeRefreshingExactlyOnce() {
            List<String> events = new ArrayList<>();

            PluginSettingsConfigurable.loadGithubBaseUrlAndRefresh(
                    "https://github.example.test",
                    url -> events.add("set:" + url),
                    () -> events.add("refresh"));

            assertThat(events).containsExactly("set:https://github.example.test", "refresh");
        }

        @Test
        void anOlderCheckCannotOverwriteANewerResult() {
            List<Runnable> checks = new ArrayList<>();
            AtomicReference<String> result = new AtomicReference<>();
            PluginSettingsComponent.AuthCheckCoordinator coordinator =
                    new PluginSettingsComponent.AuthCheckCoordinator(
                            url ->
                                    authenticated(
                                            url.contains("example")
                                                    ? "enterprise-user"
                                                    : "default-user"),
                            checks::add,
                            Runnable::run);

            long first = coordinator.begin();
            coordinator.execute(first, "https://github.com", value -> result.set(value.username()));
            long second = coordinator.begin();
            coordinator.execute(
                    second, "https://github.example.test", value -> result.set(value.username()));
            checks.get(1).run();
            checks.get(0).run();

            assertThat(result).hasValue("enterprise-user");
        }
    }

    private static CheckAuthResult authenticated(String username) {
        return new CheckAuthResult(
                "authenticated", username, "GitHub authentication is available.");
    }
}
