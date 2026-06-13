package cn.lypi.resource;

import cn.lypi.contracts.memory.MemoryScope;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillDescriptor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认 system prompt 构建器。
 *
 * NOTE: ContextFile 正文会进入 prompt，Skill 和 Prompt Template 只披露索引信息。
 */
public class DefaultSystemPromptBuilder implements SystemPromptBuilder {
    @Override
    public SystemPrompt build(ResourceSnapshot resources) {
        StringBuilder content = new StringBuilder();
        List<String> sourceNames = new ArrayList<>();

        for (ContextFile file : resources.agentFiles()) {
            String sourceName = file.path().toString();
            sourceNames.add(sourceName);
            content.append("## ").append(sourceName).append('\n');
            content.append(file.content().strip()).append("\n\n");
        }

        appendMemorySources(content, sourceNames, resources.memorySources());

        if (!resources.skillIndex().skills().isEmpty()) {
            appendSkills(content, sourceNames, resources.skillIndex().skills());
        }

        if (!resources.promptTemplates().isEmpty()) {
            content.append("## Prompt Templates\n");
            resources.promptTemplates().forEach(template -> {
                sourceNames.add("prompt:" + template.name());
                content.append("- prompt:")
                    .append(template.name())
                    .append(" source=")
                    .append(template.source())
                    .append(" hash=")
                    .append(template.contentHash())
                    .append('\n')
                    .append("  description: ")
                    .append(template.description())
                    .append('\n');
                if (!template.parameters().isEmpty()) {
                    content.append("  parameters: ").append(parameterSummary(template.parameters())).append('\n');
                }
            });
            content.append('\n');
        }

        String promptContent = content.toString().strip();
        return new SystemPrompt(promptContent, List.copyOf(sourceNames), Hashing.sha256(promptContent));
    }

    private String parameterSummary(List<PromptParameter> parameters) {
        return parameters.stream()
            .map(parameter -> parameter.name() + (parameter.required() ? "(required)" : "(optional)"))
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    }

    private void appendMemorySources(StringBuilder content, List<String> sourceNames, List<MemorySource> memorySources) {
        if (memorySources.isEmpty()) {
            return;
        }

        content.append("## Memory 使用规则\n");
        content.append("memory 是可演进经验源，不是稳定规范源。当前用户指令 > AGENTS.md/SYSTEM.md/CLAUDE.md > memory；冲突时遵循更高优先级，并把 memory 视为可能过期。\n\n");
        content.append("- L0: `~/.ly-pi/memory.md` 始终注入，是 L1 索引和记忆治理入口。\n");
        content.append("- L1: `~/.ly-pi/memories/*` 不自动注入；涉及用户长期偏好、跨项目指导、重要纠错时，先读 L0，再根据 L0 索引按需读取 L1。\n");
        content.append("- L2: `<cwd>/.ly-pi/memory.md` 不自动注入；涉及当前项目开发、项目约定、架构取舍、历史纠错、接续任务时，读取它。\n");
        content.append("- L3: `<cwd>/.ly-pi/skills/*` 由 Skills 区块索引；涉及模块 SOP 或复用能力时，按 skill 规则读取 `SKILL.md`。\n\n");
        content.append("写入 memory 前必须满足：No Verification, No Memory；只写未来会减少重复认知成本的信息；不写临时状态、当前进度、未验证猜测、模型推理过程或通用常识。\n\n");

        List<MemorySource> remaining = new ArrayList<>();
        for (MemorySource memorySource : memorySources) {
            sourceNames.add(memorySource.path().toString());
            if (isGlobalMemoryIndex(memorySource)) {
                content.append("## Global Memory Index (L0)\n");
                content.append("Source: ")
                    .append(memorySource.path())
                    .append(" (")
                    .append(memorySource.contentHash())
                    .append(")\n");
                content.append(memorySource.content().strip()).append("\n\n");
            } else {
                remaining.add(memorySource);
            }
        }

        if (!remaining.isEmpty()) {
            content.append("## Other Memory Sources\n");
            for (MemorySource memorySource : remaining) {
                content.append("- ")
                    .append(memorySource.path())
                    .append(" (")
                    .append(memorySource.contentHash())
                    .append(")\n");
            }
            content.append('\n');
        }
    }

    private boolean isGlobalMemoryIndex(MemorySource source) {
        return source.scope() == MemoryScope.USER && source.path().endsWith(Path.of("memory.md"));
    }

    private void appendSkills(StringBuilder content, List<String> sourceNames, List<SkillDescriptor> skills) {
        content.append("## Skills\n");
        content.append("A skill is a set of local instructions to follow that is stored in a `SKILL.md` file.\n\n");
        content.append("### Available skills\n");
        for (SkillDescriptor skill : skills) {
            sourceNames.add("skill:" + skill.name());
            content.append("- ")
                .append(skill.name())
                .append(": ")
                .append(skill.description())
                .append(" (file: ")
                .append(skill.skillFile())
                .append(")\n");
        }
        content.append("\n");
        content.append("### How to use skills\n");
        content.append("- Discovery: The list above is the skills available in this session.\n");
        content.append("- Trigger rules: If the user names a skill with `$skillName`, you must use that skill for that turn.\n");
        content.append("- Progressive disclosure: After deciding to use a skill, read its `SKILL.md` before following it.\n");
        content.append("- Skill bodies are not included in this index. Use normal file-reading tools for implicit skill use.\n\n");
    }
}
