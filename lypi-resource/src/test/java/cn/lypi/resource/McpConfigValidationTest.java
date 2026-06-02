package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void scanReportsInvalidMcpServerConfigWithoutDroppingOtherServers() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path config = project.resolve(".ly-pi/mcp.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            {
              "mcpServers": {
                "empty": {"transport": "STDIO"},
                "badTransport": {"transport": "pipe", "command": "node"},
                "badTimeout": {
                  "command": "npx",
                  "args": ["server"],
                  "startupTimeoutSeconds": 0,
                  "callTimeoutSeconds": -1
                },
                "ok": {"command": "npx", "args": ["server"]}
              }
            }
            """);
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var servers = new McpConfigScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project")
        ), diagnostics);

        assertThat(servers).extracting(server -> server.name()).containsExactly("empty", "badTimeout", "ok");
        assertThat(servers)
            .filteredOn(server -> server.name().equals("badTimeout"))
            .singleElement()
            .satisfies(server -> {
                assertThat(server.startupTimeout()).isEqualTo(Duration.ofSeconds(10));
                assertThat(server.callTimeout()).isEqualTo(Duration.ofSeconds(60));
            });
        assertThat(diagnostics)
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("mcp server command is empty").contains("empty"))
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("unsupported mcp transport").contains("badTransport"))
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("startupTimeoutSeconds").contains("badTimeout"))
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("callTimeoutSeconds").contains("badTimeout"));
    }

    @Test
    void scanReportsHttpServerWithoutUrl() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path config = project.resolve(".ly-pi/mcp.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            {
              "mcpServers": {
                "remote": {"transport": "HTTP"}
              }
            }
            """);
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var servers = new McpConfigScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project")
        ), diagnostics);

        assertThat(servers).singleElement().satisfies(server -> assertThat(server.name()).isEqualTo("remote"));
        assertThat(diagnostics)
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("HTTP").contains("url").contains("remote"));
    }
}
