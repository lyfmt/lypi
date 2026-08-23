package cn.lycode.ai.spec;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.AttachmentContentBlock;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.TextContentBlock;
import cn.lycode.contracts.context.ThinkingContentBlock;
import cn.lycode.contracts.context.ToolCallContentBlock;
import cn.lycode.contracts.context.ToolResultContentBlock;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.security.AgentMode;
import cn.lycode.contracts.security.PermissionMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContextSnapshotRequestFactoryTest {
    @Test
    void convertsSystemPromptAndUserText() {
        ContextSnapshot snapshot = context(List.of(message(
            "msg-user",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock("hello model"))
        )));

        LyCodeModelRequest request = ContextSnapshotRequestFactory.from(snapshot, "req-1", List.of());

        assertThat(request.requestId()).isEqualTo("req-1");
        assertThat(request.model()).isEqualTo(new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH));
        assertThat(request.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(request.systemPrompt()).isEqualTo("system prompt");
        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().getFirst().role()).isEqualTo(LyCodeRole.USER);
        assertThat(request.messages().getFirst().content())
            .containsExactly(new LyCodeTextBlock("hello model", Map.of()));
        assertThat(request.metadata()).containsEntry("permissionMode", "ask");
    }

    @Test
    void preservesAssistantTextThinkingAndToolCallHistory() {
        ContextSnapshot snapshot = context(List.of(message(
            "msg-assistant",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(
                new TextContentBlock("answer"),
                new ThinkingContentBlock("private reasoning"),
                new ToolCallContentBlock("call-1", "read_file", "{\"path\":\"pom.xml\"}", Map.of("raw", "yes"))
            )
        )));

        LyCodeModelRequest request = ContextSnapshotRequestFactory.from(snapshot, "req-2", List.of());

        assertThat(request.messages()).containsExactly(new LyCodeMessage(
            LyCodeRole.ASSISTANT,
            List.of(
                new LyCodeTextBlock("answer", Map.of()),
                new LyCodeThinkingBlock("private reasoning", Map.of()),
                new LyCodeToolCallBlock("call-1", "read_file", "{\"path\":\"pom.xml\"}", Map.of("raw", "yes"))
            ),
            Map.of("messageId", "msg-assistant", "messageKind", "TEXT")
        ));
    }

    @Test
    void carriesProviderConversationStateFromAssistantBlockMetadata() {
        ContextSnapshot snapshot = context(List.of(message(
            "msg-assistant",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new TextContentBlock("answer", Map.of(
                "providerConversationState", Map.of(
                    "provider", "openai",
                    "style", "responses",
                    "previousResponseId", "resp-123"
                )
            )))
        )));

        LyCodeModelRequest request = ContextSnapshotRequestFactory.from(snapshot, "req-state", List.of());

        assertThat(request.metadata()).containsEntry("providerConversationState", Map.of(
            "provider", "openai",
            "style", "responses",
            "previousResponseId", "resp-123",
            "messageId", "msg-assistant"
        ));
    }

    @Test
    void preservesToolResultsAndAttachments() {
        ContextSnapshot snapshot = context(List.of(message(
            "msg-tool",
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(
                new ToolResultContentBlock("call-1", "file contents", false, Map.of("bytes", 12)),
                new AttachmentContentBlock("att-1", "image description", "image/png", Map.of(
                    "source", "clipboard",
                    "imageUrl", "data:image/png;base64,AAA",
                    "toolUseId", "toolu_1"
                ))
            )
        )));

        LyCodeModelRequest request = ContextSnapshotRequestFactory.from(snapshot, "req-3", List.of());

        assertThat(request.messages()).containsExactly(new LyCodeMessage(
            LyCodeRole.TOOL_RESULT,
            List.of(
                new LyCodeToolResultBlock("call-1", "file contents", false, Map.of("bytes", 12)),
                new LyCodeAttachmentBlock("att-1", "image description", "image/png", Map.of(
                    "source", "clipboard",
                    "imageUrl", "data:image/png;base64,AAA",
                    "toolUseId", "toolu_1"
                ))
            ),
            Map.of("messageId", "msg-tool", "messageKind", "TOOL_RESULT")
        ));
    }

    @Test
    void carriesManuallyDefinedToolSpecs() {
        LyCodeToolSpec tool = new LyCodeToolSpec(
            "math_operation",
            "Perform math",
            Map.of("type", "object", "properties", Map.of("a", Map.of("type", "number")))
        );

        LyCodeModelRequest request = ContextSnapshotRequestFactory.from(context(List.of()), "req-4", List.of(tool));

        assertThat(request.tools()).containsExactly(tool);
    }

    private static ContextSnapshot context(List<AgentMessage> messages) {
        return new ContextSnapshot(
            new SystemPrompt("system prompt", List.of("layer"), "hash"),
            messages,
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }

    private static AgentMessage message(
        String id,
        MessageRole role,
        MessageKind kind,
        List<cn.lycode.contracts.context.ContentBlock> content
    ) {
        return new AgentMessage(id, role, kind, content, Instant.EPOCH, Optional.empty(), Optional.empty());
    }
}
