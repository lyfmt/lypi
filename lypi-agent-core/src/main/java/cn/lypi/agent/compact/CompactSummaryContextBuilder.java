package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CompactSummaryContextBuilder {
    private final CompactSummaryInstructionFactory instructionFactory;

    public CompactSummaryContextBuilder(CompactSummaryInstructionFactory instructionFactory) {
        this.instructionFactory = instructionFactory;
    }

    public ContextSnapshot build(CompactSummaryRequest request) {
        return build(request, summarizedMessages(request));
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

    private List<AgentMessage> summarizedMessages(CompactSummaryRequest request) {
        Set<String> summarizedEntryIds = new LinkedHashSet<>(request.plan().summarizedEntryIds());
        if (summarizedEntryIds.isEmpty()) {
            return request.context().messages();
        }
        List<AgentMessage> messages = new ArrayList<>();
        for (SessionEntry entry : request.branchEntries()) {
            if (summarizedEntryIds.contains(entry.id()) && entry instanceof MessageEntry messageEntry) {
                messages.add(messageEntry.message());
            }
        }
        return messages.isEmpty() ? request.context().messages() : List.copyOf(messages);
    }
}
