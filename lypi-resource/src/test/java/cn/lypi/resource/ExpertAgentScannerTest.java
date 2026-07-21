package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpertAgentScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void higherPriorityNestedAgentOverridesProjectAndUserDefinitions() throws Exception {
        Path user = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path nested = Files.createDirectories(project.resolve("module"));
        writeAgent(user.resolve("agents/code-reviewer.yaml"), "user-model", "User prompt", List.of("bash"));
        writeAgent(project.resolve(".ly-pi/agents/code-reviewer.yml"), "project-model", "Project prompt", List.of("read"));
        Path nestedFile = nested.resolve(".ly-pi/agents/review/code-reviewer.yaml");
        writeAgent(nestedFile, "nested-model", "Nested prompt", List.of("bash", "write"));
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var agents = new ExpertAgentScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.USER, user, 100, "user"),
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project"),
            new ResourceLocation(ResourceLayer.NESTED_PROJECT, nested, 300, "nested")
        ), diagnostics);

        assertThat(agents).singleElement().satisfies(agent -> {
            assertThat(agent.name()).isEqualTo("code-reviewer");
            assertThat(agent.provider()).isEqualTo("openai");
            assertThat(agent.model()).isEqualTo("nested-model");
            assertThat(agent.prompt()).isEqualTo("Nested prompt");
            assertThat(agent.tools()).containsExactly("bash", "write");
            assertThat(agent.sourceFile()).isEqualTo(nestedFile.toAbsolutePath().normalize());
        });
        assertThat(diagnostics)
            .filteredOn(diagnostic -> diagnostic.message().contains("expert agent override: code-reviewer"))
            .hasSize(2);
    }

    @Test
    void malformedUnknownOrInvalidYamlProducesDiagnosticsWithoutBlockingValidAgents() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path agentRoot = Files.createDirectories(project.resolve(".ly-pi/agents"));
        Files.writeString(agentRoot.resolve("valid.yaml"), """
            name: valid-agent
            provider: openai
            model: gpt-5.4
            prompt: Valid prompt
            """);
        Files.writeString(agentRoot.resolve("malformed.yaml"), "name: [");
        Files.writeString(agentRoot.resolve("unknown.yaml"), """
            name: unknown-field
            provider: openai
            model: gpt-5.4
            prompt: Prompt
            temperature: 0.2
            """);
        Files.writeString(agentRoot.resolve("missing.yaml"), """
            name: missing-prompt
            provider: openai
            model: gpt-5.4
            """);
        Files.writeString(agentRoot.resolve("invalid-name.yaml"), """
            name: Code Reviewer
            provider: openai
            model: gpt-5.4
            prompt: Prompt
            """);
        Files.writeString(agentRoot.resolve("invalid-tools.yaml"), """
            name: invalid-tools
            provider: openai
            model: gpt-5.4
            prompt: Prompt
            tools:
              - 42
            """);
        Files.writeString(agentRoot.resolve("blank-tool.yaml"), """
            name: blank-tool
            provider: openai
            model: gpt-5.4
            prompt: Prompt
            tools:
              - " "
            """);
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var agents = new ExpertAgentScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project")
        ), diagnostics);

        assertThat(agents).extracting(agent -> agent.name()).containsExactly("valid-agent");
        assertThat(diagnostics).hasSize(6).allSatisfy(diagnostic -> {
            assertThat(diagnostic.message()).startsWith("Failed to parse expert agent:");
            assertThat(diagnostic.path()).isPresent();
        });
        assertThat(diagnostics)
            .anySatisfy(diagnostic -> assertThat(diagnostic.path().orElseThrow()).endsWith(Path.of("malformed.yaml")))
            .anySatisfy(diagnostic -> assertThat(diagnostic.path().orElseThrow()).endsWith(Path.of("unknown.yaml")))
            .anySatisfy(diagnostic -> assertThat(diagnostic.path().orElseThrow()).endsWith(Path.of("missing.yaml")))
            .anySatisfy(diagnostic -> assertThat(diagnostic.path().orElseThrow()).endsWith(Path.of("invalid-name.yaml")))
            .anySatisfy(diagnostic -> assertThat(diagnostic.path().orElseThrow()).endsWith(Path.of("invalid-tools.yaml")))
            .anySatisfy(diagnostic -> assertThat(diagnostic.path().orElseThrow()).endsWith(Path.of("blank-tool.yaml")));
    }

    @Test
    void missingNullOrEmptyToolsUseNoAdditionalToolsAndBothYamlExtensionsAreAccepted() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path agentRoot = Files.createDirectories(project.resolve(".ly-pi/agents/nested"));
        Files.writeString(agentRoot.resolve("missing.yaml"), agentYaml("missing-tools", "missing-model", "Missing", ""));
        Files.writeString(agentRoot.resolve("null.yml"), agentYaml("null-tools", "null-model", "Null tools", "tools:\n"));
        Files.writeString(agentRoot.resolve("empty.yaml"), agentYaml("empty-tools", "empty-model", "Empty", "tools: []\n"));
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var agents = new ExpertAgentScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project")
        ), diagnostics);

        assertThat(diagnostics).isEmpty();
        assertThat(agents)
            .extracting(agent -> agent.name())
            .containsExactlyInAnyOrder("missing-tools", "null-tools", "empty-tools");
        assertThat(agents).allSatisfy(agent -> assertThat(agent.tools()).isEmpty());
    }

    @Test
    void laterFileInSameLayerOverridesEarlierFileDeterministically() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path agentRoot = Files.createDirectories(project.resolve(".ly-pi/agents"));
        Files.writeString(agentRoot.resolve("a.yaml"), agentYaml("code-reviewer", "first-model", "First", ""));
        Path laterFile = agentRoot.resolve("z.yml");
        Files.writeString(laterFile, agentYaml("code-reviewer", "later-model", "Later", ""));
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var agents = new ExpertAgentScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project")
        ), diagnostics);

        assertThat(agents).singleElement().satisfies(agent -> {
            assertThat(agent.model()).isEqualTo("later-model");
            assertThat(agent.sourceFile()).isEqualTo(laterFile.toAbsolutePath().normalize());
        });
        assertThat(diagnostics).singleElement().satisfies(diagnostic ->
            assertThat(diagnostic.message()).isEqualTo("expert agent override: code-reviewer")
        );
    }

    @Test
    void explicitAgentOverridesDeepestNestedDefinition() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path nested = Files.createDirectories(project.resolve("module"));
        Path explicit = Files.createDirectories(tempDir.resolve("explicit"));
        writeAgent(nested.resolve(".ly-pi/agents/code-reviewer.yaml"), "nested-model", "Nested", List.of());
        Path explicitFile = explicit.resolve(".ly-pi/agents/code-reviewer.yaml");
        writeAgent(explicitFile, "explicit-model", "Explicit", List.of("bash"));
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var agents = new ExpertAgentScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.NESTED_PROJECT, nested, 300, "nested"),
            new ResourceLocation(ResourceLayer.EXPLICIT_PATH, explicit, 400, "explicit")
        ), diagnostics);

        assertThat(agents).singleElement().satisfies(agent -> {
            assertThat(agent.model()).isEqualTo("explicit-model");
            assertThat(agent.sourceFile()).isEqualTo(explicitFile.toAbsolutePath().normalize());
        });
        assertThat(diagnostics).singleElement().satisfies(diagnostic ->
            assertThat(diagnostic.message()).isEqualTo("expert agent override: code-reviewer")
        );
    }

    private void writeAgent(Path file, String model, String prompt, List<String> tools) throws Exception {
        Files.createDirectories(file.getParent());
        String toolLines = tools.stream().map(tool -> "  - " + tool).collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(file, """
            name: code-reviewer
            provider: openai
            model: %s
            prompt: %s
            tools:
            %s
            """.formatted(model, prompt, toolLines));
    }

    private String agentYaml(String name, String model, String prompt, String tools) {
        return """
            name: %s
            provider: openai
            model: %s
            prompt: %s
            %s""".formatted(name, model, prompt, tools);
    }
}
