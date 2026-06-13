package cn.lypi.agent.branch;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 创建 branch summary 专用提示词。
 */
public final class BranchSummaryInstructionFactory {
    private static final String INSTRUCTION = """
        Create a structured summary of this conversation branch for context when returning later.

        The user explored a different conversation branch before returning here.
        Summarize only the messages above. Do not continue the user conversation, do not call tools,
        and do not include target branch context.

        Use this exact format:

        ## Goal
        [What was the user trying to accomplish in this branch?]

        ## Constraints & Preferences
        - [Any constraints, preferences, or requirements mentioned]
        - [Or "(none)" if none were mentioned]

        ## Progress
        ### Done
        - [x] [Completed tasks/changes]

        ### In Progress
        - [ ] [Work that was started but not finished]

        ### Blocked
        - [Issues preventing progress, if any]

        ## Key Decisions
        - **[Decision]**: [Brief rationale]

        ## Next Steps
        1. [What should happen next to continue this work]

        Keep each section concise. Preserve exact file paths, function names, and error messages.
        只输出 structured summary 正文，不要调用工具。
        """;

    /**
     * 返回 summary 指令消息。
     */
    public AgentMessage instructionMessage() {
        return new AgentMessage(
            "branch-summary-instruction",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock(INSTRUCTION.strip())),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }
}
