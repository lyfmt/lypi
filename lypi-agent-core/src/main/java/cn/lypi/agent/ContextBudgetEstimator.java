package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import java.math.BigDecimal;
import java.util.List;

public final class ContextBudgetEstimator {
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_AUTO_COMPACT_THRESHOLD = 100_000;
    private static final int DEFAULT_TURN_OUTPUT_BUDGET = 8_192;
    private static final int DEFAULT_TOOL_RESULT_BUDGET = 16_384;

    ContextBudget estimate(List<AgentMessage> messages) {
        int estimatedTokens = messages.stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(this::estimateBlock)
            .sum();

        return new ContextBudget(
            estimatedTokens,
            DEFAULT_CONTEXT_WINDOW,
            DEFAULT_AUTO_COMPACT_THRESHOLD,
            DEFAULT_TURN_OUTPUT_BUDGET,
            DEFAULT_TOOL_RESULT_BUDGET,
            0,
            0,
            BigDecimal.ZERO
        );
    }

    private int estimateBlock(ContentBlock block) {
        String text = block.text() == null ? "" : block.text();
        return Math.max(1, text.length() / 4);
    }
}
