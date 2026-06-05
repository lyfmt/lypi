package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
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
                new ToolCallContentBlock("toolu-1", "read", "{\"path\":\"pom.xml\"}"),
                new ToolCallContentBlock("toolu-2", "bash", "{\"timeout\":10,\"trusted\":true}")
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
}
