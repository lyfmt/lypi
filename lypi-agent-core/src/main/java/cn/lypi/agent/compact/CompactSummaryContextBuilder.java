package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import java.util.ArrayList;
import java.util.List;

public final class CompactSummaryContextBuilder {
    private final CompactSummaryInstructionFactory instructionFactory;

    public CompactSummaryContextBuilder(CompactSummaryInstructionFactory instructionFactory) {
        this.instructionFactory = instructionFactory;
    }

    public ContextSnapshot build(CompactSummaryRequest request) {
        ContextSnapshot current = request.context();
        return build(request, current.messages());
    }

    ContextSnapshot build(CompactSummaryRequest request, List<AgentMessage> messagePrefix) {
        ContextSnapshot current = request.context();
        List<AgentMessage> messages = new ArrayList<>(messagePrefix);
        messages.add(instructionFactory.instructionMessage());
        return new ContextSnapshot(
            current.systemPrompt(),
            List.copyOf(messages),
            current.model(),
            current.thinkingLevel(),
            current.mode(),
            current.permissionMode(),
            estimateBudget(current, messages)
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
