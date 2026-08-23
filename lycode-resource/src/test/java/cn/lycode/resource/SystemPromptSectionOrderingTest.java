package cn.lycode.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.memory.MemoryScope;
import cn.lycode.contracts.prompt.PromptTemplate;
import cn.lycode.contracts.prompt.PromptTemplateSource;
import cn.lycode.contracts.resource.ContextFile;
import cn.lycode.contracts.resource.MemorySource;
import cn.lycode.contracts.resource.ResourceSnapshot;
import cn.lycode.contracts.skill.SkillDescriptor;
import cn.lycode.contracts.skill.SkillIndex;
import cn.lycode.contracts.skill.SkillSource;
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
                Path.of(".ly-code/skills/review-skill/SKILL.md"),
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
            "You are ly-code",
            "## AGENTS.md",
            "## Memory 使用规则",
            "## Skills",
            "## Prompt Templates"
        );
        assertThat(content).doesNotContain("hidden template body");
    }
}
