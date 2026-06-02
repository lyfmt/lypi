package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.skill.SkillIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillScannerValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void scanReportsInvalidSkillMetadataButKeepsUsableSkill() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path skillDir = Files.createDirectories(project.resolve(".ly-pi/skills/Java"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: Java
            description: ""
            allowed-tools:
              - read
            allowed_tools:
              - edit
            ---
            body
            """);
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        SkillIndex index = new SkillScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project")
        ), diagnostics);

        assertThat(index.skills()).singleElement().satisfies(skill -> {
            assertThat(skill.name()).isEqualTo("Java");
            assertThat(skill.allowedTools()).containsExactly("edit", "read");
        });
        assertThat(index.diagnostics())
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("skill name"))
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("description"));
    }
}
