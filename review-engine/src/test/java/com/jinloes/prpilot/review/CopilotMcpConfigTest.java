package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.McpStdioServerConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CopilotMcpConfigTest {

    @Nested
    class ResolveConfigFile {

        @Test
        void usesConfigDirOverrideWhenProvided() {
            File file = CopilotMcpConfig.resolveConfigFile("/custom/.copilot");
            assertThat(file).isEqualTo(new File("/custom/.copilot", "mcp-config.json"));
        }

        @Test
        void trimsConfigDirOverride() {
            File file = CopilotMcpConfig.resolveConfigFile("  /custom/.copilot  ");
            assertThat(file).isEqualTo(new File("/custom/.copilot", "mcp-config.json"));
        }

        @Test
        void fallsBackToUserHomeCopilotWhenBlank() {
            File expected =
                    new File(
                            new File(System.getProperty("user.home", "/"), ".copilot"),
                            "mcp-config.json");
            assertThat(CopilotMcpConfig.resolveConfigFile("   ")).isEqualTo(expected);
            assertThat(CopilotMcpConfig.resolveConfigFile(null)).isEqualTo(expected);
        }
    }

    @Nested
    class ParseServers {

        @Test
        void parsesStdioServerWithCommandArgsAndEnv() {
            String json =
                    "{\"mcpServers\":{\"docs\":{\"command\":\"node\",\"args\":[\"server.js\",\"--flag\"],"
                            + "\"env\":{\"TOKEN\":\"abc\"},\"tools\":[\"search\"]}}}";
            Map<String, McpServerConfig> servers = CopilotMcpConfig.parseServers(json);
            assertThat(servers).containsOnlyKeys("docs");
            assertThat(servers.get("docs")).isInstanceOf(McpStdioServerConfig.class);
            McpStdioServerConfig stdio = (McpStdioServerConfig) servers.get("docs");
            assertThat(stdio.getCommand()).isEqualTo("node");
            assertThat(stdio.getArgs()).containsExactly("server.js", "--flag");
            assertThat(stdio.getEnv()).containsEntry("TOKEN", "abc");
            assertThat(stdio.getTools()).containsExactly("search");
        }

        @Test
        void parsesHttpServerWithUrlAndHeaders() {
            String json =
                    "{\"mcpServers\":{\"remote\":{\"url\":\"https://mcp.example.com\","
                            + "\"headers\":{\"Authorization\":\"Bearer x\"}}}}";
            Map<String, McpServerConfig> servers = CopilotMcpConfig.parseServers(json);
            assertThat(servers.get("remote")).isInstanceOf(McpHttpServerConfig.class);
            McpHttpServerConfig http = (McpHttpServerConfig) servers.get("remote");
            assertThat(http.getUrl()).isEqualTo("https://mcp.example.com");
            assertThat(http.getHeaders()).containsEntry("Authorization", "Bearer x");
        }

        @Test
        void keepsInsertionOrderAcrossMixedTransports() {
            String json =
                    "{\"mcpServers\":{\"a\":{\"command\":\"x\"},\"b\":{\"url\":\"https://h\"}}}";
            Map<String, McpServerConfig> servers = CopilotMcpConfig.parseServers(json);
            assertThat(servers.keySet()).containsExactly("a", "b");
        }

        @Test
        void skipsEntriesWithNeitherCommandNorUrl() {
            String json = "{\"mcpServers\":{\"bad\":{\"tools\":[\"x\"]},\"ok\":{\"command\":\"y\"}}}";
            Map<String, McpServerConfig> servers = CopilotMcpConfig.parseServers(json);
            assertThat(servers).containsOnlyKeys("ok");
        }

        @Test
        void returnsEmptyWhenMcpServersMissing() {
            assertThat(CopilotMcpConfig.parseServers("{\"other\":1}")).isEmpty();
        }

        @Test
        void returnsEmptyWhenMcpServersNotAnObject() {
            assertThat(CopilotMcpConfig.parseServers("{\"mcpServers\":[]}")).isEmpty();
        }

        @Test
        void returnsEmptyOnMalformedJson() {
            assertThat(CopilotMcpConfig.parseServers("not json")).isEmpty();
        }
    }

    @Nested
    class LoadTrustedServers {

        private Path tempDir;

        @BeforeEach
        void setUp() throws Exception {
            tempDir = Files.createTempDirectory("copilot-mcp-test");
        }

        @AfterEach
        void tearDown() throws Exception {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (Exception ignored) {
                                        // best-effort cleanup
                                    }
                                });
            }
        }

        @Test
        void returnsEmptyWhenFileMissing() {
            assertThat(CopilotMcpConfig.loadTrustedServers(tempDir.toString())).isEmpty();
        }

        @Test
        void readsAndParsesTrustedFile() throws Exception {
            Files.writeString(
                    tempDir.resolve("mcp-config.json"),
                    "{\"mcpServers\":{\"docs\":{\"command\":\"node\"}}}",
                    StandardCharsets.UTF_8);
            Map<String, McpServerConfig> servers =
                    CopilotMcpConfig.loadTrustedServers(tempDir.toString());
            assertThat(servers).containsOnlyKeys("docs");
        }
    }
}

