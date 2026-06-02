package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextFileScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void scanOrdersSystemAppendAndInstructionFilesByPriorityAndNameRules() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path nested = Files.createDirectories(project.resolve("module"));
        Files.writeString(project.resolve("AGENTS.md"), "project agents");
        Files.writeString(project.resolve("SYSTEM.md"), "project system");
        Files.writeString(project.resolve("APPEND_SYSTEM.md"), "project append");
        Files.writeString(nested.resolve("CLAUDE.md"), "nested claude");
        Files.writeString(nested.resolve("SYSTEM.md"), "nested system");
        Files.writeString(nested.resolve("APPEND_SYSTEM.md"), "nested append");
        List<ResourceLocation> locations = List.of(
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project"),
            new ResourceLocation(ResourceLayer.NESTED_PROJECT, nested, 300, "nested")
        );

        List<ContextFile> files = new ContextFileScanner().scan(locations, new ArrayList<>());

        assertThat(files)
            .extracting(file -> project.relativize(file.path()).toString())
            .containsExactly(
                "SYSTEM.md",
                "APPEND_SYSTEM.md",
                "AGENTS.md",
                "module/SYSTEM.md",
                "module/APPEND_SYSTEM.md",
                "module/CLAUDE.md"
            );
    }
}
