package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultSystemPromptBuilderTest {
    @Test
    void buildCombinesResourceSnapshotWithoutReadingFiles() {
        ResourceSnapshot snapshot = new ResourceSnapshot(
            List.of(new ContextFile(Path.of("AGENTS.md"), "Follow project rules.", "sha256:agents")),
            List.of(new MemorySource(Path.of("MEMORY.md"), "sha256:memory")),
            new SkillIndex(
                List.of(new SkillDescriptor(
                    "java-style",
                    "Use Java conventions",
                    SkillSource.PROJECT,
                    Path.of(".ly-pi/skills/java/SKILL.md"),
                    List.of("**/*.java"),
                    List.of("read"),
                    "sha256:skill"
                )),
                List.of()
            ),
            List.of(new PromptTemplate(
                "review",
                "Review changes",
                PromptTemplateSource.PROJECT,
                List.of(new PromptParameter("scope", "Review scope", true, Optional.empty())),
                "body must not be included",
                "sha256:prompt"
            )),
            List.of(),
            List.of()
        );

        SystemPrompt prompt = new DefaultSystemPromptBuilder().build(snapshot);

        assertThat(prompt.content()).contains("Follow project rules.");
        assertThat(prompt.content()).contains("MEMORY.md").contains("sha256:memory");
        assertThat(prompt.content()).contains("## Skills");
        assertThat(prompt.content()).contains("### Available skills");
        assertThat(prompt.content()).contains("- java-style: Use Java conventions (file: .ly-pi/skills/java/SKILL.md)");
        assertThat(prompt.content()).contains("### How to use skills");
        assertThat(prompt.content()).contains("After deciding to use a skill");
        assertThat(prompt.content()).doesNotContain("skill:java-style").doesNotContain("sha256:skill");
        assertThat(prompt.content()).contains("prompt:review").contains("PROJECT").contains("sha256:prompt");
        assertThat(prompt.content()).contains("description: Review changes");
        assertThat(prompt.content()).contains("parameters: scope(required)");
        assertThat(prompt.content()).doesNotContain("body must not be included");
        assertThat(prompt.sourceNames()).containsExactly("AGENTS.md", "MEMORY.md", "skill:java-style", "prompt:review");
        assertThat(prompt.contentHash()).startsWith("sha256:");
    }
}
