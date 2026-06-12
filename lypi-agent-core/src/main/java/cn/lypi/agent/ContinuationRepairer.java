package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.ErrorContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

final class ContinuationRepairer {
    private static final String TOOL_INTERRUPTED_TEXT = "用户中断，工具未执行。";
    private static final String THINKING_INTERRUPTED_TEXT = "用户中断，上一轮未完成。";
    static final String TOOL_CALL_UNSAFE_REASON = "cannot-continue-from-tool-call-assistant";

    private final AgentMessageFactory messageFactory;

    ContinuationRepairer(AgentMessageFactory messageFactory) {
        this.messageFactory = messageFactory;
    }

    Optional<AgentMessage> repairMessage(AgentMessage leafMessage, Supplier<String> messageIdSupplier) {
        if (leafMessage == null
            || leafMessage.role() != MessageRole.ASSISTANT
            || leafMessage.content() == null
            || leafMessage.content().isEmpty()
            || leafMessage.kind() == MessageKind.ERROR) {
            return Optional.empty();
        }
        List<ToolCallContentBlock> toolCalls = leafMessage.content().stream()
            .filter(ToolCallContentBlock.class::isInstance)
            .map(ToolCallContentBlock.class::cast)
            .toList();
        if (!toolCalls.isEmpty()) {
            if (!isAborted(leafMessage)) {
                return Optional.empty();
            }
            return Optional.of(toolRepair(messageIdSupplier.get(), toolCalls));
        }
        if (isThinkingOnly(leafMessage)) {
            return Optional.of(thinkingRepair(messageIdSupplier.get()));
        }
        return Optional.empty();
    }

    boolean isUnsafeWithoutRepair(AgentMessage leafMessage) {
        if (leafMessage == null
            || leafMessage.role() != MessageRole.ASSISTANT
            || leafMessage.content() == null
            || leafMessage.content().isEmpty()
            || leafMessage.kind() == MessageKind.ERROR
            || isAborted(leafMessage)) {
            return false;
        }
        return leafMessage.content().stream().anyMatch(ToolCallContentBlock.class::isInstance);
    }

    private AgentMessage toolRepair(String messageId, List<ToolCallContentBlock> toolCalls) {
        List<ToolResultContentBlock> blocks = new ArrayList<>();
        for (ToolCallContentBlock toolCall : toolCalls) {
            Map<String, Object> metadata = interruptedMetadata();
            metadata.put("openaiPendingToolOutput", true);
            blocks.add(new ToolResultContentBlock(
                toolCall.toolUseId(),
                TOOL_INTERRUPTED_TEXT,
                true,
                Map.copyOf(metadata)
            ));
        }
        return messageFactory.toolResultMessage(messageId, blocks, Optional.empty());
    }

    private AgentMessage thinkingRepair(String messageId) {
        Map<String, Object> metadata = interruptedMetadata();
        return messageFactory.assistantMessage(
            messageId,
            MessageKind.ERROR,
            List.of(new ErrorContentBlock("interrupted-thinking", THINKING_INTERRUPTED_TEXT, Map.copyOf(metadata))),
            Optional.empty(),
            Optional.of("error")
        );
    }

    private Map<String, Object> interruptedMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("syntheticInterrupted", true);
        return metadata;
    }

    private boolean isThinkingOnly(AgentMessage message) {
        return isAborted(message)
            && message.content().stream().allMatch(block ->
            block instanceof ThinkingContentBlock || block.kind() == ContentBlockKind.THINKING
        );
    }

    private boolean isAborted(AgentMessage message) {
        return message.stopReason().filter("aborted"::equals).isPresent();
    }
}
