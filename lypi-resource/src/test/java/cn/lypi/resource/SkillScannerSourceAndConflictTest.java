package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillScannerSourceAndConflictTest {
    @TempDir
    Path tempDir;

    @Test
    void scanPreservesAllSameNameSkillsMapsSourcesAndReportsConflict() throws Exception {
        Path user = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        writeSkill(user.resolve("skills/java/SKILL.md"), "java-style", "User java");
        writeSkill(project.resolve(".ly-pi/skills/java/SKILL.md"), "java-style", "Project java");
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        SkillIndex index = new SkillScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.USER, user, 100, "user"),
            new ResourceLocation(ResourceLayer.PROJECT, project, 200, "project")
        ), diagnostics);

        assertThat(index.skills()).hasSize(2);
        assertThat(index.skills())
            .extracting(skill -> skill.source() + ":" + skill.description())
            .containsExactly(
                SkillSource.USER + ":User java",
                SkillSource.PROJECT + ":Project java"
            );
        assertThat(index.diagnostics())
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("duplicate skill name").contains("java-style"));
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
}
