package cn.lycode.agent.branch;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.context.ContentBlock;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.TextContentBlock;
import cn.lycode.contracts.session.BranchSummaryEntry;
import cn.lycode.contracts.session.CompactionEntry;
import cn.lycode.contracts.session.CustomMessageEntry;
import cn.lycode.contracts.session.MessageEntry;
import cn.lycode.contracts.session.SessionEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 构建独立的 branch summary 模型上下文。
 */
public final class BranchSummaryContextBuilder {
    private final BranchSummaryInstructionFactory instructionFactory;

    public BranchSummaryContextBuilder(BranchSummaryInstructionFactory instructionFactory) {
        this.instructionFactory = instructionFactory;
    }

    /**
     * 根据待摘要 entry 片段构建 summary 请求上下文。
     */
    public ContextSnapshot build(BranchSummaryRequest request) {
        ContextSnapshot current = request.context();
        List<AgentMessage> messages = new ArrayList<>();
        for (SessionEntry entry : request.plan().entries()) {
            project(entry).ifPresent(messages::add);
        }
        messages.add(instructionFactory.instructionMessage());
        return new ContextSnapshot(
            current.systemPrompt(),
            List.copyOf(messages),
            current.model(),
            current.thinkingLevel(),
            current.mode(),
            current.permissionRuntimeState(),
            estimateBudget(current, messages)
        );
    }

    private Optional<AgentMessage> project(SessionEntry entry) {
        if (entry instanceof MessageEntry messageEntry) {
            return Optional.of(messageEntry.message());
        }
        if (entry instanceof CustomMessageEntry customMessage) {
            return Optional.of(summaryMessage(
                "custom-message-" + customMessage.id(),
                MessageKind.TEXT,
                customMessage.content(),
                customMessage.timestamp()
            ));
        }
        if (entry instanceof BranchSummaryEntry branchSummary) {
            return Optional.of(summaryMessage(
                "branch-summary-" + branchSummary.id(),
                MessageKind.SUMMARY,
                branchSummary.summary(),
                branchSummary.timestamp()
            ));
        }
        if (entry instanceof CompactionEntry compaction) {
            return Optional.of(summaryMessage(
                "compaction-summary-" + compaction.id(),
                MessageKind.SUMMARY,
                compaction.summary(),
                compaction.timestamp()
            ));
        }
        return Optional.empty();
    }

    private AgentMessage summaryMessage(String id, MessageKind kind, String text, Instant timestamp) {
        return new AgentMessage(
            id,
            MessageRole.SYSTEM_LOCAL,
            kind,
            List.of(new TextContentBlock(text)),
            timestamp == null ? Instant.EPOCH : timestamp,
            Optional.empty(),
            Optional.empty()
        );
    }

    private ContextBudget estimateBudget(ContextSnapshot current, List<AgentMessage> messages) {
        ContextBudget currentBudget = current.budget();
        int systemPromptTokens = current.systemPrompt() == null
            ? 0
            : estimateText(current.systemPrompt().content());
        int estimatedTokens = messages.stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(this::estimateBlock)
            .sum() + systemPromptTokens;
        return new ContextBudget(
            estimatedTokens,
            currentBudget.effectiveContextWindow(),
            currentBudget.autoCompactThreshold(),
            currentBudget.turnOutputBudget(),
            currentBudget.toolResultBudget(),
            currentBudget.totalInputTokens(),
            currentBudget.totalOutputTokens(),
            currentBudget.estimatedCost()
        );
    }

    private int estimateBlock(ContentBlock block) {
        return estimateText(block.text());
    }

    private int estimateText(String text) {
        String safeText = text == null ? "" : text;
        return Math.max(1, safeText.length() / 4);
    }
}
