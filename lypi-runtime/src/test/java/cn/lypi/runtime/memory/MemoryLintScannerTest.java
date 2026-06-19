package cn.lypi.runtime.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryLintScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsMissingFrontmatterAndMissingIndexForTopicFile() throws Exception {
        Files.createDirectories(tempDir.resolve(".ly-pi/memory/project"));
        Path index = tempDir.resolve(".ly-pi/memory.md");
        Path topic = tempDir.resolve(".ly-pi/memory/project/facts.md");
        Files.writeString(index, "# Project Memory\n");
        Files.writeString(topic, "# Facts\n\n- remember this\n");

        List<MemoryLintDiagnostic> diagnostics = new MemoryLintScanner(tempDir)
            .scan(List.of(topic));

        assertThat(diagnostics).extracting(MemoryLintDiagnostic::code)
            .contains("missing-frontmatter", "missing-index-entry");
    }

    @Test
    void reportsDuplicateItemsAndWrongLayerField() throws Exception {
        Files.createDirectories(tempDir.resolve(".ly-pi/memory/project"));
        Path topic = tempDir.resolve(".ly-pi/memory/project/facts.md");
        Files.writeString(tempDir.resolve(".ly-pi/memory.md"), "- [facts](memory/project/facts.md)\n");
        Files.writeString(topic, """
            ---
            layer: L1
            topic: facts
            ---
            - same fact
            - same fact
            """);

        List<MemoryLintDiagnostic> diagnostics = new MemoryLintScanner(tempDir)
            .scan(List.of(topic));

        assertThat(diagnostics).extracting(MemoryLintDiagnostic::code)
            .contains("duplicate-entry", "invalid-layer");
    }

    @Test
    void reportsSkillDescriptionThatDoesNotMatchBodyTopic() throws Exception {
        Path skill = tempDir.resolve(".ly-pi/skills/build/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, """
            ---
            name: build
            description: Use when deploying to production.
            layer: L3
            ---
            # Maven Test Workflow

            Run `mvn test` before finishing Java changes.
            """);

        List<MemoryLintDiagnostic> diagnostics = new MemoryLintScanner(tempDir)
            .scan(List.of(skill));

        assertThat(diagnostics).extracting(MemoryLintDiagnostic::code)
            .contains("skill-description-mismatch");
    }

    @Test
    void doesNotReportDescriptionMismatchForChineseSkill() throws Exception {
        Path skill = tempDir.resolve(".ly-pi/skills/build/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, """
            ---
            name: build
            description: 构建和测试 Java 项目时使用。
            layer: L3
            ---
            # 构建流程

            修改 Java 代码后运行 Maven 测试。
            """);

        List<MemoryLintDiagnostic> diagnostics = new MemoryLintScanner(tempDir)
            .scan(List.of(skill));

        assertThat(diagnostics).extracting(MemoryLintDiagnostic::code)
            .doesNotContain("skill-description-mismatch");
    }
}
