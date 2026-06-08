package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceDiagnosticLevel;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceRuntimeCoverageTest {
    @TempDir
    Path tempDir;

    @Test
    void runtimeKeepsLayerOrderForContextSystemPromptSkillsAndMcpOverrides() throws Exception {
        Path userRoot = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("repo"));
        Path explicitRoot = Files.createDirectories(tempDir.resolve("explicit"));
        Files.writeString(projectRoot.resolve(".git"), "gitdir: /tmp/repo.git");

        Files.writeString(userRoot.resolve("SYSTEM.md"), "user system");
        Files.writeString(userRoot.resolve("AGENTS.md"), "user agents");
        Files.writeString(projectRoot.resolve("SYSTEM.md"), "project system");
        Files.writeString(projectRoot.resolve("AGENTS.md"), "project agents");
        Files.writeString(explicitRoot.resolve("SYSTEM.md"), "explicit system");
        Files.writeString(explicitRoot.resolve("AGENTS.md"), "explicit agents");
        writeSkill(userRoot.resolve("skills/user/SKILL.md"), "user-skill", "User skill");
        writeSkill(projectRoot.resolve(".ly-pi/skills/project/SKILL.md"), "project-skill", "Project skill");
        writeSkill(explicitRoot.resolve(".ly-pi/skills/explicit/SKILL.md"), "explicit-skill", "Explicit skill");
        writeMcp(userRoot.resolve("mcp.json"), "node", "user.js");
        writeMcp(projectRoot.resolve(".ly-pi/mcp.json"), "node", "project.js");
        writeMcp(explicitRoot.resolve(".ly-pi/mcp.json"), "node", "explicit.js");

        ResourceRuntimePort runtime = new DefaultResourceRuntime(
            new DefaultResourceLoader(List.of(userRoot), List.of(explicitRoot)),
            new DefaultSystemPromptBuilder()
        );

        ResourceSnapshot snapshot = runtime.load(projectRoot);
        SystemPrompt prompt = runtime.buildSystemPrompt(snapshot);

        assertThat(snapshot.agentFiles())
            .extracting(file -> file.content().strip())
            .containsExactly(
                "user system",
                "user agents",
                "project system",
                "project agents",
                "explicit system",
                "explicit agents"
            );
        assertThat(prompt.content())
            .containsSubsequence(
                "user system",
                "user agents",
                "project system",
                "project agents",
                "explicit system",
                "explicit agents"
            );
        assertThat(snapshot.skillIndex().skills())
            .extracting(skill -> skill.source() + ":" + skill.name())
            .containsExactly(
                SkillSource.USER + ":user-skill",
                SkillSource.PROJECT + ":project-skill",
                SkillSource.EXPLICIT_PATH + ":explicit-skill"
            );
        assertThat(snapshot.mcpServers()).singleElement().satisfies(server -> {
            assertThat(server.name()).isEqualTo("filesystem");
            assertThat(server.transport()).isEqualTo(McpTransport.STDIO);
            assertThat(server.command()).containsExactly("node", "explicit.js");
        });
        assertThat(snapshot.diagnostics())
            .extracting(diagnostic -> diagnostic.message())
            .containsExactly(
                "mcp server override: filesystem",
                "mcp server override: filesystem"
            );
    }

    @Test
    void runtimeKeepsDiagnosticsWhenFrontmatterFailsAndContinuesLoadingUsableResources() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(projectRoot.resolve(".git"), "gitdir: /tmp/repo.git");
        Files.writeString(projectRoot.resolve("AGENTS.md"), "project agents still load");
        Path brokenSkill = projectRoot.resolve(".ly-pi/skills/broken/SKILL.md");
        Files.createDirectories(brokenSkill.getParent());
        Files.writeString(brokenSkill, """
            ---
            name: [
            broken body
            """);
        writeSkill(projectRoot.resolve(".ly-pi/skills/valid/SKILL.md"), "valid-skill", "Valid skill");
        Path brokenPrompt = projectRoot.resolve(".ly-pi/prompts/broken.md");
        Files.createDirectories(brokenPrompt.getParent());
        Files.writeString(brokenPrompt, """
            ---
            name: [
            broken prompt
            """);
        Files.writeString(projectRoot.resolve(".ly-pi/prompts/valid.md"), """
            ---
            name: valid
            description: Valid prompt
            ---
            usable prompt
            """);

        ResourceRuntimePort runtime = new DefaultResourceRuntime(
            new DefaultResourceLoader(List.of(), List.of()),
            new DefaultSystemPromptBuilder()
        );

        ResourceSnapshot snapshot = runtime.load(projectRoot);
        SystemPrompt prompt = runtime.buildSystemPrompt(snapshot);

        assertThat(snapshot.agentFiles()).singleElement().satisfies(file ->
            assertThat(file.content()).isEqualTo("project agents still load")
        );
        assertThat(snapshot.skillIndex().skills())
            .extracting(skill -> skill.name())
            .containsExactly("valid-skill");
        assertThat(snapshot.promptTemplates())
            .extracting(template -> template.name())
            .containsExactly("valid");
        assertThat(prompt.content())
            .contains("project agents still load")
            .contains("skill:valid-skill")
            .contains("prompt:valid");
        assertThat(snapshot.diagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.level()).isEqualTo(ResourceDiagnosticLevel.WARNING);
                assertThat(diagnostic.message()).contains("Failed to parse skill frontmatter");
                assertThat(diagnostic.path()).contains(projectRoot.resolve(".ly-pi/skills/broken/SKILL.md"));
            })
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.level()).isEqualTo(ResourceDiagnosticLevel.WARNING);
                assertThat(diagnostic.message()).contains("Failed to parse prompt template");
                assertThat(diagnostic.path()).contains(projectRoot.resolve(".ly-pi/prompts/broken.md"));
            });
    }

    private void writeSkill(Path file, String name, String description) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            ---
            name: %s
            description: %s
            ---
            body
            """.formatted(name, description));
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
