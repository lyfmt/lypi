package cn.lypi.ai.spec;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ErrorContentBlock;
import cn.lypi.contracts.context.LegacyContentBlock;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.context.ContextSnapshot;
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
     * 该请求是 ly-pi 内部通用规格，provider adapter 再负责转换为具体厂商协议。
     */
    public static LypiModelRequest from(ContextSnapshot snapshot, String requestId, List<LypiToolSpec> tools) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tools, "tools");
        return new LypiModelRequest(
            requestId,
            snapshot.model(),
            snapshot.thinkingLevel(),
            snapshot.systemPrompt().content(),
            snapshot.messages().stream().map(ContextSnapshotRequestFactory::message).toList(),
            tools,
            LypiGenerationOptions.defaults(),
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

    private static LypiMessage message(AgentMessage message) {
        return new LypiMessage(
            role(message.role()),
            message.content().stream().map(ContextSnapshotRequestFactory::content).toList(),
            Map.of("messageId", message.id(), "messageKind", message.kind().name())
        );
    }

    private static LypiRole role(MessageRole role) {
        return switch (role) {
            case USER -> LypiRole.USER;
            case ASSISTANT -> LypiRole.ASSISTANT;
            case TOOL_RESULT -> LypiRole.TOOL_RESULT;
            case SYSTEM_LOCAL -> LypiRole.SYSTEM_LOCAL;
        };
    }

    private static LypiContentBlock content(ContentBlock block) {
        return switch (block) {
            case TextContentBlock text -> new LypiTextBlock(text.text(), text.metadata());
            case ThinkingContentBlock thinking -> new LypiThinkingBlock(thinking.text(), thinking.metadata());
            case ToolCallContentBlock toolCall -> new LypiToolCallBlock(
                toolCall.toolUseId(),
                toolCall.toolName(),
                toolCall.text(),
                toolCall.metadata()
            );
            case ToolResultContentBlock toolResult -> new LypiToolResultBlock(
                toolResult.toolUseId(),
                toolResult.text(),
                toolResult.error(),
                toolResult.metadata()
            );
            case AttachmentContentBlock attachment -> new LypiAttachmentBlock(
                attachment.attachmentId(),
                attachment.text(),
                attachment.mediaType(),
                attachment.metadata()
            );
            case ErrorContentBlock error -> new LypiErrorBlock(error.errorId(), error.text(), error.metadata());
            case LegacyContentBlock legacy -> new LypiTextBlock(legacy.text(), legacy.metadata());
        };
    }
}
