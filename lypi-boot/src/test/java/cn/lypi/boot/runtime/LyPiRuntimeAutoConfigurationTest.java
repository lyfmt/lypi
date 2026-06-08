package cn.lypi.boot.runtime;

import cn.lypi.boot.tool.LyPiToolAutoConfiguration;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.runtime.subagent.MailboxDeliveryGuard;
import cn.lypi.runtime.subagent.SubagentProcessRunner;
import cn.lypi.session.SessionManagerImpl;
import cn.lypi.tool.PermissionGateResult;
import cn.lypi.tool.PermissionPromptPort;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LyPiRuntimeAutoConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void createsDefaultInMemoryEventBusWhenMissing() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(EventBus.class);
                assertThat(context.getBean(EventBus.class)).isInstanceOf(InMemoryEventBus.class);
            });
    }

    @Test
    void keepsUserProvidedEventBus() {
        EventBus customEventBus = new RecordingEventBus();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(EventBus.class, () -> customEventBus)
            .run(context -> {
                assertThat(context).hasSingleBean(EventBus.class);
                assertThat(context.getBean(EventBus.class)).isSameAs(customEventBus);
            });
    }

    @Test
    void attachesTransportsToSameEventBusWhenSessionStateIsAvailable() {
        RecordingTransport first = new RecordingTransport("first");
        RecordingTransport second = new RecordingTransport("second");
        SessionRuntimeState state = sessionState();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean("firstTransport", TransportAdapter.class, () -> first)
            .withBean("secondTransport", TransportAdapter.class, () -> second)
            .withBean(SessionRuntimeState.class, () -> state)
            .run(context -> {
                EventBus eventBus = context.getBean(EventBus.class);

                assertThat(first.events.get()).isSameAs(eventBus);
                assertThat(second.events.get()).isSameAs(eventBus);
                assertThat(first.state.get()).isSameAs(state);
                assertThat(second.state.get()).isSameAs(state);
            });
    }

    @Test
    void connectorCanAttachExplicitSessionStateWhenNoStateBeanExists() {
        RecordingTransport transport = new RecordingTransport("transport");

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(TransportAdapter.class, () -> transport)
            .run(context -> {
                SessionRuntimeState state = sessionState();
                TransportEventConnector connector = context.getBean(TransportEventConnector.class);
                connector.attachAll(state);

                assertThat(transport.events.get()).isSameAs(context.getBean(EventBus.class));
                assertThat(transport.state.get()).isSameAs(state);
            });
    }

    @Test
    void runtimeAndToolAutoConfigurationShareDefaultEventBusWithTransports() {
        RecordingTransport transport = new RecordingTransport("transport");
        List<EventEnvelope> receivedEvents = new ArrayList<>();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class, LyPiToolAutoConfiguration.class)
            .withBean(TransportAdapter.class, () -> transport)
            .withBean(SessionRuntimeState.class, LyPiRuntimeAutoConfigurationTest::sessionState)
            .withBean(SecurityRuntimePort.class, () -> LyPiRuntimeAutoConfigurationTest::allowAllSecurity)
            .withBean(PermissionPromptPort.class, () -> handle -> PermissionGateResult.allow())
            .run(context -> {
                EventBus eventBus = context.getBean(EventBus.class);
                assertThat(eventBus).isInstanceOf(InMemoryEventBus.class);
                assertThat(transport.events.get()).isSameAs(eventBus);

                eventBus.subscribe(new EventFilter(java.util.Optional.empty(), java.util.Optional.empty()), receivedEvents::add);
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new SuccessTool());
                ToolResult<?> result = runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-probe", Map.of("path", "pom.xml"), "msg_1")),
                    contextSnapshot()
                ).getFirst();

                assertThat(result.isError()).isFalse();
                assertThat(receivedEvents.stream()
                    .map(EventEnvelope::event)
                    .filter(event -> event instanceof PermissionRequestEvent || event instanceof PermissionDecisionEvent)
                    .map(cn.lypi.contracts.event.AgentEvent::getClass))
                    .containsExactly(PermissionRequestEvent.class, PermissionDecisionEvent.class);
            });
    }

    @Test
    void createsDefaultSubagentRuntimeBeansWithConservativeDeliveryGuard() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(SessionManagerFactoryPort.class);
                assertThat(context).hasSingleBean(ChildSessionPort.class);
                assertThat(context).hasSingleBean(SessionManagerPort.class);
                assertThat(context).hasSingleBean(MailboxPort.class);
                assertThat(context).hasSingleBean(SubagentProcessRunner.class);
                assertThat(context).hasSingleBean(AgentCenterPort.class);
                assertThat(context.getBean(MailboxDeliveryGuard.class).canDeliver(null)).isFalse();
            });
    }

    @Test
    void defaultDeliveryGuardAllowsSameIdleSessionWhenRuntimeStateExists() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false))
            .run(context -> {
                MailboxDeliveryGuard guard = context.getBean(MailboxDeliveryGuard.class);

                assertThat(guard.canDeliver(mail("ses_parent"))).isTrue();
                assertThat(guard.canDeliver(mail("ses_other"))).isFalse();
            });
    }

    @Test
    void defaultDeliveryGuardKeepsMailboxPendingWhenRuntimeStateHasRunningTool() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", true))
            .run(context -> assertThat(context.getBean(MailboxDeliveryGuard.class).canDeliver(mail("ses_parent"))).isFalse());
    }

    @Test
    void bindsSubagentCommandToRunnerAndAgentCenter() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withPropertyValues(
                "lypi.subagent.command[0]=python3",
                "lypi.subagent.command[1]=-c",
                "lypi.subagent.command[2]=import json, sys; data=json.load(sys.stdin); print(json.dumps({'childSessionId':data['childSessionId'],'status':'SUCCEEDED','summary':'ok','finalEntryId':'msg_final'}))"
            )
            .run(context -> {
                SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);
                sessionManager.openOrCreate("ses_parent");
                sessionManager.appendMessage(new AgentMessage(
                    "msg_parent",
                    MessageRole.USER,
                    MessageKind.TEXT,
                    List.of(new cn.lypi.contracts.context.TextContentBlock("parent", Map.of())),
                    Instant.EPOCH,
                    java.util.Optional.empty(),
                    java.util.Optional.empty()
                ));
                AgentCenterPort agentCenter = context.getBean(AgentCenterPort.class);

                SubagentSpawnResult result = agentCenter.spawn(new SubagentSpawnRequest(
                    "ses_parent",
                    sessionManager.currentView().leafId(),
                    "执行检查",
                    tempDir,
                    List.of(),
                    PermissionMode.DEFAULT_EXECUTE,
                    30,
                    java.util.Optional.empty(),
                    java.util.Optional.empty()
                ));

                assertThat(result.status()).isEqualTo(SubagentRunStatus.STARTED);
                assertThat(result.agentId()).isNotBlank();
            });
    }

    @Test
    void keepsUserProvidedSessionManagerForMailboxAndAgentCenter() {
        SessionManagerPort sessionManager = new SessionManagerImpl(tempDir);
        sessionManager.openOrCreate("ses_custom");

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionManagerPort.class, () -> sessionManager)
            .run(context -> assertThat(context.getBean(SessionManagerPort.class)).isSameAs(sessionManager));
    }

    @Test
    void keepsUserProvidedMailboxPortWithoutRequiringDefaultAgentCenter() {
        MailboxPort mailbox = new NoopMailbox();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(MailboxPort.class, () -> mailbox)
            .run(context -> {
                assertThat(context).hasSingleBean(MailboxPort.class);
                assertThat(context.getBean(MailboxPort.class)).isSameAs(mailbox);
                assertThat(context).doesNotHaveBean(AgentCenterPort.class);
            });
    }

    private static PermissionDecision allowAllSecurity(ToolUseRequest request, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "allowed",
            java.util.Optional.empty(),
            Map.of()
        );
    }

    private static ContextSnapshot contextSnapshot() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO)
        );
    }

    private static SessionRuntimeState sessionState() {
        return sessionState("session-1", false);
    }

    private static SessionRuntimeState sessionState(String sessionId, boolean hasInterruptibleTool) {
        return new SessionRuntimeState(
            sessionId,
            Path.of(".").toAbsolutePath().normalize(),
            "leaf-1",
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO),
            hasInterruptibleTool
        );
    }

    private static MailboxMessage mail(String parentSessionId) {
        return new MailboxMessage(
            "mail_1",
            "agent_1",
            "ses_child",
            parentSessionId,
            "entry_spawn",
            "完成摘要",
            new SubagentResultRef("ses_child", "entry_final", java.util.Optional.empty()),
            MailboxStatus.PENDING,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }

    private static final class RecordingTransport implements TransportAdapter {
        private final String name;
        private final AtomicReference<EventBus> events = new AtomicReference<>();
        private final AtomicReference<SessionRuntimeState> state = new AtomicReference<>();

        private RecordingTransport(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void attach(EventBus events, SessionRuntimeState state) {
            this.events.set(events);
            this.state.set(state);
        }
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<cn.lypi.contracts.event.AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(cn.lypi.contracts.event.AgentEvent event) {
            events.add(event);
        }

        @Override
        public cn.lypi.contracts.event.EventSubscription subscribe(
            cn.lypi.contracts.event.EventFilter filter,
            cn.lypi.contracts.event.EventConsumer consumer
        ) {
            return () -> {
            };
        }
    }

    private static final class NoopMailbox implements MailboxPort {
        @Override
        public List<cn.lypi.contracts.subagent.MailboxMessage> read(
            String sessionId,
            java.util.Set<cn.lypi.contracts.subagent.MailboxStatus> statuses
        ) {
            return List.of();
        }

        @Override
        public cn.lypi.contracts.subagent.MailboxCommandResult accept(String sessionId, String mailId) {
            return cn.lypi.contracts.subagent.MailboxCommandResult.failure("not used");
        }

        @Override
        public cn.lypi.contracts.subagent.MailboxCommandResult stash(String sessionId, String mailId) {
            return cn.lypi.contracts.subagent.MailboxCommandResult.failure("not used");
        }

        @Override
        public cn.lypi.contracts.subagent.MailboxCommandResult discard(String sessionId, String mailId) {
            return cn.lypi.contracts.subagent.MailboxCommandResult.failure("not used");
        }
    }

    private static final class SuccessTool implements Tool<Map<String, Object>, String> {
        @Override
        public String name() {
            return "ask-probe";
        }

        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public cn.lypi.contracts.common.JsonSchema inputSchema() {
            return new cn.lypi.contracts.common.JsonSchema(Map.of());
        }

        @Override
        public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
            return new ValidationResult(true, List.of());
        }

        @Override
        public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
            return new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "需要确认",
                java.util.Optional.empty(),
                Map.of()
            );
        }

        @Override
        public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
            return new ToolResult<>("content", false, List.of(serializeForContext("content")), java.util.Optional.empty());
        }

        @Override
        public cn.lypi.contracts.tool.InterruptBehavior interruptBehavior() {
            return cn.lypi.contracts.tool.InterruptBehavior.CANCEL;
        }

        @Override
        public boolean isReadOnly(Map<String, Object> input) {
            return true;
        }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) {
            return true;
        }

        @Override
        public boolean isDestructive(Map<String, Object> input) {
            return false;
        }

        @Override
        public int maxResultSize() {
            return 4096;
        }

        @Override
        public String renderForUser(Map<String, Object> input) {
            return "ask-probe " + input;
        }

        @Override
        public cn.lypi.contracts.context.AgentMessage serializeForContext(String output) {
            return new cn.lypi.contracts.context.AgentMessage(
                "msg_tool_result",
                MessageRole.TOOL_RESULT,
                MessageKind.TOOL_RESULT,
                List.of(new ToolResultContentBlock("toolu_1", output, false)),
                Instant.EPOCH,
                java.util.Optional.empty(),
                java.util.Optional.empty()
            );
        }
    }
}
