package cn.lypi.tool;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContentReplacementRecord;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用工具结果上下文预算。
 *
 * NOTE: 本轮只生成确定性 replacement 记录，真实落盘由后续 ToolResultStore 接管。
 */
public final class ToolResultBudgeter {
    /**
     * 裁剪超出最大长度的工具结果消息。
     */
    public <O> ToolResult<O> apply(String toolUseId, String toolName, ToolResult<O> result, int maxResultSize) {
        if (result == null || maxResultSize <= 0) {
            return result;
        }
        BudgetResult budgetResult = budgetMessages(toolUseId, result.newMessages(), maxResultSize);
        if (!budgetResult.changed()) {
            return result;
        }
        Optional<ContentReplacementRecord> replacement = result.replacement().isPresent()
            ? result.replacement()
            : Optional.of(replacement(toolUseId, toolName, budgetResult.originalLength(), budgetResult.replacementLength()));
        return new ToolResult<>(
            result.output(),
            result.isError(),
            budgetResult.messages(),
            replacement
        );
    }

    private BudgetResult budgetMessages(String toolUseId, List<AgentMessage> messages, int maxResultSize) {
        List<AgentMessage> budgetedMessages = new ArrayList<>();
        boolean changed = false;
        int originalLength = 0;
        int replacementLength = 0;
        for (AgentMessage message : messages) {
            MessageBudgetResult messageBudget = budgetMessage(toolUseId, message, maxResultSize);
            budgetedMessages.add(messageBudget.message());
            changed = changed || messageBudget.changed();
            originalLength += messageBudget.originalLength();
            replacementLength += messageBudget.replacementLength();
        }
        return new BudgetResult(List.copyOf(budgetedMessages), changed, originalLength, replacementLength);
    }

    private MessageBudgetResult budgetMessage(String toolUseId, AgentMessage message, int maxResultSize) {
        List<ContentBlock> blocks = new ArrayList<>();
        boolean changed = false;
        int originalLength = 0;
        int replacementLength = 0;
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolResultContentBlock toolResultBlock
                && toolResultBlock.text().length() > maxResultSize) {
                originalLength += toolResultBlock.text().length();
                String preview = preview(toolResultBlock.text(), maxResultSize);
                replacementLength += preview.length();
                blocks.add(new ToolResultContentBlock(
                    toolUseId,
                    preview,
                    toolResultBlock.error(),
                    toolResultBlock.metadata()
                ));
                changed = true;
            } else {
                blocks.add(block);
            }
        }
        if (!changed) {
            return new MessageBudgetResult(message, false, 0, 0);
        }
        return new MessageBudgetResult(
            new AgentMessage(
                message.id(),
                message.role(),
                message.kind(),
                List.copyOf(blocks),
                message.timestamp(),
                message.usage(),
                message.stopReason()
            ),
            true,
            originalLength,
            replacementLength
        );
    }

    private String preview(String text, int maxResultSize) {
        return text.substring(0, maxResultSize)
            + "\n\n[工具结果已超出预算，完整内容已替换为持久化记录。]";
    }

    private ContentReplacementRecord replacement(
        String toolUseId,
        String toolName,
        int originalLength,
        int replacementLength
    ) {
        return new ContentReplacementRecord(
            "msg_" + toolUseId,
            toolUseId,
            toolName,
            Path.of(".lypi/tool-results", toolUseId + ".txt"),
            "工具结果已超出预算。",
            originalLength,
            replacementLength
        );
    }

    private record BudgetResult(
        List<AgentMessage> messages,
        boolean changed,
        int originalLength,
        int replacementLength
    ) {}

    private record MessageBudgetResult(
        AgentMessage message,
        boolean changed,
        int originalLength,
        int replacementLength
    ) {}
}
