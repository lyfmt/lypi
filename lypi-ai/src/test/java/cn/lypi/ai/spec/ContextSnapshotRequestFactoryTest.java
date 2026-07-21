package cn.lypi.ai.spec;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
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

        LypiModelRequest request = ContextSnapshotRequestFactory.from(snapshot, "req-1", List.of());

        assertThat(request.requestId()).isEqualTo("req-1");
        assertThat(request.model()).isEqualTo(new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH));
        assertThat(request.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(request.systemPrompt()).isEqualTo("system prompt");
        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().getFirst().role()).isEqualTo(LypiRole.USER);
        assertThat(request.messages().getFirst().content())
            .containsExactly(new LypiTextBlock("hello model", Map.of()));
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

        LypiModelRequest request = ContextSnapshotRequestFactory.from(snapshot, "req-2", List.of());

        assertThat(request.messages()).containsExactly(new LypiMessage(
            LypiRole.ASSISTANT,
            List.of(
                new LypiTextBlock("answer", Map.of()),
                new LypiThinkingBlock("private reasoning", Map.of()),
                new LypiToolCallBlock("call-1", "read_file", "{\"path\":\"pom.xml\"}", Map.of("raw", "yes"))
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

        LypiModelRequest request = ContextSnapshotRequestFactory.from(snapshot, "req-state", List.of());

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

        LypiModelRequest request = ContextSnapshotRequestFactory.from(snapshot, "req-3", List.of());

        assertThat(request.messages()).containsExactly(new LypiMessage(
            LypiRole.TOOL_RESULT,
            List.of(
                new LypiToolResultBlock("call-1", "file contents", false, Map.of("bytes", 12)),
                new LypiAttachmentBlock("att-1", "image description", "image/png", Map.of(
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
        LypiToolSpec tool = new LypiToolSpec(
            "math_operation",
            "Perform math",
            Map.of("type", "object", "properties", Map.of("a", Map.of("type", "number")))
        );

        LypiModelRequest request = ContextSnapshotRequestFactory.from(context(List.of()), "req-4", List.of(tool));

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
        List<cn.lypi.contracts.context.ContentBlock> content
    ) {
        return new AgentMessage(id, role, kind, content, Instant.EPOCH, Optional.empty(), Optional.empty());
    }
}
