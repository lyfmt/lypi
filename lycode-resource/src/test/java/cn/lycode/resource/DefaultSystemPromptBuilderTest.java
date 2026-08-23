package cn.lycode.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.prompt.PromptTemplate;
import cn.lycode.contracts.prompt.PromptTemplateSource;
import cn.lycode.contracts.prompt.PromptParameter;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.memory.MemoryScope;
import cn.lycode.contracts.resource.ContextFile;
import cn.lycode.contracts.resource.MemorySource;
import cn.lycode.contracts.resource.ResourceSnapshot;
import cn.lycode.contracts.security.ActivePermissionProfile;
import cn.lycode.contracts.security.ApprovalMode;
import cn.lycode.contracts.security.ApprovalPolicy;
import cn.lycode.contracts.security.LegacyPermissionBehavior;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.skill.SkillDescriptor;
import cn.lycode.contracts.skill.SkillIndex;
import cn.lycode.contracts.skill.SkillSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultSystemPromptBuilderTest {
    private static final String SANDBOX_GUIDANCE = "Treat Bash as running inside the active sandbox by default. "
        + "Paths may be read-only or not visible, and network access may be unavailable. "
        + "If the task genuinely requires access beyond the sandbox, issue a new Bash request with "
        + "sandboxPermissions=requireEscalated and a user-facing justification. "
        + "The runtime does not request escalation or retry outside the sandbox automatically.";

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

        assertThat(prompt.content()).startsWith("You are ly-code");
        assertThat(prompt.content()).contains("## General");
        assertThat(prompt.content()).contains("## Editing Constraints");
        assertThat(prompt.content()).contains("## Workflow");
        assertThat(prompt.content()).contains("## Subagents");
        assertThat(prompt.content()).contains("Subagent completion is delivered automatically at a later model boundary");
        assertThat(prompt.content()).contains("Call `wait_agent` only when the next step depends on the completion");
        assertThat(prompt.content()).contains("If the user asks you not to wait or to continue working");
        assertThat(prompt.content()).contains("## AGENTS.md");
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
        assertThat(prompt.content()).contains("### Project Takeover Requirement");
        assertThat(prompt.content()).contains("接手项目、恢复长期上下文、开始非平凡开发任务");
        assertThat(prompt.content()).contains("如果存在 L2 项目记忆，必须优先读取 L2");
        assertThat(prompt.content()).contains("`<cwd>/.ly-code/memory.md` 或项目根 `MEMORY.md`");
        assertThat(prompt.content()).contains("如果用户明确要求忽略 memory");
        assertThat(prompt.content()).contains("### Extraction Focus");
        assertThat(prompt.content()).contains("用户明确纠正、禁止、偏好或确认有效的工作方式");
        assertThat(prompt.content()).contains("不容易从代码、git history、AGENTS.md、测试或少量搜索中重新推导");
        assertThat(prompt.content()).contains("写入或更新前必须先查重");
        assertThat(prompt.content()).contains("优先更新旧 memory，不要创建重复记忆");
        assertThat(prompt.content()).contains("### Memory vs Plan vs Task");
        assertThat(prompt.content()).contains("计划用于当前任务的实现路线和用户确认，不写入长期 memory");
        assertThat(prompt.content()).contains("task/todo 用于当前会话的步骤追踪，不写入长期 memory");
        assertThat(prompt.content()).contains("memory-settlement");
        assertThat(prompt.content()).contains("长任务");
        assertThat(prompt.content()).contains("重要纠错");
        assertThat(prompt.content()).contains("L0: `~/.ly-code/memory.md` 始终注入");
        assertThat(prompt.content()).contains("L1: `~/.ly-code/memory/*` 不自动注入");
        assertThat(prompt.content()).contains("L1 `~/.ly-code/memory/*`");
        assertThat(prompt.content()).doesNotContain("~/.ly-code/memories");
        assertThat(prompt.content()).contains("根据 L0 索引按需读取 L1");
        assertThat(prompt.content()).contains("L2: `<cwd>/.ly-code/memory.md` 或项目根 `MEMORY.md` 不自动注入");
        assertThat(prompt.content()).contains("L3: `<cwd>/.ly-code/skills/*`");
        assertThat(prompt.content()).contains("No Verification, No Memory");
        assertThat(prompt.content()).contains("重新接手任务时，缺少它会导致再次踩坑");
        assertThat(prompt.content()).contains("当前会话进度、短期 todo、一次性计划");
        assertThat(prompt.content()).contains("L0 不写详细内容、不写项目事实、不写具体 SOP");
        assertThat(prompt.content()).contains("新增、删除、重命名 L1 文件时，必须同步更新 L0 指针");
        assertThat(prompt.content()).contains("L2 是项目方向层");
        assertThat(prompt.content()).contains("项目目标、边界、设计方向、用户纠错、准则级事实和 L3 skill 索引");
        assertThat(prompt.content()).contains("已经沉淀了哪些 L3 级项目知识");
        assertThat(prompt.content()).contains("L2 只保留最简 L3 skill 索引");
        assertThat(prompt.content()).contains("skill 名称、触发场景和一句话用途");
        assertThat(prompt.content()).contains("L2 不写 L3 正文摘要、步骤清单、排障细节或实现细节");
        assertThat(prompt.content()).contains("具体知识必须写入对应 L3 `SKILL.md`");
        assertThat(prompt.content()).contains("L2 不需要写入 L0 指针");
        assertThat(prompt.content()).contains("L3 是项目具体知识和处理技巧层");
        assertThat(prompt.content()).contains("模块知识、处理流程、排障技巧、实现取舍和验证方式");
        assertThat(prompt.content()).contains("新增、删除、重命名或更新 L3 `SKILL.md` 的用途、触发场景时");
        assertThat(prompt.content()).contains("必须同步更新对应 L2 的最简 skill 索引");
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
        assertThat(prompt.sourceNames()).containsExactly(
            "base-agent-instructions",
            "AGENTS.md",
            "memory.md",
            "skill:memory-settlement",
            "prompt:review"
        );
        assertThat(prompt.contentHash()).startsWith("sha256:");
    }

    @Test
    void buildUsesSameSandboxGuidanceForEveryPermissionMode() {
        List<PermissionMode> modes = List.of(PermissionMode.ASK, PermissionMode.AUTO, PermissionMode.BYPASS);
        List<SystemPrompt> prompts = modes.stream()
            .map(this::buildPermissionPrompt)
            .toList();

        for (int index = 0; index < modes.size(); index++) {
            PermissionMode mode = modes.get(index);
            SystemPrompt prompt = prompts.get(index);

            assertThat(prompt.content()).contains("## Permissions");
            assertThat(prompt.content()).contains(
                "Current permission mode: " + mode + ". Follow the permission and sandbox guidance below for tool calls."
            );
            assertThat(prompt.content()).contains("approval policy metadata: ON_FAILURE");
            assertThat(prompt.content()).contains("active sandbox profile: project-dev");
            assertThat(prompt.content()).contains("request_permissions");
            assertThat(prompt.content()).contains("strictAutoReview");
            assertThat(prompt.content()).contains("sandboxPermissions=requireEscalated");
            assertThat(prompt.content()).contains("sandboxPermissions=withAdditionalPermissions");
            assertThat(prompt.content()).contains("permission mode above is the final review route");
            assertThat(prompt.content()).contains(SANDBOX_GUIDANCE);
            assertThat(prompt.content()).doesNotContain(
                "Read-only tools run directly",
                "independent model reviewer",
                "Permission review is skipped",
                "DEFAULT_EXECUTE",
                "ACCEPT_EDITS"
            );
            assertThat(prompt.sourceNames()).containsExactly(
                "base-agent-instructions",
                "permission-runtime-state"
            );
        }

        assertThat(prompts.stream().map(prompt -> extractSandboxGuidance(prompt.content())).toList())
            .containsExactly(SANDBOX_GUIDANCE, SANDBOX_GUIDANCE, SANDBOX_GUIDANCE);
    }

    @Test
    void buildDoesNotExposeOtherMemorySourcesInPrompt() {
        ResourceSnapshot snapshot = new ResourceSnapshot(
            List.of(),
            List.of(
                new MemorySource(MemoryScope.USER, Path.of("memory.md"), "L0 index body", "sha256:l0"),
                new MemorySource(MemoryScope.PROJECT, Path.of(".ly-code/memory/project/facts.md"), "project facts", "sha256:project")
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
        assertThat(prompt.content()).doesNotContain(".ly-code/memory/project/facts.md");
        assertThat(prompt.content()).doesNotContain("sha256:project");
        assertThat(prompt.content()).doesNotContain("project facts");
    }

    private ResourceSnapshot emptySnapshot() {
        return new ResourceSnapshot(
            List.of(),
            List.of(),
            new SkillIndex(List.of(), List.of()),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private SystemPrompt buildPermissionPrompt(PermissionMode mode) {
        PermissionRuntimeState runtimeState = new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.ON_FAILURE),
            new ActivePermissionProfile("project-dev", Optional.of(":workspace")),
            cn.lycode.contracts.security.PermissionProfiles.workspace(),
            new LegacyPermissionBehavior(false, false, false),
            mode
        );
        return new DefaultSystemPromptBuilder().build(emptySnapshot(), runtimeState);
    }

    private String extractSandboxGuidance(String content) {
        int start = content.indexOf(SANDBOX_GUIDANCE);
        return start < 0 ? "" : content.substring(start, start + SANDBOX_GUIDANCE.length());
    }
}
