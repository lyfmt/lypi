package cn.lypi.agent;

import cn.lypi.agent.compact.NoopCompactionCoordinator;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.tool.ToolResult;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultTurnExecutorPermissionTest {
    @Test
    void permissionDenyBackfillsErrorToolResultAndContinuesModelLoop() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "write", Map.of("path", "notes.txt", "content", "secret"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("没有权限，我不会执行写入。"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "权限请求未获允许: 用户拒绝。",
            true,
            List.of(AgentCoreTestFixtures.toolResultMessage(
                "msg-tool-result",
                "toolu-1",
                "权限请求未获允许: 用户拒绝。",
                true
            )),
            Optional.empty()
        )));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback-1", "msg-fallback-2"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest(
            "session-1",
            "write notes",
            Optional.empty(),
            () -> false
        ));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(provider.contexts).hasSize(2);
        assertThat(tools.requests).hasSize(1);
        assertThat(tools.requests.getFirst()).extracting(request -> request.toolUseId())
            .containsExactly("toolu-1");
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-tool-call", "msg-tool-result", "msg-final");
        assertThat(session.messages()).extracting(AgentMessage::role)
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL_RESULT, MessageRole.ASSISTANT);

        AgentMessage permissionResult = session.messages().get(2);
        ToolResultContentBlock block = (ToolResultContentBlock) permissionResult.content().getFirst();
        assertThat(block.toolUseId()).isEqualTo("toolu-1");
        assertThat(block.error()).isTrue();
        assertThat(block.text()).contains("用户拒绝");
        assertThat(provider.contexts.get(1).messages()).contains(permissionResult);
        assertThat(eventBus.events).extracting(AgentEvent::getClass)
            .contains(
                TurnStartEvent.class,
                MessageStartEvent.class,
                MessageEndEvent.class,
                ToolStartEvent.class,
                ToolEndEvent.class,
                TurnEndEvent.class
            );
        ToolEndEvent toolEnd = eventBus.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(ToolEndEvent.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(toolEnd.error()).isTrue();
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("COMPLETED");
    }
}
