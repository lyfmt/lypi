package cn.lypi.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.model.ToolCallDelta;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AssistantStreamEventMessageMapperTest {
    @Test
    void mapsAssistantStreamIntoSemanticMessageEventsWithStableBlockIds() {
        AssistantStreamEventMessageMapper mapper = new AssistantStreamEventMessageMapper(
            "ses_01",
            Instant.parse("2026-06-01T12:00:00Z")
        );
        List<AssistantStreamEvent> streamEvents = List.of(
            new AssistantStart("msg_01"),
            new TextDelta("hello "),
            new TextDelta("world"),
            new ThinkingDelta("checking"),
            new ToolCallDelta("toolu_01", "read", Map.of("path", "pom.xml"), false),
            new ToolCallDelta("toolu_01", "read", Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.of(new TokenUsage(10, 5, 2, 1)), Optional.of("stop"))
        );

        List<AgentEvent> events = streamEvents.stream()
            .flatMap(event -> mapper.map(event).stream())
            .toList();

        assertThat(events).hasSize(7);
        MessageStartEvent start = (MessageStartEvent) events.getFirst();
        MessageDeltaEvent textOne = (MessageDeltaEvent) events.get(1);
        MessageDeltaEvent textTwo = (MessageDeltaEvent) events.get(2);
        MessageDeltaEvent thinking = (MessageDeltaEvent) events.get(3);
        MessageDeltaEvent toolPartial = (MessageDeltaEvent) events.get(4);
        MessageDeltaEvent toolFinal = (MessageDeltaEvent) events.get(5);
        MessageEndEvent end = (MessageEndEvent) events.get(6);

        assertThat(start.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(start.kind()).isEqualTo(MessageKind.TEXT);
        assertThat(textOne.blockId()).isEqualTo("msg_01:text:0");
        assertThat(textTwo.blockId()).isEqualTo("msg_01:text:0");
        assertThat(textOne.blockKind()).isEqualTo(ContentBlockKind.TEXT);
        assertThat(thinking.blockId()).isEqualTo("msg_01:thinking:0");
        assertThat(thinking.blockKind()).isEqualTo(ContentBlockKind.THINKING);
        assertThat(toolPartial.blockId()).isEqualTo("toolu_01");
        assertThat(toolPartial.metadata()).containsEntry("complete", false);
        assertThat(toolFinal.blockId()).isEqualTo("toolu_01");
        assertThat(toolFinal.blockKind()).isEqualTo(ContentBlockKind.TOOL_CALL);
        assertThat(toolFinal.isFinal()).isTrue();
        assertThat(toolFinal.metadata())
            .containsEntry("toolUseId", "toolu_01")
            .containsEntry("toolName", "read")
            .containsEntry("partialInput", Map.of("path", "pom.xml"));
        assertThat(end.blocks())
            .extracting(block -> block.blockId())
            .containsExactly("msg_01:text:0", "msg_01:thinking:0", "toolu_01");
        assertThat(end.blocks().getFirst().text()).isEqualTo("hello world");
        assertThat(end.usage()).contains(new TokenUsage(10, 5, 2, 1));
        assertThat(end.stopReason()).contains("stop");
    }

    @Test
    void fallsBackToStableToolCallBlockIdWhenToolUseIdIsMissing() {
        AssistantStreamEventMessageMapper mapper = new AssistantStreamEventMessageMapper(
            "ses_01",
            Instant.parse("2026-06-01T12:00:00Z")
        );

        mapper.map(new AssistantStart("msg_01"));
        MessageDeltaEvent partial = (MessageDeltaEvent) mapper.map(
            new ToolCallDelta(null, null, null, false)
        ).getFirst();
        MessageDeltaEvent complete = (MessageDeltaEvent) mapper.map(
            new ToolCallDelta("", "", Map.of("path", "pom.xml"), true)
        ).getFirst();
        MessageEndEvent end = (MessageEndEvent) mapper.map(
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ).getFirst();

        assertThat(partial.blockId()).isEqualTo("msg_01:tool_call:0");
        assertThat(complete.blockId()).isEqualTo("msg_01:tool_call:0");
        assertThat(complete.isFinal()).isTrue();
        assertThat(complete.metadata())
            .containsEntry("toolUseId", "msg_01:tool_call:0")
            .containsEntry("toolName", "")
            .containsEntry("partialInput", Map.of("path", "pom.xml"))
            .containsEntry("complete", true);
        assertThat(end.kind()).isEqualTo(MessageKind.TOOL_CALL);
        assertThat(end.blocks()).singleElement()
            .satisfies(block -> assertThat(block.blockId()).isEqualTo("msg_01:tool_call:0"));
    }

    @Test
    void messageEndKindReflectsPureThinkingMessages() {
        AssistantStreamEventMessageMapper mapper = new AssistantStreamEventMessageMapper(
            "ses_01",
            Instant.parse("2026-06-01T12:00:00Z")
        );

        mapper.map(new AssistantStart("msg_01"));
        mapper.map(new ThinkingDelta("checking"));
        MessageEndEvent end = (MessageEndEvent) mapper.map(
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ).getFirst();

        assertThat(end.kind()).isEqualTo(MessageKind.THINKING);
        assertThat(end.blocks()).singleElement()
            .satisfies(block -> assertThat(block.blockKind()).isEqualTo(ContentBlockKind.THINKING));
    }

    @Test
    void mapsAssistantErrorToErrorEvent() {
        AssistantStreamEventMessageMapper mapper = new AssistantStreamEventMessageMapper(
            "ses_01",
            Instant.parse("2026-06-01T12:00:00Z")
        );

        AgentEvent event = mapper.map(new AssistantError("err_01", "bad input")).getFirst();

        ErrorEvent error = (ErrorEvent) event;
        assertThat(error.sessionId()).isEqualTo("ses_01");
        assertThat(error.errorId()).isEqualTo("err_01");
        assertThat(error.message()).isEqualTo("bad input");
    }
}
