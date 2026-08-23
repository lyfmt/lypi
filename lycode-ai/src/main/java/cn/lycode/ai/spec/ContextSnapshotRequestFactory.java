package cn.lycode.ai.spec;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.AttachmentContentBlock;
import cn.lycode.contracts.context.ContentBlock;
import cn.lycode.contracts.context.ErrorContentBlock;
import cn.lycode.contracts.context.LegacyContentBlock;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.TextContentBlock;
import cn.lycode.contracts.context.ThinkingContentBlock;
import cn.lycode.contracts.context.ToolCallContentBlock;
import cn.lycode.contracts.context.ToolResultContentBlock;
import cn.lycode.contracts.context.ContextSnapshot;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ContextSnapshotRequestFactory {
    private ContextSnapshotRequestFactory() {
    }

    /**
     * 从上下文快照构造 provider 无关模型请求。
     *
     * 该请求是 ly-code 内部通用规格，provider adapter 再负责转换为具体厂商协议。
     */
    public static LyCodeModelRequest from(ContextSnapshot snapshot, String requestId, List<LyCodeToolSpec> tools) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tools, "tools");
        return new LyCodeModelRequest(
            requestId,
            snapshot.model(),
            snapshot.thinkingLevel(),
            snapshot.systemPrompt().content(),
            snapshot.messages().stream().map(ContextSnapshotRequestFactory::message).toList(),
            tools,
            LyCodeGenerationOptions.defaults(),
            metadata(snapshot)
        );
    }

    private static Map<String, Object> metadata(ContextSnapshot snapshot) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", snapshot.mode().name());
        metadata.put("permissionMode", snapshot.permissionMode().toJson());
        providerConversationState(snapshot).ifPresent(state -> metadata.put("providerConversationState", state));
        return metadata;
    }

    private static java.util.Optional<Map<String, Object>> providerConversationState(ContextSnapshot snapshot) {
        for (int i = snapshot.messages().size() - 1; i >= 0; i--) {
            AgentMessage message = snapshot.messages().get(i);
            if (message.role() != MessageRole.ASSISTANT) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                Object state = block.metadata().get("providerConversationState");
                if (state instanceof Map<?, ?> stateMap) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    stateMap.forEach((key, value) -> {
                        if (key != null) {
                            values.put(key.toString(), value);
                        }
                    });
                    values.putIfAbsent("messageId", message.id());
                    return java.util.Optional.of(Map.copyOf(values));
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static LyCodeMessage message(AgentMessage message) {
        return new LyCodeMessage(
            role(message.role()),
            message.content().stream().map(ContextSnapshotRequestFactory::content).toList(),
            Map.of("messageId", message.id(), "messageKind", message.kind().name())
        );
    }

    private static LyCodeRole role(MessageRole role) {
        return switch (role) {
            case USER -> LyCodeRole.USER;
            case ASSISTANT -> LyCodeRole.ASSISTANT;
            case TOOL_RESULT -> LyCodeRole.TOOL_RESULT;
            case SYSTEM_LOCAL -> LyCodeRole.SYSTEM_LOCAL;
        };
    }

    private static LyCodeContentBlock content(ContentBlock block) {
        return switch (block) {
            case TextContentBlock text -> new LyCodeTextBlock(text.text(), text.metadata());
            case ThinkingContentBlock thinking -> new LyCodeThinkingBlock(thinking.text(), thinking.metadata());
            case ToolCallContentBlock toolCall -> new LyCodeToolCallBlock(
                toolCall.toolUseId(),
                toolCall.toolName(),
                toolCall.text(),
                toolCall.metadata()
            );
            case ToolResultContentBlock toolResult -> new LyCodeToolResultBlock(
                toolResult.toolUseId(),
                toolResult.text(),
                toolResult.error(),
                toolResult.metadata()
            );
            case AttachmentContentBlock attachment -> new LyCodeAttachmentBlock(
                attachment.attachmentId(),
                attachment.text(),
                attachment.mediaType(),
                attachment.metadata()
            );
            case ErrorContentBlock error -> new LyCodeErrorBlock(error.errorId(), error.text(), error.metadata());
            case LegacyContentBlock legacy -> new LyCodeTextBlock(legacy.text(), legacy.metadata());
        };
    }
}
