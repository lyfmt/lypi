package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.prompt.SystemPrompt;
import java.math.BigDecimal;
import java.util.List;

public final class ContextBudgetEstimator {
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_AUTO_COMPACT_THRESHOLD = 100_000;
    private static final int DEFAULT_TURN_OUTPUT_BUDGET = 8_192;
    private static final int DEFAULT_TOOL_RESULT_BUDGET = 16_384;

    private final int contextWindow;
    private final int autoCompactThreshold;
    private final int turnOutputBudget;
    private final int toolResultBudget;

    public ContextBudgetEstimator() {
        this(
            DEFAULT_CONTEXT_WINDOW,
            DEFAULT_AUTO_COMPACT_THRESHOLD,
            DEFAULT_TURN_OUTPUT_BUDGET,
            DEFAULT_TOOL_RESULT_BUDGET
        );
    }

    ContextBudgetEstimator(int contextWindow, int autoCompactThreshold, int turnOutputBudget, int toolResultBudget) {
        this.contextWindow = contextWindow;
        this.autoCompactThreshold = autoCompactThreshold;
        this.turnOutputBudget = turnOutputBudget;
        this.toolResultBudget = toolResultBudget;
    }

    public ContextBudget estimate(List<AgentMessage> messages) {
        return budget(estimateMessages(messages));
    }

    public ContextBudget estimate(ContextSnapshot snapshot) {
        return estimate(snapshot.systemPrompt(), snapshot.messages());
    }

    public ContextBudget estimate(SystemPrompt systemPrompt, List<AgentMessage> messages) {
        int systemPromptTokens = systemPrompt == null ? 0 : estimateText(systemPrompt.content());
        return budget(systemPromptTokens + estimateMessages(messages));
    }

    private int estimateMessages(List<AgentMessage> messages) {
        return messages.stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(this::estimateBlock)
            .sum();
    }

    private ContextBudget budget(int estimatedTokens) {
        return new ContextBudget(
            estimatedTokens,
            contextWindow,
            autoCompactThreshold,
            turnOutputBudget,
            toolResultBudget,
            0,
            0,
            BigDecimal.ZERO
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
