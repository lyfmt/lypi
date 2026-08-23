package cn.lycode.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.TextContentBlock;
import cn.lycode.contracts.context.ToolResultContentBlock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolResultMessageMarkerTest {
    @Test
    void marksToolResultBlocksAsPendingForOpenAiContext() {
        AgentMessage message = new AgentMessage(
            "msg-tool",
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock("toolu-1", "content", false, Map.of("source", "runtime"))),
            Instant.parse("2026-01-01T00:00:00Z"),
            Optional.empty(),
            Optional.empty()
        );

        AgentMessage marked = ToolResultMessageMarker.markPendingToolOutput(message);

        ToolResultContentBlock block = (ToolResultContentBlock) marked.content().getFirst();
        assertThat(block.metadata())
            .containsEntry("source", "runtime")
            .containsEntry("openaiPendingToolOutput", true);
    }

    @Test
    void leavesNonToolResultMessagesUnchanged() {
        AgentMessage message = new AgentMessage(
            "msg-user",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock("hello")),
            Instant.parse("2026-01-01T00:00:00Z"),
            Optional.empty(),
            Optional.empty()
        );

        AgentMessage marked = ToolResultMessageMarker.markPendingToolOutput(message);

        assertThat(marked).isSameAs(message);
    }
}
