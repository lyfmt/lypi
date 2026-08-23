package cn.lycode.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.prompt.PromptTemplateSource;
import cn.lycode.contracts.resource.ResourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptTemplateOverrideTest {
    @TempDir
    Path tempDir;

    @Test
    void scanKeepsHighestPriorityTemplateAndReportsOverride() throws Exception {
        Path user = Files.createDirectories(tempDir.resolve("user/.ly-code"));
        Path nested = Files.createDirectories(tempDir.resolve("repo/module"));
        writePrompt(user.resolve("prompts/review.md"), "review", "User review", "user body");
        writePrompt(nested.resolve(".ly-code/prompts/review.md"), "review", "Nested review", "nested body");
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        var templates = new PromptTemplateScanner().scan(List.of(
            new ResourceLocation(ResourceLayer.USER, user, 100, "user"),
            new ResourceLocation(ResourceLayer.NESTED_PROJECT, nested, 300, "nested")
        ), diagnostics);

        assertThat(templates).singleElement().satisfies(template -> {
            assertThat(template.name()).isEqualTo("review");
            assertThat(template.description()).isEqualTo("Nested review");
            assertThat(template.source()).isEqualTo(PromptTemplateSource.PROJECT);
            assertThat(template.templateBody()).isEqualTo("nested body");
        });
        assertThat(diagnostics).anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("template override").contains("review"));
    }

    private void writePrompt(Path file, String name, String description, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            ---
            name: %s
            description: %s
            ---
            %s
            """.formatted(name, description, body));
    }
}
