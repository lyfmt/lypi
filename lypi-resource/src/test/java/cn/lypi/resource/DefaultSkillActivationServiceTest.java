package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultSkillActivationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void activateLoadsBodyAndCreatesActivationRecord() throws Exception {
        Path skillFile = tempDir.resolve("java/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
            ---
            name: java-style
            description: Java style
            allowed_tools:
              - read
            ---
            Follow Java conventions.
            """);
        SkillDescriptor descriptor = new SkillDescriptor(
            "java-style",
            "Java style",
            SkillSource.PROJECT,
            skillFile,
            List.of("**/*.java"),
            List.of("read"),
            "sha256:old"
        );

        SkillActivationResult result = new DefaultSkillActivationService()
            .activate(descriptor, "matched user request");

        assertThat(result.body()).isEqualTo("Follow Java conventions.");
        assertThat(result.activation().skillName()).isEqualTo("java-style");
        assertThat(result.activation().activatedReason()).isEqualTo("matched user request");
        assertThat(result.activation().allowedTools()).containsExactly("read");
        assertThat(result.activation().contentHash()).startsWith("sha256:");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void activateReportsReadFailureWithoutThrowing() {
        Path missingFile = tempDir.resolve("missing/SKILL.md");
        SkillDescriptor descriptor = new SkillDescriptor(
            "missing",
            "Missing skill",
            SkillSource.PROJECT,
            missingFile,
            List.of(),
            List.of("read"),
            "sha256:old"
        );

        SkillActivationResult result = new DefaultSkillActivationService()
            .activate(descriptor, "explicit request");

        assertThat(result.body()).isEmpty();
        assertThat(result.activation().skillName()).isEqualTo("missing");
        assertThat(result.activation().contentHash()).isEqualTo("sha256:old");
        assertThat(result.diagnostics()).isNotEmpty();
    }
}
