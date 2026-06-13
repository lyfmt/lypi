package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.memory.MemoryScope;
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
            List.of(new MemorySource(MemoryScope.USER, Path.of("memory.md"), "L0 index body", "sha256:memory")),
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
        assertThat(prompt.content()).contains("L0 index body").contains("sha256:memory");
        assertThat(prompt.content()).contains("memory 是可演进经验源，不是稳定规范源");
        assertThat(prompt.content()).contains("当前用户指令 > AGENTS.md/SYSTEM.md/CLAUDE.md > memory");
        assertThat(prompt.content()).contains("L0: `~/.ly-pi/memory.md` 始终注入");
        assertThat(prompt.content()).contains("根据 L0 索引按需读取 L1");
        assertThat(prompt.content()).contains("L2: `<cwd>/.ly-pi/memory.md` 不自动注入");
        assertThat(prompt.content()).contains("L3: `<cwd>/.ly-pi/skills/*`");
        assertThat(prompt.content()).contains("No Verification, No Memory");
        assertThat(prompt.content()).contains("不写临时状态");
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
        assertThat(prompt.sourceNames()).containsExactly("AGENTS.md", "memory.md", "skill:java-style", "prompt:review");
        assertThat(prompt.contentHash()).startsWith("sha256:");
    }
}
