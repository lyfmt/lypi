package cn.lycode.agent;

import cn.lycode.agent.compact.NoopCompactionCoordinator;
import cn.lycode.agent.compact.NoopToolMicroCompactor;
import cn.lycode.contracts.agent.TurnRequest;
import cn.lycode.contracts.agent.TurnState;
import cn.lycode.contracts.agent.TurnStatus;
import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.ToolResultContentBlock;
import cn.lycode.contracts.event.AgentEvent;
import cn.lycode.contracts.event.MessageEndEvent;
import cn.lycode.contracts.event.MessageStartEvent;
import cn.lycode.contracts.event.PermissionDecisionEvent;
import cn.lycode.contracts.event.PermissionRequestEvent;
import cn.lycode.contracts.event.ToolEndEvent;
import cn.lycode.contracts.event.ToolStartEvent;
import cn.lycode.contracts.event.TurnEndEvent;
import cn.lycode.contracts.event.TurnStartEvent;
import cn.lycode.contracts.model.AssistantDone;
import cn.lycode.contracts.model.AssistantStart;
import cn.lycode.contracts.model.TextDelta;
import cn.lycode.contracts.model.ToolCallDelta;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.runtime.ToolRuntimeInvocation;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.tool.Tool;
import cn.lycode.contracts.tool.ToolExecutionStatus;
import cn.lycode.contracts.tool.ToolRegistrySnapshot;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolResultSummary;
import cn.lycode.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lycode.agent.AgentCoreTestFixtures.NOW;
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
            AgentCoreTestFixtures.emptyResources(),
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
                new NoopToolMicroCompactor(),
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
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(AgentEvent::getClass))
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
            AgentCoreTestFixtures.emptyResources(),
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
                new NoopToolMicroCompactor(),
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
                cn.lycode.contracts.event.MessageDeltaEvent.class,
                MessageEndEvent.class,
                ToolStartEvent.class,
                PermissionRequestEvent.class,
                PermissionDecisionEvent.class,
                ToolEndEvent.class,
                MessageStartEvent.class,
                MessageEndEvent.class,
                MessageStartEvent.class,
                cn.lycode.contracts.event.MessageDeltaEvent.class,
                MessageEndEvent.class,
                TurnEndEvent.class
            );
        PermissionDecisionEvent decision = (PermissionDecisionEvent) eventBus.events.get(8);
        ToolEndEvent toolEnd = (ToolEndEvent) eventBus.events.get(9);
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
        public List<ToolResult<?>> execute(List<ToolUseRequest> requests, cn.lycode.contracts.context.ContextSnapshot context) {
            return execute(requests, context, new ToolRuntimeInvocation("session-1", "turn-1"));
        }

        @Override
        public List<ToolResult<?>> execute(
            List<ToolUseRequest> requests,
            cn.lycode.contracts.context.ContextSnapshot context,
            ToolRuntimeInvocation invocation
        ) {
            this.requests.add(List.copyOf(requests));
            ToolUseRequest request = requests.getFirst();
            String sessionId = invocation.sessionId();
            String turnId = invocation.turnId();
            PermissionDecision ask = new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "需要确认",
                Optional.empty(),
                Map.of()
            );
            eventBus.publish(new ToolStartEvent(
                sessionId,
                request.toolUseId(),
                request.parentMessageId(),
                turnId,
                request.toolName(),
                request.toolName(),
                "write notes.txt",
                request.input(),
                Instant.EPOCH,
                Instant.EPOCH
            ));
            eventBus.publish(new PermissionRequestEvent(
                sessionId,
                request.toolUseId(),
                request.toolName(),
                "write notes.txt",
                "需要确认",
                ask,
                Instant.EPOCH
            ));
            eventBus.publish(new PermissionDecisionEvent(
                sessionId,
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
                sessionId,
                request.toolUseId(),
                ToolExecutionStatus.FAILED,
                null,
                new ToolResultSummary(
                    request.toolName() + " failed",
                    "权限请求未获允许: 用户拒绝。",
                    true,
                    null,
                    false,
                    0L,
                    Map.of("toolName", request.toolName())
                ),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                0L,
                Map.of("toolName", request.toolName()),
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
