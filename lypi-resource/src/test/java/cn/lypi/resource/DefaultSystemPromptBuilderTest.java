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
                    "memory-settlement",
                    "Use when a long task, important correction, repeated failure, project handoff, or reusable lesson may need durable memory.",
                    SkillSource.USER,
                    Path.of("skills/memory-settlement/SKILL.md"),
                    List.of(),
                    List.of("read", "edit"),
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
        assertThat(prompt.content()).contains("L0 index body");
        assertThat(prompt.content()).doesNotContain("sha256:memory");
        assertThat(prompt.content()).contains("memory 是可演进经验源，用于跨轮次、跨会话沉淀已验证经验");
        assertThat(prompt.content()).doesNotContain("AGENTS.md/SYSTEM.md/CLAUDE.md > memory");
        assertThat(prompt.content()).doesNotContain("优先级");
        assertThat(prompt.content()).contains("### Read Discipline");
        assertThat(prompt.content()).contains("### Write Discipline");
        assertThat(prompt.content()).contains("### Update Discipline");
        assertThat(prompt.content()).contains("### Layering Discipline");
        assertThat(prompt.content()).contains("### Settlement Trigger");
        assertThat(prompt.content()).contains("memory-settlement");
        assertThat(prompt.content()).contains("长任务");
        assertThat(prompt.content()).contains("重要纠错");
        assertThat(prompt.content()).contains("L0: `~/.ly-pi/memory.md` 始终注入");
        assertThat(prompt.content()).contains("根据 L0 索引按需读取 L1");
        assertThat(prompt.content()).contains("L2: `<cwd>/.ly-pi/memory.md` 不自动注入");
        assertThat(prompt.content()).contains("L3: `<cwd>/.ly-pi/skills/*`");
        assertThat(prompt.content()).contains("No Verification, No Memory");
        assertThat(prompt.content()).contains("重新接手任务时，缺少它会导致再次踩坑");
        assertThat(prompt.content()).contains("当前进度、临时状态、会话状态");
        assertThat(prompt.content()).contains("L0 不写详细内容、不写项目事实、不写具体 SOP");
        assertThat(prompt.content()).contains("新增、删除、重命名 L1 文件时，必须同步更新 L0 指针");
        assertThat(prompt.content()).contains("L2 是项目方向层");
        assertThat(prompt.content()).contains("项目目标、边界、设计方向、用户纠错、准则级事实和 L3 skill 索引");
        assertThat(prompt.content()).contains("L2 不需要写入 L0 指针");
        assertThat(prompt.content()).contains("L3 是项目具体知识和处理技巧层");
        assertThat(prompt.content()).contains("模块知识、处理流程、排障技巧、实现取舍和验证方式");
        assertThat(prompt.content()).doesNotContain("应迁移或整理到 AGENTS.md");
        assertThat(prompt.content()).contains("L2 出现具体模块知识或操作流程时，下沉到 L3");
        assertThat(prompt.content()).contains("L0 变长时，下沉到 L1");
        assertThat(prompt.content()).contains("## Skills");
        assertThat(prompt.content()).contains("### Available skills");
        assertThat(prompt.content()).contains("- memory-settlement: Use when a long task");
        assertThat(prompt.content()).contains("### How to use skills");
        assertThat(prompt.content()).contains("After deciding to use a skill");
        assertThat(prompt.content()).doesNotContain("skill:memory-settlement").doesNotContain("sha256:skill");
        assertThat(prompt.content()).contains("prompt:review").contains("PROJECT").contains("sha256:prompt");
        assertThat(prompt.content()).contains("description: Review changes");
        assertThat(prompt.content()).contains("parameters: scope(required)");
        assertThat(prompt.content()).doesNotContain("body must not be included");
        assertThat(prompt.sourceNames()).containsExactly("AGENTS.md", "memory.md", "skill:memory-settlement", "prompt:review");
        assertThat(prompt.contentHash()).startsWith("sha256:");
    }

    @Test
    void buildDoesNotExposeOtherMemorySourcesInPrompt() {
        ResourceSnapshot snapshot = new ResourceSnapshot(
            List.of(),
            List.of(
                new MemorySource(MemoryScope.USER, Path.of("memory.md"), "L0 index body", "sha256:l0"),
                new MemorySource(MemoryScope.PROJECT, Path.of(".ly-pi/memory/project/facts.md"), "project facts", "sha256:project")
            ),
            new SkillIndex(List.of(), List.of()),
            List.of(),
            List.of(),
            List.of()
        );

        SystemPrompt prompt = new DefaultSystemPromptBuilder().build(snapshot);

        assertThat(prompt.content()).contains("L0 index body");
        assertThat(prompt.content()).doesNotContain("sha256:l0");
        assertThat(prompt.content()).doesNotContain("Other Memory Sources");
        assertThat(prompt.content()).doesNotContain(".ly-pi/memory/project/facts.md");
        assertThat(prompt.content()).doesNotContain("sha256:project");
        assertThat(prompt.content()).doesNotContain("project facts");
    }
}
