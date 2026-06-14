package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultResourceRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void runtimePortDelegatesLoadingAndSystemPromptBuilding() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(root.resolve(".git"), "gitdir: /tmp/repo.git");
        Files.writeString(root.resolve("AGENTS.md"), "runtime rules");
        ResourceRuntimePort runtime = new DefaultResourceRuntime(
            new DefaultResourceLoader(List.of(), List.of()),
            new DefaultSystemPromptBuilder()
        );

        ResourceSnapshot snapshot = runtime.load(root);
        SystemPrompt prompt = runtime.buildSystemPrompt(snapshot);

        assertThat(snapshot.agentFiles()).hasSize(1);
        assertThat(prompt.content()).contains("runtime rules");
    }

    @Test
    void runtimePromptInjectsL0AndOnlyPointsToProjectMemory() throws Exception {
        Path user = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(root.resolve(".git"), "gitdir: /tmp/repo.git");
        Files.writeString(root.resolve("AGENTS.md"), "stable project rules");
        Files.writeString(user.resolve("memory.md"), "global memory index body");
        Files.createDirectories(root.resolve(".ly-pi"));
        Files.writeString(root.resolve(".ly-pi/memory.md"), "project memory body must be read on demand");

        ResourceRuntimePort runtime = new DefaultResourceRuntime(
            new DefaultResourceLoader(List.of(user), List.of()),
            new DefaultSystemPromptBuilder()
        );

        ResourceSnapshot snapshot = runtime.load(root);
        SystemPrompt prompt = runtime.buildSystemPrompt(snapshot);

        assertThat(prompt.content()).contains("stable project rules");
        assertThat(prompt.content()).contains("global memory index body");
        assertThat(prompt.content()).contains("<cwd>/.ly-pi/memory.md");
        assertThat(prompt.content()).doesNotContain("project memory body must be read on demand");
    }
}
