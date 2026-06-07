package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class CompactSummaryInstructionFactory {
    public AgentMessage instructionMessage() {
        return new AgentMessage(
            "compact-summary-instruction",
            MessageRole.SYSTEM_LOCAL,
            MessageKind.TEXT,
            List.of(new TextContentBlock(instruction())),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }

    private String instruction() {
        return """
            你将看到当前会话截至目前的完整上下文。请生成一份 compact summary，用于在后续上下文中替代此前会话历史。

            只输出 summary 正文，不要调用工具，不要继续对话。

            summary 必须保留：
            1. 用户明确请求和目标。
            2. 用户偏好、约束和本地指令。
            3. 当前任务进展，包括已完成、正在做、未完成事项。
            4. 关键技术决策及原因。
            5. 重要文件、类、方法、接口、配置项名称。
            6. 工具调用结果、错误信息、测试结果和修复结论。
            7. 分支摘要或系统本地上下文中与继续工作有关的信息。
            8. 下一步应继续做什么。

            summary 必须避免：
            1. 编造未发生的操作。
            2. 删除仍影响后续工作的约束。
            3. 把已经明确取消或废弃的任务写成待办。
            4. 输出分析过程、标签包装或寒暄。
            """.strip();
    }
}
