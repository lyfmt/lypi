package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigPrecedenceTest {
    @TempDir
    Path tempDir;

    @Test
    void scanKeepsHighestPriorityServerAndReportsOverride() throws Exception {
        Path user = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        writeMcp(user.resolve("mcp.json"), "node", "user.js");
        writeMcp(project.resolve(".ly-pi/mcp.json"), "node", "project.js");
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var servers = new McpConfigScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.USER, user, 100, "user"),
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project")
        ), diagnostics);

        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.name()).isEqualTo("filesystem");
            assertThat(server.command()).containsExactly("node", "project.js");
        });
        assertThat(diagnostics).anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("mcp server override").contains("filesystem"));
    }

    private void writeMcp(Path file, String command, String argument) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {
              "servers": {
                "filesystem": {
                  "transport": "STDIO",
                  "command": ["%s", "%s"]
                }
              }
            }
            """.formatted(command, argument));
    }
}
