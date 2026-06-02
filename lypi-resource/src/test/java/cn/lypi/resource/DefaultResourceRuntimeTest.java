package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import java.nio.file.Files;
import java.nio.file.Path;
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
        ResourceRuntimePort runtime = new DefaultResourceRuntime();

        ResourceSnapshot snapshot = runtime.load(root);
        SystemPrompt prompt = runtime.buildSystemPrompt(snapshot);

        assertThat(snapshot.agentFiles()).hasSize(1);
        assertThat(prompt.content()).contains("runtime rules");
    }
}
