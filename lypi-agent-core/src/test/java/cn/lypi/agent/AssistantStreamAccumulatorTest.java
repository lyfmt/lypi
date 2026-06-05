package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.model.ToolCallDelta;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class AssistantStreamAccumulatorTest {
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void aggregatesTextDeltasIntoAssistantMessage() {
        AssistantStreamAccumulator accumulator = new AssistantStreamAccumulator(clock);

        accumulator.accept(new AssistantStart("msg-a"));
        accumulator.accept(new TextDelta("hel"));
        accumulator.accept(new TextDelta("lo"));
        accumulator.accept(new AssistantDone(Optional.of(new TokenUsage(10, 5, 1, 0)), Optional.of("end_turn")));

        AgentMessage message = accumulator.toMessage("fallback", false);

        assertThat(message.id()).isEqualTo("msg-a");
        assertThat(message.kind()).isEqualTo(MessageKind.TEXT);
        assertThat(message.content()).hasSize(1);
        assertThat(message.content().getFirst().kind()).isEqualTo(ContentBlockKind.TEXT);
        assertThat(message.content().getFirst().text()).isEqualTo("hello");
        assertThat(message.usage()).contains(new TokenUsage(10, 5, 1, 0));
        assertThat(message.stopReason()).contains("end_turn");
    }

    @Test
    void aggregatesThinkingAndToolCallBlocks() {
        AssistantStreamAccumulator accumulator = new AssistantStreamAccumulator(clock);

        accumulator.accept(new AssistantStart("msg-a"));
        accumulator.accept(new ThinkingDelta("plan"));
        accumulator.accept(new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true));
        accumulator.accept(new AssistantDone(Optional.empty(), Optional.of("tool_calls")));

        AgentMessage message = accumulator.toMessage("fallback", false);

        assertThat(message.kind()).isEqualTo(MessageKind.TOOL_CALL);
        assertThat(message.content()).extracting(block -> block.kind())
            .containsExactly(ContentBlockKind.THINKING, ContentBlockKind.TOOL_CALL);
        assertThat(message.content().get(1).text()).isEqualTo("{\"path\":\"pom.xml\"}");
        assertThat(accumulator.hasToolCalls()).isTrue();
        assertThat(accumulator.stopReason()).contains("tool_calls");
    }

    @Test
    void returnsPartialAssistantWhenAbortedBeforeDone() {
        AssistantStreamAccumulator accumulator = new AssistantStreamAccumulator(clock);

        accumulator.accept(new AssistantStart("msg-a"));
        accumulator.accept(new TextDelta("partial"));

        AgentMessage message = accumulator.toMessage("fallback", true);

        assertThat(message.id()).isEqualTo("msg-a");
        assertThat(message.content().getFirst().text()).isEqualTo("partial");
        assertThat(message.stopReason()).contains("aborted");
    }
}
