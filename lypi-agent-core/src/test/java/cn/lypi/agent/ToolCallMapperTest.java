package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static org.assertj.core.api.Assertions.assertThat;

class ToolCallMapperTest {
    @Test
    void mapsToolCallBlocksInAssistantOrder() {
        AgentMessage assistant = new AgentMessage(
            "msg-a",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(
                new TextContentBlock("checking"),
                new ToolCallContentBlock("toolu-1", "read", "", Map.of(
                    "input", Map.of("path", "pom.xml"),
                    "complete", true
                )),
                new ToolCallContentBlock("toolu-2", "bash", "", Map.of(
                    "input", Map.of("timeout", 10L, "trusted", true),
                    "complete", true
                ))
            ),
            NOW,
            Optional.empty(),
            Optional.of("tool_calls")
        );

        List<ToolUseRequest> requests = new ToolCallMapper().requestsFrom(assistant);

        assertThat(requests).extracting(ToolUseRequest::toolUseId)
            .containsExactly("toolu-1", "toolu-2");
        assertThat(requests).extracting(ToolUseRequest::toolName)
            .containsExactly("read", "bash");
        assertThat(requests).allSatisfy(request -> assertThat(request.parentMessageId()).isEqualTo("msg-a"));
        assertThat(requests.getFirst().input()).containsEntry("path", "pom.xml");
        assertThat(requests.get(1).input()).containsEntry("timeout", 10L).containsEntry("trusted", true);
    }

    @Test
    void returnsEmptyListWhenAssistantHasNoToolCalls() {
        AgentMessage assistant = assistantMessage("msg-a", "hello");

        assertThat(new ToolCallMapper().requestsFrom(assistant)).isEmpty();
    }

    @Test
    void ignoresIncompleteToolCallBlocks() {
        AgentMessage assistant = new AgentMessage(
            "msg-a",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new ToolCallContentBlock(
                "toolu-1",
                "read",
                "",
                Map.of("input", Map.of("path", "pom.xml"), "complete", false)
            )),
            NOW,
            Optional.empty(),
            Optional.of("tool_calls")
        );

        assertThat(new ToolCallMapper().requestsFrom(assistant)).isEmpty();
    }

    @Test
    void keepsNestedStructuredInputFromMetadata() {
        Map<String, Object> input = Map.of(
            "options", Map.of("encoding", "utf-8"),
            "ranges", List.of(1, 2, 3),
            "ratio", 1.5
        );
        AgentMessage assistant = new AgentMessage(
            "msg-a",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new ToolCallContentBlock("toolu-1", "read", "", Map.of(
                "input", input,
                "complete", true
            ))),
            NOW,
            Optional.empty(),
            Optional.of("tool_calls")
        );

        List<ToolUseRequest> requests = new ToolCallMapper().requestsFrom(assistant);

        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().input()).containsAllEntriesOf(input);
    }
}
