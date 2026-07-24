package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BinaryLocatorTest {

    @Nested
    class ProviderPath {

        @Test
        void includesGuiToolAndRuntimeLocationsBeforeTheInheritedPath() {
            String path = BinaryLocator.providerPath("/Users/tester", "/usr/bin:/bin");

            assertThat(path)
                    .isEqualTo(
                            String.join(
                                    File.pathSeparator,
                                    "/Users/tester/.local/bin",
                                    "/Users/tester/.npm-global/bin",
                                    "/Users/tester/.volta/bin",
                                    "/opt/homebrew/bin",
                                    "/usr/local/bin",
                                    "/usr/bin:/bin"));
        }

        @Test
        void omitsTheInheritedPathWhenItIsBlank() {
            String path = BinaryLocator.providerPath("/Users/tester", " ");

            assertThat(path)
                    .isEqualTo(
                            String.join(
                                    File.pathSeparator,
                                    "/Users/tester/.local/bin",
                                    "/Users/tester/.npm-global/bin",
                                    "/Users/tester/.volta/bin",
                                    "/opt/homebrew/bin",
                                    "/usr/local/bin"));
        }
    }
}
