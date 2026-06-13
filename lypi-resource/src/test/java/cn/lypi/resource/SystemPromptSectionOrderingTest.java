package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.memory.MemoryScope;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemPromptSectionOrderingTest {
    @Test
    void rendersStableSectionOrderAndKeepsTemplateBodyHidden() {
        ResourceSnapshot snapshot = new ResourceSnapshot(
            List.of(new ContextFile(Path.of("AGENTS.md"), "project rules", "sha256:agents")),
            List.of(new MemorySource(MemoryScope.USER, Path.of("memory.md"), "L0 body", "sha256:memory")),
            new SkillIndex(List.of(new SkillDescriptor(
                "review-skill",
                "Review implementation",
                SkillSource.PROJECT,
                Path.of(".ly-pi/skills/review-skill/SKILL.md"),
                List.of(),
                List.of(),
                "sha256:skill"
            )), List.of()),
            List.of(new PromptTemplate(
                "review",
                "Review code",
                PromptTemplateSource.PROJECT,
                List.of(),
                "hidden template body",
                "sha256:prompt"
            )),
            List.of(),
            List.of()
        );

        String content = new DefaultSystemPromptBuilder().build(snapshot).content();

        assertThat(content).containsSubsequence(
            "You are ly-pi",
            "## AGENTS.md",
            "## Memory 使用规则",
            "## Skills",
            "## Prompt Templates"
        );
        assertThat(content).doesNotContain("hidden template body");
    }
}
