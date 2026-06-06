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
        List<AgentMessage> messages = new ArrayList<>(current.messages());
        messages.add(instructionFactory.instructionMessage());
        return new ContextSnapshot(
            current.systemPrompt(),
            List.copyOf(messages),
            current.model(),
            current.thinkingLevel(),
            current.mode(),
            current.permissionMode(),
            estimateBudget(current.budget(), messages)
        );
    }

    private ContextBudget estimateBudget(ContextBudget currentBudget, List<AgentMessage> messages) {
        int estimatedTokens = messages.stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(this::estimateBlock)
            .sum();
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
        String text = block.text() == null ? "" : block.text();
        return Math.max(1, text.length() / 4);
    }
}
