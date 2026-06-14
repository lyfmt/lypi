package cn.lypi.resource;

import cn.lypi.contracts.memory.MemoryScope;
import cn.lypi.contracts.resource.MemorySource;
import java.nio.file.Path;
import java.util.List;

/**
 * 渲染 memory 使用规则和全局 memory 索引。
 */
final class MemoryPromptSection implements SystemPromptSection {
    private final List<MemorySource> memorySources;

    MemoryPromptSection(List<MemorySource> memorySources) {
        this.memorySources = memorySources == null ? List.of() : List.copyOf(memorySources);
    }

    @Override
    public void appendTo(StringBuilder content, List<String> sourceNames) {
        if (memorySources.isEmpty()) {
            return;
        }

        content.append("## Memory 使用规则\n");
        content.append("memory 是可演进经验源，用于跨轮次、跨会话沉淀已验证经验；它只记录仍可能随经验继续调整的信息。\n\n");
        content.append("### Project Takeover Requirement\n");
        content.append("- 接手项目、恢复长期上下文、开始非平凡开发任务、执行计划、处理用户纠正、或进入不熟悉模块前，如果存在 L2 项目记忆，必须优先读取 L2：`<cwd>/.ly-pi/memory.md` 或项目根 `MEMORY.md`。\n");
        content.append("- L2 不自动全文注入。它是项目方向入口；读取后再决定是否继续读取 L3 skill 或具体文件。\n");
        content.append("- 如果 L2 不存在，不要臆造项目记忆；继续根据 AGENTS.md、SYSTEM.md、CLAUDE.md、代码和测试建立上下文。\n");
        content.append("- 如果用户明确要求忽略 memory，则不要读取、应用、引用或更新 memory，除非用户随后重新允许。\n\n");
        content.append("### Settlement Trigger\n");
        content.append("当任务经历长任务、多步骤实现、重要纠错、反复失败、项目接手、项目交接、上下文接续，或产生可复用经验时，在结束前使用 `memory-settlement` skill 尝试记忆沉淀；若无可沉淀内容，简要说明跳过原因。\n\n");
        content.append("### Read Discipline\n");
        content.append("- L0: `~/.ly-pi/memory.md` 始终注入。它是 L1 索引和 memory 治理入口，只应包含下层记忆指针、触发场景和少量红线规则。\n");
        content.append("- L1: `~/.ly-pi/memories/*` 不自动注入。涉及用户长期偏好、跨项目指导、重要纠错、协作习惯时，先读 L0，再根据 L0 索引按需读取 L1。\n");
        content.append("- L2: `<cwd>/.ly-pi/memory.md` 或项目根 `MEMORY.md` 不自动注入。它是项目方向层；接手项目或涉及当前项目目标、边界、设计方向、用户纠错、准则级事实、L3 skill 索引或长期上下文时，必须优先读取它；与项目方向无关时不要读取。\n");
        content.append("- L3: `<cwd>/.ly-pi/skills/*` 由 Skills 区块索引。它是项目具体知识和处理技巧层；涉及模块知识、处理流程、排障技巧、实现取舍、验证方式或已沉淀操作经验时，按 skill 规则读取对应 `SKILL.md`。\n\n");
        content.append("### Extraction Focus\n");
        content.append("优先提取和沉淀以下信息：\n");
        content.append("- 用户明确纠正、禁止、偏好或确认有效的工作方式。\n");
        content.append("- 当前项目的长期目标、边界、设计方向、架构原则和不可违反的团队约定。\n");
        content.append("- 反复失败后的原因、修复结论、验证方法和避免再次踩坑的判断规则。\n");
        content.append("- 对未来任务有帮助的外部参考入口，例如 issue 系统、文档位置、仪表盘或决策记录。\n");
        content.append("- 不容易从代码、git history、AGENTS.md、测试或少量搜索中重新推导出来的背景和动机。\n\n");
        content.append("不要把以下内容当作 memory 提取重点：\n");
        content.append("- 当前会话进度、短期 todo、一次性计划、临时命令、PID、时间戳或本轮状态。\n");
        content.append("- 可从当前代码、测试、git history、README 或 AGENTS.md 快速恢复的信息。\n");
        content.append("- 未验证猜测、模型推理过程、泛泛总结、通用常识。\n");
        content.append("- 大段日志、聊天记录、测试输出、diff 或文件正文。\n");
        content.append("- 密钥、凭据、隐私数据和不应长期保存的敏感信息。\n\n");
        content.append("### Write Discipline\n");
        content.append("写入 memory 前必须满足：No Verification, No Memory。\n\n");
        content.append("只写满足以下条件的信息：\n");
        content.append("- 已由工具执行、文件读取、测试结果、用户明确确认或其他可验证证据支持。\n");
        content.append("- 未来会减少重复认知成本。\n");
        content.append("- 重新接手任务时，缺少它会导致再次踩坑、再次摸索或再次询问用户。\n");
        content.append("- 能写成未来可直接复用的一句话、规则、索引或 SOP 指针。\n\n");
        content.append("写入或更新前必须先查重：\n");
        content.append("- 先读取目标层当前 memory，确认是否已有相同或冲突内容。\n");
        content.append("- 优先更新旧 memory，不要创建重复记忆。\n");
        content.append("- 如果新事实修正旧事实，保留必要的原因或适用条件，避免只追加互相矛盾的结论。\n");
        content.append("- 删除或改写已验证 memory 前，必须确认它确实过期、错误或已迁移；不确定时保留并标注条件。\n\n");
        content.append("### Memory vs Plan vs Task\n");
        content.append("- 计划用于当前任务的实现路线和用户确认，不写入长期 memory。\n");
        content.append("- task/todo 用于当前会话的步骤追踪，不写入长期 memory。\n");
        content.append("- memory 只保存未来会跨会话复用的事实、偏好、纠错、背景动机或索引指针。\n");
        content.append("- 如果信息只服务本轮对话，用计划、任务或最终回复表达，不要写 memory。\n\n");
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
        content.append("- L2 `<cwd>/.ly-pi/memory.md` 或项目根 `MEMORY.md`\n");
        content.append("  - L2 是项目方向层，记录当前项目的项目目标、边界、设计方向、用户纠错、准则级事实和 L3 skill 索引。\n");
        content.append("  - L2 用于回答：这个项目往哪里走、哪些原则不能忘、已经沉淀了哪些 L3 级项目知识。\n");
        content.append("  - L2 只保留最简 L3 skill 索引：skill 名称、触发场景和一句话用途。\n");
        content.append("  - 只有当信息绑定当前项目，且会影响未来开发判断时，才写入 L2。\n");
        content.append("  - L2 不写 L3 正文摘要、步骤清单、排障细节或实现细节；具体知识必须写入对应 L3 `SKILL.md`。\n");
        content.append("  - L2 不堆放模块细节、排障步骤、实现细节或具体操作流程；这些内容应下沉到 L3。\n");
        content.append("  - L2 不需要写入 L0 指针；它由固定路径自动发现，按项目场景主动读取。\n");
        content.append("- L3 `<cwd>/.ly-pi/skills/*`\n");
        content.append("  - L3 是项目具体知识和处理技巧层，记录模块知识、处理流程、排障技巧、实现取舍和验证方式。\n");
        content.append("  - L3 用于回答：遇到这个模块、问题或流程时，具体怎么做。\n");
        content.append("  - 当 L2 出现具体模块知识或操作流程时，下沉到 L3；当某个项目知识需要多次调用时，也应沉淀为 L3 skill。\n");
        content.append("  - L3 更新必须保持可执行：说明触发场景、前置条件、处理步骤、验证方式和常见失败点。\n");
        content.append("  - 新增、删除、重命名或更新 L3 `SKILL.md` 的用途、触发场景时，必须同步更新对应 L2 的最简 skill 索引。\n");
        content.append("  - 修改 skill 后，如果它的用途或触发场景变化，应同步 skill 描述或索引信息；不要只改正文导致发现失败。\n\n");
        content.append("### Layering Discipline\n");
        content.append("- 上层只留最小充分指针，下层才放内容。\n");
        content.append("- L0/L1 负责全局发现和用户层导航，不写长篇 how-to。\n");
        content.append("- L2 写项目方向、边界、准则级事实、项目级纠错和 L3 skill 索引。\n");
        content.append("- L3 写当前项目内的具体模块知识、处理流程、排障技巧、实现取舍和验证方式。\n");
        content.append("- L0 变长时，下沉到 L1；L1 出现项目专属内容时，迁移到 L2；L2 出现具体模块知识或操作流程时，下沉到 L3。\n");
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
}
