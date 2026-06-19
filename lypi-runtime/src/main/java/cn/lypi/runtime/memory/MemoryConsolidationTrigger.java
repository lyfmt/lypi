package cn.lypi.runtime.memory;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.event.TurnEndEvent;
import java.util.List;

/**
 * 判断 turn 结束后是否需要后台记忆沉淀。
 */
public record MemoryConsolidationTrigger(
    int minimumMessageTokensToInit,
    int minimumTokensBetweenUpdate,
    int toolCallsBetweenUpdates
) {
    public static final int DEFAULT_MINIMUM_MESSAGE_TOKENS_TO_INIT = 10_000;
    public static final int DEFAULT_MINIMUM_TOKENS_BETWEEN_UPDATE = 5_000;
    public static final int DEFAULT_TOOL_CALLS_BETWEEN_UPDATES = 3;

    public MemoryConsolidationTrigger() {
        this(
            DEFAULT_MINIMUM_MESSAGE_TOKENS_TO_INIT,
            DEFAULT_MINIMUM_TOKENS_BETWEEN_UPDATE,
            DEFAULT_TOOL_CALLS_BETWEEN_UPDATES
        );
    }

    /**
     * 返回 turn end 事件是否具备发起后台沉淀的基础条件。
     *
     * NOTE: 后台沉淀 turn 自身不能递归触发沉淀。
     */
    public boolean isEligible(TurnEndEvent event, boolean consolidationSession) {
        if (event == null || consolidationSession) {
            return false;
        }
        return "COMPLETED".equalsIgnoreCase(event.status());
    }

    /**
     * 按 Claude Code session memory 风格判断当前上下文是否达到抽取阈值。
     */
    public boolean shouldExtract(List<AgentMessage> messages, ExtractionState state) {
        ExtractionState effectiveState = state == null ? new ExtractionState() : state;
        int currentTokenCount = estimateTokens(messages);
        if (!effectiveState.initialized()) {
            if (currentTokenCount < minimumMessageTokensToInit) {
                return false;
            }
            effectiveState.markInitialized();
        }
        boolean tokenThresholdMet =
            currentTokenCount - effectiveState.tokensAtLastExtraction() >= minimumTokensBetweenUpdate;
        if (!tokenThresholdMet) {
            return false;
        }
        int toolCallsSinceLastUpdate = countToolCallsAfter(messages, effectiveState.lastExtractedMessageId());
        boolean toolThresholdMet = toolCallsSinceLastUpdate >= toolCallsBetweenUpdates;
        boolean naturalBreak = !hasToolCallsInLastAssistantTurn(messages);
        if (toolThresholdMet || naturalBreak) {
            effectiveState.recordExtraction(currentTokenCount, lastMessageId(messages, naturalBreak));
            return true;
        }
        return false;
    }

    private int estimateTokens(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (AgentMessage message : messages) {
            if (message == null || message.content() == null) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                tokens += estimateBlock(block);
            }
        }
        return tokens;
    }

    private int estimateBlock(ContentBlock block) {
        if (block == null) {
            return 0;
        }
        String text = block.text();
        return Math.max(1, (text == null ? "" : text).length() / 4);
    }

    private int countToolCallsAfter(List<AgentMessage> messages, String sinceMessageId) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        boolean foundStart = sinceMessageId == null || sinceMessageId.isBlank();
        int count = 0;
        for (AgentMessage message : messages) {
            if (message == null) {
                continue;
            }
            if (!foundStart) {
                if (sinceMessageId.equals(message.id())) {
                    foundStart = true;
                }
                continue;
            }
            if (message.content() == null) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolCallContentBlock) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean hasToolCallsInLastAssistantTurn(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            AgentMessage message = messages.get(index);
            if (message == null || message.role() != cn.lypi.contracts.context.MessageRole.ASSISTANT) {
                continue;
            }
            if (message.content() == null) {
                return false;
            }
            return message.content().stream().anyMatch(ToolCallContentBlock.class::isInstance);
        }
        return false;
    }

    private String lastMessageId(List<AgentMessage> messages, boolean naturalBreak) {
        if (!naturalBreak || messages == null || messages.isEmpty()) {
            return null;
        }
        AgentMessage last = messages.getLast();
        return last == null ? null : last.id();
    }

    public static final class ExtractionState {
        private boolean initialized;
        private int tokensAtLastExtraction;
        private String lastExtractedMessageId;

        boolean initialized() {
            return initialized;
        }

        void markInitialized() {
            initialized = true;
        }

        int tokensAtLastExtraction() {
            return tokensAtLastExtraction;
        }

        String lastExtractedMessageId() {
            return lastExtractedMessageId;
        }

        void recordExtraction(int tokenCount, String messageId) {
            tokensAtLastExtraction = tokenCount;
            if (messageId != null && !messageId.isBlank()) {
                lastExtractedMessageId = messageId;
            }
        }
    }
}
