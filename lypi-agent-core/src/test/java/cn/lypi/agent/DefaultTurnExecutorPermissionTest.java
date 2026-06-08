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
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
            new AgentCoreRuntimePorts(
                Path.of("."),
                session,
                provider,
                tools,
                AgentCoreTestFixtures.allowAllSecurityRuntime(),
                AgentCoreTestFixtures.fixedResourceRuntime("system"),
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
                TurnEndEvent.class
            );
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent))
            .isEmpty();
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void permissionDenyEventsOccurBetweenToolStartAndToolEndBeforeBackfilledToolResult() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        PermissionEventToolRuntime tools = new PermissionEventToolRuntime(eventBus);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "write", Map.of("path", "notes.txt"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("已收到权限拒绝。"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            new AgentCoreRuntimePorts(
                Path.of("."),
                session,
                provider,
                tools,
                AgentCoreTestFixtures.allowAllSecurityRuntime(),
                AgentCoreTestFixtures.fixedResourceRuntime("system"),
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
        assertThat(eventBus.events).extracting(AgentEvent::getClass)
            .containsExactly(
                TurnStartEvent.class,
                MessageStartEvent.class,
                MessageEndEvent.class,
                MessageStartEvent.class,
                MessageEndEvent.class,
                ToolStartEvent.class,
                PermissionRequestEvent.class,
                PermissionDecisionEvent.class,
                ToolEndEvent.class,
                MessageStartEvent.class,
                MessageEndEvent.class,
                MessageStartEvent.class,
                cn.lypi.contracts.event.MessageDeltaEvent.class,
                MessageEndEvent.class,
                TurnEndEvent.class
            );
        PermissionDecisionEvent decision = (PermissionDecisionEvent) eventBus.events.get(7);
        ToolEndEvent toolEnd = (ToolEndEvent) eventBus.events.get(8);
        assertThat(decision.decision().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(toolEnd.error()).isTrue();
        assertThat(session.messages()).extracting(AgentMessage::role)
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL_RESULT, MessageRole.ASSISTANT);
    }

    private static final class PermissionEventToolRuntime implements ToolRuntimePort {
        private final AgentCoreTestFixtures.RecordingEventBus eventBus;
        private final List<List<ToolUseRequest>> requests = new ArrayList<>();

        private PermissionEventToolRuntime(AgentCoreTestFixtures.RecordingEventBus eventBus) {
            this.eventBus = eventBus;
        }

        @Override
        public void register(Tool<?, ?> tool) {
        }

        @Override
        public Optional<Tool<?, ?>> resolve(String nameOrAlias) {
            return Optional.empty();
        }

        @Override
        public ToolRegistrySnapshot snapshot() {
            return new ToolRegistrySnapshot(List.of());
        }

        @Override
        public Path cwd() {
            return Path.of(".").toAbsolutePath().normalize();
        }

        @Override
        public List<ToolResult<?>> execute(List<ToolUseRequest> requests, cn.lypi.contracts.context.ContextSnapshot context) {
            this.requests.add(List.copyOf(requests));
            ToolUseRequest request = requests.getFirst();
            eventBus.publish(new ToolStartEvent(
                "session-1",
                request.toolUseId(),
                "msg-tool-call",
                "turn-1",
                request.toolName(),
                request.toolName(),
                "write notes.txt",
                Map.of(),
                Instant.EPOCH,
                Instant.EPOCH
            ));
            PermissionDecision ask = new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "需要确认",
                Optional.empty(),
                Map.of()
            );
            eventBus.publish(new PermissionRequestEvent(
                "session-1",
                request.toolUseId(),
                request.toolName(),
                "write notes.txt",
                "需要确认",
                ask,
                Instant.EPOCH
            ));
            eventBus.publish(new PermissionDecisionEvent(
                "session-1",
                request.toolUseId(),
                request.toolName(),
                "write notes.txt",
                new PermissionDecision(
                    PermissionBehavior.DENY,
                    PermissionDecisionReason.TOOL_SPECIFIC,
                    "用户拒绝",
                    Optional.empty(),
                    Map.of()
                ),
                Instant.EPOCH
            ));
            eventBus.publish(new ToolEndEvent(
                "session-1",
                request.toolUseId(),
                ToolExecutionStatus.FAILED,
                null,
                new ToolResultSummary(
                    "permission denied",
                    "用户拒绝",
                    true,
                    null,
                    false,
                    0L,
                    Map.of()
                ),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                0L,
                Map.of(),
                Instant.EPOCH
            ));
            return List.of(new ToolResult<>(
                "权限请求未获允许: 用户拒绝。",
                true,
                List.of(AgentCoreTestFixtures.toolResultMessage(
                    "msg-tool-result",
                    request.toolUseId(),
                    "权限请求未获允许: 用户拒绝。",
                    true
                )),
                Optional.empty()
            ));
        }
    }
}
