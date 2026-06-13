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
        content.append("memory 是可演进经验源，用于跨轮次、跨会话沉淀已验证经验；它只记录仍可能随经验继续调整的信息。\n\n");
        content.append("### Settlement Trigger\n");
        content.append("当任务经历长任务、多步骤实现、重要纠错、反复失败、项目交接、上下文接续，或产生可复用经验时，在结束前使用 `memory-settlement` skill 尝试记忆沉淀；若无可沉淀内容，简要说明跳过原因。\n\n");
        content.append("### Read Discipline\n");
        content.append("- L0: `~/.ly-pi/memory.md` 始终注入。它是 L1 索引和 memory 治理入口，只应包含下层记忆指针、触发场景和少量红线规则。\n");
        content.append("- L1: `~/.ly-pi/memories/*` 不自动注入。涉及用户长期偏好、跨项目指导、重要纠错、协作习惯时，先读 L0，再根据 L0 索引按需读取 L1。\n");
        content.append("- L2: `<cwd>/.ly-pi/memory.md` 不自动注入。涉及当前项目开发、项目约定、架构取舍、历史纠错、接续任务或长期上下文时，先读取它；与项目认知无关时不要读取。\n");
        content.append("- L3: `<cwd>/.ly-pi/skills/*` 由 Skills 区块索引。涉及模块 SOP、复用能力、复杂流程或已沉淀操作经验时，按 skill 规则读取对应 `SKILL.md`。\n\n");
        content.append("### Write Discipline\n");
        content.append("写入 memory 前必须满足：No Verification, No Memory。\n\n");
        content.append("只写满足以下条件的信息：\n");
        content.append("- 已由工具执行、文件读取、测试结果、用户明确确认或其他可验证证据支持。\n");
        content.append("- 未来会减少重复认知成本。\n");
        content.append("- 重新接手任务时，缺少它会导致再次踩坑、再次摸索或再次询问用户。\n");
        content.append("- 能写成未来可直接复用的一句话、规则、索引或 SOP 指针。\n\n");
        content.append("禁止写入：\n");
        content.append("- 当前进度、临时状态、会话状态、一次性计划、时间戳、PID、短期路径。\n");
        content.append("- 未验证猜测、模型推理过程、泛泛总结、通用常识。\n");
        content.append("- 大段日志、聊天记录、测试输出或可通过少量读取快速恢复的信息。\n\n");
        content.append("### Update Discipline\n");
        content.append("更新 memory 时必须先判断信息属于哪一层；不要因为文件容易写就写错层。\n\n");
        content.append("- L0 `~/.ly-pi/memory.md`\n");
        content.append("  - 只更新 L1 索引、触发场景、记忆治理红线和极高频全局规则。\n");
        content.append("  - L0 不写详细内容、不写项目事实、不写具体 SOP。\n");
        content.append("  - 新增、删除、重命名 L1 文件时，必须同步更新 L0 指针。\n");
        content.append("  - L0 条目必须保持短小；如果一句话写不清，说明它应该下沉到 L1。\n");
        content.append("- L1 `~/.ly-pi/memories/*`\n");
        content.append("  - 记录用户跨项目长期偏好、重要指导、重要纠错和协作习惯。\n");
        content.append("  - 只有当信息不绑定具体项目、且未来多个项目都会复用时，才写入 L1。\n");
        content.append("  - L1 文件新增或主题明显变化时，必须同步 L0 索引。\n");
        content.append("  - L1 允许比 L0 详细，但仍应压缩为规则、偏好、反例或纠错结论，不写会话流水。\n");
        content.append("- L2 `<cwd>/.ly-pi/memory.md`\n");
        content.append("  - 记录当前项目长期有效的事实、约定、用户纠错、架构取舍和高成本避坑点。\n");
        content.append("  - 只有当信息绑定当前项目，且未来接手该项目会复用时，才写入 L2。\n");
        content.append("  - L2 不需要写入 L0 指针；它由固定路径自动发现，按项目场景主动读取。\n");
        content.append("- L3 `<cwd>/.ly-pi/skills/*`\n");
        content.append("  - 记录可复用能力、模块 SOP、复杂操作流程、排障路径和经过验证的工程取舍。\n");
        content.append("  - 当 L2 中某类经验反复出现，或某个模块知识需要被多次调用时，应沉淀为 L3 skill。\n");
        content.append("  - L3 更新必须保持可执行：说明触发场景、前置条件、操作步骤、验证方式和常见失败点。\n");
        content.append("  - 修改 skill 后，如果它的用途或触发场景变化，应同步 skill 描述或索引信息；不要只改正文导致发现失败。\n\n");
        content.append("### Layering Discipline\n");
        content.append("- 上层只留最小充分指针，下层才放内容。\n");
        content.append("- L0/L1 负责发现和导航，不写长篇 how-to。\n");
        content.append("- L2 写项目长期事实和项目级纠错。\n");
        content.append("- L3 写可复用 SOP、模块知识、复杂决策取舍和高成本避坑点。\n");
        content.append("- L0 变长时，下沉到 L1；L1 出现项目专属内容时，迁移到 L2；L2 出现可复用流程时，沉淀到 L3。\n");
        content.append("- 删除或改写已验证 memory 前，必须确认它确实过期、错误或已迁移；不确定时保留并标注条件。\n");
        content.append("- 修改 memory 时优先增量 patch；不要整篇重写。若不确定是否值得写入，默认不写。\n\n");

        for (MemorySource memorySource : memorySources) {
            sourceNames.add(memorySource.path().toString());
            if (isGlobalMemoryIndex(memorySource)) {
                content.append("## Global Memory Index (L0)\n");
                content.append("Source: ").append(memorySource.path()).append('\n');
                content.append(memorySource.content().strip()).append("\n\n");
            }
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
