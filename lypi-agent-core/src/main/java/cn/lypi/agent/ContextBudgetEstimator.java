package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.ModelCatalogPort;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.prompt.SystemPrompt;
import java.math.BigDecimal;
import java.util.List;

public final class ContextBudgetEstimator {
    private static final int DEFAULT_CONTEXT_WINDOW = 256_000;
    private static final double AUTO_COMPACT_RATIO = 0.8d;
    private static final int DEFAULT_AUTO_COMPACT_THRESHOLD = autoCompactThreshold(DEFAULT_CONTEXT_WINDOW);
    private static final int DEFAULT_TURN_OUTPUT_BUDGET = 8_192;
    private static final int DEFAULT_TOOL_RESULT_BUDGET = 16_384;

    private final int contextWindow;
    private final int autoCompactThreshold;
    private final int turnOutputBudget;
    private final int toolResultBudget;
    private final ModelCatalogPort modelCatalog;

    public ContextBudgetEstimator() {
        this(
            DEFAULT_CONTEXT_WINDOW,
            DEFAULT_AUTO_COMPACT_THRESHOLD,
            DEFAULT_TURN_OUTPUT_BUDGET,
            DEFAULT_TOOL_RESULT_BUDGET
        );
    }

    public ContextBudgetEstimator(ModelCatalogPort modelCatalog) {
        this.contextWindow = DEFAULT_CONTEXT_WINDOW;
        this.autoCompactThreshold = DEFAULT_AUTO_COMPACT_THRESHOLD;
        this.turnOutputBudget = DEFAULT_TURN_OUTPUT_BUDGET;
        this.toolResultBudget = DEFAULT_TOOL_RESULT_BUDGET;
        this.modelCatalog = modelCatalog;
    }

    ContextBudgetEstimator(int contextWindow, int autoCompactThreshold, int turnOutputBudget, int toolResultBudget) {
        this.contextWindow = contextWindow;
        this.autoCompactThreshold = autoCompactThreshold;
        this.turnOutputBudget = turnOutputBudget;
        this.toolResultBudget = toolResultBudget;
        this.modelCatalog = null;
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

    public ContextBudget estimate(SystemPrompt systemPrompt, List<AgentMessage> messages, ModelSelection model) {
        int systemPromptTokens = systemPrompt == null ? 0 : estimateText(systemPrompt.content());
        if (modelCatalog == null) {
            return budget(systemPromptTokens + estimateMessages(messages));
        }
        return budget(systemPromptTokens + estimateMessages(messages), contextWindow(model));
    }

    private int estimateMessages(List<AgentMessage> messages) {
        return messages.stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(this::estimateBlock)
            .sum();
    }

    private ContextBudget budget(int estimatedTokens) {
        return budget(estimatedTokens, contextWindow, autoCompactThreshold);
    }

    private ContextBudget budget(int estimatedTokens, int contextWindow) {
        return budget(estimatedTokens, contextWindow, autoCompactThreshold(contextWindow));
    }

    private ContextBudget budget(int estimatedTokens, int contextWindow, int autoCompactThreshold) {
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

    private int contextWindow(ModelSelection model) {
        if (modelCatalog == null || model == null) {
            return contextWindow;
        }
        return modelCatalog.find(model)
            .map(ModelDescriptor::contextWindow)
            .filter(window -> window > 0)
            .orElse(DEFAULT_CONTEXT_WINDOW);
    }

    private static int autoCompactThreshold(int contextWindow) {
        return (int) Math.floor(contextWindow * AUTO_COMPACT_RATIO);
    }

    private int estimateBlock(ContentBlock block) {
        return estimateText(block.text());
    }

    private int estimateText(String text) {
        String safeText = text == null ? "" : text;
        return Math.max(1, safeText.length() / 4);
    }
}
